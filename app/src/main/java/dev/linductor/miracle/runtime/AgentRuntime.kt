package dev.linductor.miracle.runtime

import android.util.Log
import dev.linductor.miracle.consent.RiskPolicy
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.host.HttpTransportBinding
import dev.linductor.miracle.host.MiracleAccessibilityService
import dev.linductor.miracle.settings.ModelConfig
import dev.linductor.miracle.settings.ModelConfigStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时门面（架构 §2/RULE-03）：UI 与悬浮球只经本对象访问闭环运行时。
 *
 * 职责：会话生命周期（open/submit/cancel/takeover/close）、状态投影
 * （SessionState/时间线/确认请求流）、R3 策略查询（native consent gate 回调）、
 * 模型传输绑定（内存凭据）。native 上投经 [HostBridge.onLoopEvent] 进入
 * [handleNativeEvent]；本对象不直接触碰 JNI 细节以外的任何 mira 类型。
 *
 * 状态模型：mira AgentLoop 终态是唯一事实源；相位为宿主信号驱动的粗投影
 * （P3 计划决策 4），UI 不自造第二状态机。
 */
object AgentRuntime {

    sealed interface SessionState {
        data object Idle : SessionState

        data class Running(
            val goal: String,
            val phase: LoopEventParser.Phase,
            val stepEvents: Int,
            val takeover: Boolean,
        ) : SessionState

        data class Terminal(
            val goal: String,
            val outcome: String,
            val summary: String,
            val ok: Boolean,
        ) : SessionState
    }

    data class TimelineEntry(val atMs: Long, val text: String)

    private const val TAG = "miracle/runtime"

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _confirmation =
        MutableStateFlow<LoopEventParser.ConfirmationData?>(null)
    val confirmation: StateFlow<LoopEventParser.ConfirmationData?> = _confirmation.asStateFlow()

    private val _timeline = MutableStateFlow<List<TimelineEntry>>(emptyList())
    val timeline: StateFlow<List<TimelineEntry>> = _timeline.asStateFlow()

    private val _events = MutableSharedFlow<LoopEventParser.LoopEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LoopEventParser.LoopEvent> = _events.asSharedFlow()

    private val transport = HttpTransportBinding()

    @Volatile
    private var currentGoal: String = ""

    @Volatile
    private var lastStepCount = 0

    @Volatile
    private var configStore: ModelConfigStore? = null

    fun attach(context: android.content.Context) {
        if (configStore == null) {
            configStore = ModelConfigStore(context.applicationContext)
        }
    }

    /** 绑定传输凭据（连通性自检与真实传输会话前置）。空＝成功，非空＝失败原因。 */
    fun ensureTransport(context: android.content.Context): String? {
        val store = configStore ?: ModelConfigStore(context.applicationContext).also {
            configStore = it
        }
        val apiKey = store.loadApiKey() ?: return "API key 未配置"
        if (!store.load().httpsValid) {
            return "端点必须是 https://"
        }
        transport.bind(apiKey)
        return null
    }

    /** native 传输入口（HostBridge 转发）。 */
    fun httpExchangeStart(exchangeId: Long, requestJson: String, body: ByteArray): Int =
        transport.start(exchangeId, requestJson, body)

    fun httpExchangeCancel(exchangeId: Long) = transport.cancel(exchangeId)

    /** 模型连通性自检（阻塞 JNI：网络往返；调用方在 IO/Default 协程）。 */
    fun modelConnectivity(context: android.content.Context): String {
        val store = configStore ?: ModelConfigStore(context.applicationContext).also {
            configStore = it
        }
        ensureTransport(context)?.let { error ->
            return "{\"ok\":false,\"stage\":\"config\",\"error\":\"$error\"}"
        }
        val config = store.load()
        val apiKey = store.loadApiKey() ?: return "{\"ok\":false,\"stage\":\"config\"}"
        return HostBridge.modelConnectivityTest(config.toNativeJson(apiKey))
    }

    /** native 会话是否已打开（loopOpen 后、loopClose 前）。 */
    @Volatile
    var sessionOpen: Boolean = false
        private set

    // ---- 会话生命周期（native 调用为阻塞 JNI：调用方保证非主线程） ----

    /**
     * 打开并提交任务。前置：SessionGate 满足（调用方校验）。
     * @return 空＝成功；非空＝失败原因（UI 呈现）。
     */
    fun startSession(context: android.content.Context, goal: String, script: String? = null): String? {
        val store = configStore ?: ModelConfigStore(context.applicationContext).also {
            configStore = it
        }
        val config = store.load()
        val apiKey = store.loadApiKey()
        if (script == null && (!config.complete || apiKey == null)) {
            return "模型配置不完整（端点/密钥/模型）"
        }
        if (sessionOpen) {
            return "会话已在进行中"
        }
        if (!HostBridge.ensureNative()) {
            return "native 库不可用"
        }
        if (script == null) {
            transport.bind(apiKey ?: return "模型密钥不可用")
        }
        val configJson = if (script != null) script else config.toNativeJson(apiKey!!)
        val opened = HostBridge.loopOpen(configJson)
        if (opened != 1) {
            return when (opened) {
                -1 -> "会话已打开"
                -2 -> "宿主被其他自检会话占用（请先关闭）"
                -3 -> "宿主传输未就绪"
                -4 -> "配置非法（端点必须为 https）"
                else -> "loopOpen 失败（$opened）"
            }
        }
        sessionOpen = true
        currentGoal = goal
        lastStepCount = 0
        _timeline.value = emptyList()
        appendTimeline("会话开启：$goal")
        val submitted = HostBridge.loopSubmit(goal, config.maxSteps)
        if (submitted != 1) {
            closeSession(recordResult = false)
            return "任务提交失败（$submitted）"
        }
        _state.value = SessionState.Running(goal, LoopEventParser.Phase.Observing, 0, false)
        return null
    }

    fun cancelSession() {
        if (!sessionOpen) {
            return
        }
        HostBridge.loopCancel()
        appendTimeline("已请求取消")
    }

    fun takeover() {
        if (!sessionOpen) {
            return
        }
        HostBridge.loopTakeover()
        _confirmation.value = null
        val previous = _state.value
        if (previous is SessionState.Running) {
            _state.value = previous.copy(takeover = true)
        }
        appendTimeline("Human Takeover：阻断新动作 + RELEASE_ALL")
    }

    /** 关闭会话（返回 close 统计 JSON 供自检展示）。 */
    fun closeSession(recordResult: Boolean = true): String? {
        if (!sessionOpen) {
            return null
        }
        val summary = HostBridge.loopClose()
        sessionOpen = false
        transport.shutdown()
        if (recordResult && _state.value is SessionState.Running) {
            _state.value = SessionState.Terminal(
                currentGoal, "Closed", "会话已关闭", false,
            )
        }
        appendTimeline("会话关闭")
        return summary
    }

    /** 用户对 R3 确认的响应（对话框/通知 action 调用；native consume 校验）。 */
    fun resolveConfirmation(request: LoopEventParser.ConfirmationData, approve: Boolean): Boolean {
        val outcome = HostBridge.consentResolve(request.challenge, request.nonce, approve)
        _confirmation.value = null
        return outcome == 0 || outcome == 1
    }

    /** 传输就绪（native kotlin_transport_ready 探测）。 */
    fun transportReady(): Boolean = transport.ready

    /** native R3 准入询问（host_abi_impl → HostBridge.consentCheckInput）。 */
    fun consentCheck(eventsJson: String): Int {
        val actions = RiskPolicy.parseActions(eventsJson) ?: return 2 // 非法输入按拒绝
        val foreground = MiracleAccessibilityService.lastForegroundPackage
        return when (
            RiskPolicy.decide(currentGoal, actions, foreground)
        ) {
            RiskPolicy.Decision.Allow -> 0
            RiskPolicy.Decision.RequireConfirmation -> 1
        }
    }

    // ---- native 上投（HostBridge.onLoopEvent 转发；可能来自 executor 线程） ----

    fun handleNativeEvent(wrappedJson: String) {
        val event = LoopEventParser.parse(wrappedJson) ?: return
        _events.tryEmit(event)
        when (event) {
            is LoopEventParser.LoopEvent.PhaseEvent -> {
                val current = _state.value
                if (current is SessionState.Running) {
                    _state.value = current.copy(phase = event.phase)
                }
                if (event.phase == LoopEventParser.Phase.Acting) {
                    lastStepCount += 1
                    val running = _state.value
                    if (running is SessionState.Running) {
                        _state.value = running.copy(stepEvents = lastStepCount)
                    }
                }
            }

            is LoopEventParser.LoopEvent.SessionEvent -> {
                if (event.state == "closed") {
                    sessionOpen = false
                    transport.shutdown()
                }
            }

            is LoopEventParser.LoopEvent.LoopResultEvent -> {
                val result = event.result
                _state.value = SessionState.Terminal(
                    currentGoal, result.outcome, result.summary, result.completed,
                )
                appendTimeline(
                    "终态 ${result.outcome}（步 ${result.stepsCount}/恢复 ${result.recoveries}）",
                )
            }

            is LoopEventParser.LoopEvent.ConfirmationRequestEvent -> {
                _confirmation.value = event.request
                appendTimeline("R3 确认请求：${event.request.summary}")
            }

            is LoopEventParser.LoopEvent.ConfirmationSettledEvent -> {
                if (_confirmation.value?.challenge == event.challenge) {
                    _confirmation.value = null
                }
                appendTimeline("确认 ${event.outcome}")
            }
        }
    }

    private fun appendTimeline(text: String) {
        val entry = TimelineEntry(System.currentTimeMillis(), text)
        _timeline.value = (_timeline.value + entry).takeLast(MAX_TIMELINE)
        Log.i(TAG, text)
    }

    private const val MAX_TIMELINE = 200
}
