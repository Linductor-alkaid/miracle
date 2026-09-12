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
     * takeover：悬浮球长按驱动（真机取证脚本），本卡无按钮；
     * ⑤ user_message（P3h-03）：运行中注入指令（断言请求可见）+ 突发 8 连发验证
     *    队列满拒绝（max_pending=1）+ 会话投影含 UserMessageInjected + 关闭后
     *    注入明确拒绝——四路径取证；
     * ⑥ tool（P3h-05）：脚本注入 wait 工具调用，断言 ToolExecuted 计数、下一轮
     *    请求回填（mira.agent-loop.tool-result.v1 标记）与终态 Completed；
     * ⑦ tool_budget：max_tool_executions=1 + 两次 wait 调用，断言预算耗尽终态
     *    Failed。
     */
    fun runDryRun(context: Context, scenario: String) {
        if (_dryRun.value is DryRunState.Running || AgentRuntime.sessionOpen) {
            return
        }
        resetTapCount()
        _dryRun.value = DryRunState.Running(scenario)
        viewModelScope.launch(Dispatchers.Default) {
            val goal: String
            val maxSteps: Int
            val decisions: List<JSONObject>
            when (scenario) {
                "complete", "r3" -> {
                    val tap = tapDecision()
                    goal = if (scenario == "r3") "发送测试消息" else "点击靶点两次"
                    maxSteps = 8
                    decisions = listOf(tap, tap, doneDecision())
                }

                "max_steps" -> {
                    goal = "不断点击靶点"
                    maxSteps = 2
                    decisions = List(4) { tapDecision() }
                }

                "cancel", "takeover" -> {
                    goal = "点击靶点"
                    maxSteps = 8
                    decisions = List(4) { tapDecision() }
                }

                "user_message" -> {
                    goal = "点击靶点后完成"
                    maxSteps = 8
                    decisions = List(5) { tapDecision() } + doneDecision()
                }

                "tool" -> {
                    goal = "等待片刻后完成"
                    maxSteps = 8
                    decisions = listOf(waitToolCall(600), doneDecision())
                }

                "tool_budget" -> {
                    goal = "连续等待后完成"
                    maxSteps = 8
                    decisions = listOf(waitToolCall(200), waitToolCall(200), doneDecision())
                }

                else -> {
                    _dryRun.value = DryRunState.Done(scenario, "Unknown", false, "未知场景")
                    return@launch
                }
            }
            val config = JSONObject()
                .put("transport", "scripted")
                .put("max_steps", maxSteps)
                .put("script", JSONArray(decisions))
            when (scenario) {
                // 突发注入路径需要确定性队列上限（默认 16 在单步窗口内难打满）。
                "user_message" -> config.put("max_pending_user_messages", 1)
                "tool_budget" -> config.put("max_tool_executions", 1)
            }
            val error = AgentRuntime.startSession(context, goal, script = config.toString())
            if (error != null) {
                android.util.Log.i(
                    "miracle/verify",
                    "dryrun scenario=$scenario outcome=OpenFailed ok=false detail=$error",
                )
                _dryRun.value = DryRunState.Done(scenario, "OpenFailed", false, error)
                return@launch
            }
            // 结果事件采集（transport 断言投影在结果 JSON 内）。
            var resultEvent: LoopEventParser.LoopEvent.LoopResultEvent? = null
            val collector = viewModelScope.launch {
                AgentRuntime.events.collect { event ->
                    if (event is LoopEventParser.LoopEvent.LoopResultEvent) {
                        resultEvent = event
                    }
                }
            }
            // 注入序列结果（main 协程写、Default 协程断言读：原子引用保证可见性）。
            val injectionRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val burstAcceptedRef = java.util.concurrent.atomic.AtomicInteger(0)
            val burstRejectedRef = java.util.concurrent.atomic.AtomicInteger(0)
            if (scenario == "user_message") {
                launchUserMessageSequence(
                    onFirst = { injectionRef.set(it) },
                    onBurst = { accepted, queueFull ->
                        burstAcceptedRef.set(accepted)
                        burstRejectedRef.set(queueFull)
                    },
                )
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
            val conversation =
                if (scenario == "user_message") AgentRuntime.conversation() else emptyList()
            val closeSummary = AgentRuntime.closeSession() ?: "{}"
            collector.cancel()
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
            val result = resultEvent?.result
            val ok: Boolean
            val detail = StringBuilder()
                .append("终态 ${terminal.outcome}")
                .append(" · 靶点 ${_tapCount.value} 次")
                .append(" · 关闭 $shutdown")
                .also { builder ->
                    terminal.summary.takeIf { it.isNotBlank() }
                        ?.let { builder.append(" · $it") }
                }
            when (scenario) {
                "complete", "r3" ->
                    ok = terminal.ok && _tapCount.value >= tapExpectation && closeOk

                "max_steps" -> ok = terminal.outcome == "MaxSteps" && closeOk
                "cancel", "takeover" -> ok = terminal.outcome == "Cancelled" && closeOk

                "user_message" -> {
                    // 四路径：正常注入（请求可见 + 事件）+ 队列满拒绝 + 关闭后拒绝。
                    val injection = injectionRef.get()
                    val burstAccepted = burstAcceptedRef.get()
                    val burstRejectedQueueFull = burstRejectedRef.get()
                    val injectedEvent = conversation.any {
                        it.kind == "user_message" && it.text.contains("靶点")
                    }
                    val sawFollowup = result?.transport?.sawFollowup == true
                    val postClose = AgentRuntime.sendUserInstruction("关闭后再注入")
                    ok = terminal.ok && injection == null && sawFollowup &&
                        injectedEvent && burstRejectedQueueFull >= 1 && postClose != null &&
                        closeOk
                    detail.append(" · 注入${if (injection == null) "✓" else "✗($injection)"}")
                    detail.append(" · 请求可见${if (sawFollowup) "✓" else "✗"}")
                    detail.append(
                        " · 队列满拒绝 $burstRejectedQueueFull 次" +
                            "（突发 ${burstAccepted + burstRejectedQueueFull}）",
                    )
                    detail.append(" · 关闭后拒绝${if (postClose != null) "✓" else "✗"}")
                }

                "tool" -> {
                    val toolStep = result?.steps?.any {
                        it.summary.contains("tool:wait") || it.note.contains("executed 1 tool call")
                    } == true
                    val sawToolResult = result?.transport?.sawToolResult == true
                    val toolEvents = result?.toolEvents ?: 0L
                    ok = terminal.ok && toolStep && sawToolResult && toolEvents >= 1 && closeOk
                    detail.append(" · 工具步${if (toolStep) "✓" else "✗"}")
                    detail.append(" · 回填可见${if (sawToolResult) "✓" else "✗"}")
                    detail.append(" · ToolExecuted $toolEvents 次")
                }

                "tool_budget" -> {
                    val exhausted = terminal.summary.contains("tool execution budget exhausted") ||
                        (terminal.outcome == "Failed" &&
                            result?.summary?.contains("budget exhausted") == true)
                    val toolEvents = result?.toolEvents ?: 0L
                    ok = !terminal.ok && exhausted && toolEvents == 1L && closeOk
                    detail.append(" · 预算耗尽${if (exhausted) "✓" else "✗"}")
                    detail.append(" · ToolExecuted $toolEvents 次（预期 1）")
                }

                else -> ok = false
            }
            val detailText = detail.toString()
            android.util.Log.i(
                "miracle/verify",
                "dryrun scenario=$scenario outcome=${terminal.outcome} ok=$ok detail=$detailText",
            )
            _dryRun.value = DryRunState.Done(scenario, terminal.outcome, ok, detailText)
        }
    }

    /**
     * user_message 场景的注入序列（P3h-03 四路径驱动）：
     * 800ms 后注入一条指令（记录首条结果）；随后突发 8 条（max_pending=1 下
     * 确定性触发队列满拒绝；步边界 drain 不影响"至少一条拒绝"断言）。
     */
    private fun launchUserMessageSequence(
        onFirst: (String?) -> Unit,
        onBurst: (accepted: Int, queueFull: Int) -> Unit,
    ): Job = viewModelScope.launch {
        delay(800)
        onFirst(AgentRuntime.sendUserInstruction("继续以靶点为动作对象"))
        var accepted = 0
        var queueFull = 0
        repeat(8) {
            val rejection = AgentRuntime.sendUserInstruction("突发指令 $it")
            if (rejection == null) {
                accepted += 1
            } else if (rejection.contains("queue is full") ||
                rejection.contains("队列已满")
            ) {
                queueFull += 1
            }
        }
        onBurst(accepted, queueFull)
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

    /** wait 工具调用脚本条目（DEC-015；ScriptedTransport 按该形态生成 tool_calls wire）。 */
    private fun waitToolCall(durationMs: Int): JSONObject = JSONObject()
        .put(
            "tool_call",
            JSONObject()
                .put("name", "wait")
                .put("arguments", JSONObject().put("duration_ms", durationMs)),
        )
}
