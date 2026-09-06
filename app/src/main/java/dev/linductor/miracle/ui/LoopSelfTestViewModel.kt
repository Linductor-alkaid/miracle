package dev.linductor.miracle.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.runtime.AgentRuntime
import dev.linductor.miracle.runtime.LoopEventParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * P3 自检 ViewModel：模型连通性（真实端点）与闭环干跑（脚本化决策，真实环境
 * observe/act）。干跑场景复用 P2 输入自检的靶点思路（自身 UI 内副作用断言）。
 */
class LoopSelfTestViewModel : ViewModel() {

    sealed interface ConnectivityState {
        data object Idle : ConnectivityState
        data object Running : ConnectivityState
        data class Done(val json: String) : ConnectivityState
    }

    sealed interface DryRunState {
        data object Idle : DryRunState
        data class Running(val scenario: String) : DryRunState
        data class Done(
            val scenario: String,
            val outcome: String,
            val ok: Boolean,
            val detail: String,
        ) : DryRunState
    }

    private val _connectivity = MutableStateFlow<ConnectivityState>(ConnectivityState.Idle)
    val connectivity: StateFlow<ConnectivityState> = _connectivity.asStateFlow()

    private val _dryRun = MutableStateFlow<DryRunState>(DryRunState.Idle)
    val dryRun: StateFlow<DryRunState> = _dryRun.asStateFlow()

    /** 干跑靶点计数（tap 副作用断言）。 */
    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount.asStateFlow()

    /** 干跑靶点归一化坐标（onGloballyPositioned 更新）。 */
    @Volatile
    var tapTarget: Pair<Double, Double> = 0.5 to 0.5

    fun recordTap() {
        _tapCount.value += 1
    }

    fun resetTapCount() {
        _tapCount.value = 0
    }

    /** 模型连通性自检（真实端点，文本-only 决策请求；阻塞在 IO 协程）。 */
    fun runConnectivity(context: Context) {
        if (_connectivity.value is ConnectivityState.Running) {
            return
        }
        _connectivity.value = ConnectivityState.Running
        viewModelScope.launch(Dispatchers.IO) {
            val json = AgentRuntime.modelConnectivity(context)
            android.util.Log.i("miracle/verify", "connectivity $json")
            _connectivity.value = ConnectivityState.Done(json)
        }
    }

    /**
     * 闭环干跑。场景：
     * ① complete：tap→tap→done，断言 Completed 且靶点计数≥2；
     * ② max_steps：maxSteps=2 + 4×tap 脚本，断言 MaxSteps；
     * ③ cancel：提交 1.5s 后协作取消，断言 Cancelled；
     * ④ r3：目标含"发送"（策略从严），tap 前触发 R3 确认弹窗（批准后完成）；
     * ⑤ takeover：提交 1.5s 后 Human Takeover（阻断新决策 + 取消 + RELEASE_ALL +
     *    确认失效），断言 Cancelled 且关闭干净。
     */
    fun runDryRun(context: Context, scenario: String) {
        if (_dryRun.value is DryRunState.Running || AgentRuntime.sessionOpen) {
            return
        }
        resetTapCount()
        _dryRun.value = DryRunState.Running(scenario)
        viewModelScope.launch(Dispatchers.Default) {
            val (goal, maxSteps, decisions) = when (scenario) {
                "complete", "r3" -> {
                    val tap = tapDecision()
                    Triple(
                        if (scenario == "r3") "发送测试消息" else "点击靶点两次",
                        8,
                        listOf(tap, tap, doneDecision()),
                    )
                }

                "max_steps" -> Triple("不断点击靶点", 2, List(4) { tapDecision() })
                "cancel", "takeover" -> Triple("点击靶点", 8, List(4) { tapDecision() })
                else -> {
                    _dryRun.value = DryRunState.Done(scenario, "Unknown", false, "未知场景")
                    return@launch
                }
            }
            val config = JSONObject()
                .put("transport", "scripted")
                .put("max_steps", maxSteps)
                .put("script", JSONArray(decisions))
                .toString()
            val error = AgentRuntime.startSession(context, goal, script = config)
            if (error != null) {
                android.util.Log.i(
                    "miracle/verify",
                    "dryrun scenario=$scenario outcome=OpenFailed ok=false detail=$error",
                )
                _dryRun.value = DryRunState.Done(scenario, "OpenFailed", false, error)
                return@launch
            }
            if (scenario == "cancel") {
                launchCancelAfterDelay()
            }
            if (scenario == "takeover") {
                launchTakeoverAfterDelay()
            }
            // 有界等待终态（干跑场景上限 60s；Terminal 由 AgentLoopResult 投影）。
            val terminal = withTimeoutOrNull(60_000) {
                AgentRuntime.state.first { it is AgentRuntime.SessionState.Terminal }
            } as? AgentRuntime.SessionState.Terminal
            val closeSummary = AgentRuntime.closeSession() ?: "{}"
            val (closeOk, shutdown, _) = LoopEventParser.parseCloseSummary(closeSummary)
            if (terminal == null) {
                android.util.Log.i(
                    "miracle/verify",
                    "dryrun scenario=$scenario outcome=Timeout ok=false",
                )
                _dryRun.value = DryRunState.Done(scenario, "Timeout", false, "60s 内未观察到终态")
                return@launch
            }
            val tapExpectation = if (scenario == "complete" || scenario == "r3") 2 else 0
            val ok = when (scenario) {
                "complete", "r3" ->
                    terminal.ok && _tapCount.value >= tapExpectation && closeOk
                "max_steps" -> terminal.outcome == "MaxSteps" && closeOk
                "cancel", "takeover" -> terminal.outcome == "Cancelled" && closeOk
                else -> false
            }
            val detail = buildString {
                append("终态 ${terminal.outcome}")
                append(" · 靶点 ${_tapCount.value} 次")
                append(" · 关闭 $shutdown")
                terminal.summary.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }
            android.util.Log.i(
                "miracle/verify",
                "dryrun scenario=$scenario outcome=${terminal.outcome} ok=$ok detail=$detail",
            )
            _dryRun.value = DryRunState.Done(scenario, terminal.outcome, ok, detail)
        }
    }

    private fun launchCancelAfterDelay(): Job = viewModelScope.launch {
        delay(1_500)
        AgentRuntime.cancelSession()
    }

    private fun launchTakeoverAfterDelay(): Job = viewModelScope.launch {
        delay(1_500)
        AgentRuntime.takeover()
    }

    private fun tapDecision(): JSONObject = JSONObject()
        .put("action", "tap")
        .put("x", tapTarget.first)
        .put("y", tapTarget.second)
        .put("reason", "script")

    private fun doneDecision(): JSONObject = JSONObject()
        .put("action", "done")
        .put("reason", "script complete")
}
