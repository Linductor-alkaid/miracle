package dev.linductor.miracle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.host.HostAbiInput
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.host.MiracleAccessibilityService
import dev.linductor.miracle.runtime.InputSelfTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P2 输入自检页状态（UDF）。
 *
 * 双轨自检（P2 计划决策 10）：
 *  1. 直接 ABI 契约探针（独立 host：非法参数/过期 deadline/RELEASE_ALL/长按中途
 *     取消/取消后复验 tap）；
 *  2. 经 mira adapter 会话（tap/long_press/swipe/back/tap/type/home），对自身 UI
 *     靶点与文本框断言副作用；home 后经无障碍窗口事件断言 launcher 前台。
 *
 * UI 副作用计数（tap/长按/back/文本）由本 VM 持有，Compose 靶点只调用记录入口；
 * 坐标由 Compose 布局回调写入（[UiGeometry]）。
 */
sealed interface InputState {
    data object Idle : InputState
    data object Running : InputState
    data class Done(
        val ok: Boolean,
        val probe: InputSelfTestResult,
        val adapterSteps: List<InputSelfTestResult.AdapterStep>,
        val close: InputSelfTestResult.CloseStats?,
        val uiChecks: UiChecks,
        val launcherSeen: Boolean,
    ) : InputState

    data class Failed(val stage: String, val message: String) : InputState
}

/** UI 副作用断言结果。 */
data class UiChecks(
    val probeTapCounted: Boolean,
    val tapCounted: Boolean,
    val longPressCounted: Boolean,
    val backCounted: Boolean,
    val textMatched: Boolean,
)

/** 靶点/文本框/滑动区的规范坐标（Compose 布局回调写入）。 */
data class UiGeometry(
    val tapX: Double = 0.5,
    val tapY: Double = 0.5,
    val typeX: Double = 0.5,
    val typeY: Double = 0.6,
    val swipeX1: Double = 0.5,
    val swipeY1: Double = 0.7,
    val swipeX2: Double = 0.5,
    val swipeY2: Double = 0.5,
)

class InputViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TYPE_TEXT = "miracle"
    }

    private val _state = MutableStateFlow<InputState>(InputState.Idle)
    val state: StateFlow<InputState> = _state.asStateFlow()

    val accessibilityConnected = MiracleAccessibilityService.connected

    /** UI 副作用计数（自检读取；用户手动交互也计入）。 */
    val tapCount = MutableStateFlow(0)
    val longPressCount = MutableStateFlow(0)
    val backCount = MutableStateFlow(0)
    val typedValue = MutableStateFlow("")

    @Volatile
    var geometry: UiGeometry = UiGeometry()
        private set

    /**
     * 文本框确定性聚焦辅助（仅自检使用；由 InputCard 经 FocusRequester 注册，
     * 主线程调用）。生产输入语义不变：type 仍按"焦点节点 SET_TEXT、无焦点
     * fail-closed"执行——该辅助只保证被测链路（ABI→InputDispatcher→SET_TEXT）
     * 的焦点前置条件成立，与 tap 落点精度解耦。
     */
    @Volatile
    var requestFieldFocus: (() -> Unit)? = null

    private var running = false

    fun recordTap() {
        tapCount.value += 1
    }

    fun recordLongPress() {
        longPressCount.value += 1
    }

    fun recordBack() {
        backCount.value += 1
    }

    fun onTyped(value: String) {
        typedValue.value = value
    }

    fun updateGeometry(value: UiGeometry) {
        geometry = value
    }

    /** 靶点中心（规范坐标；Compose 布局回调写入）。 */
    fun updateTapCenter(x: Double, y: Double) {
        geometry = geometry.copy(tapX = x, tapY = y)
    }

    /** 滑动路径（规范坐标：区内下半 → 上半）。 */
    fun updateSwipeArea(x: Double, y1: Double, y2: Double) {
        geometry = geometry.copy(swipeX1 = x, swipeY1 = y1, swipeX2 = x, swipeY2 = y2)
    }

    /** 文本框中心（type 步骤先 tap 聚焦）。 */
    fun updateTypeCenter(x: Double, y: Double) {
        geometry = geometry.copy(typeX = x, typeY = y)
    }

    fun runSelfTest() {
        if (running) {
            return
        }
        if (!MiracleAccessibilityService.isConnected()) {
            _state.value = InputState.Failed(
                "accessibility",
                "无障碍服务未启用（PermissionDenied，fail-closed）：请在系统设置中开启 Miracle 输入服务后重试",
            )
            return
        }
        running = true
        _state.value = InputState.Running
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!HostBridge.ensureNative()) {
                    _state.value = InputState.Failed("native", "libmiracle_host.so 不可用（加载失败）")
                    return@launch
                }
                runSelfTestInternal()
            } catch (error: Throwable) {
                _state.value = InputState.Failed("exception", error.message ?: "未知异常")
            } finally {
                running = false
            }
        }
    }

    private suspend fun runSelfTestInternal() {
        // 基线计数（探针与 adapter 步骤的增量以此为参照）。
        val tapBefore = tapCount.value
        val longBefore = longPressCount.value
        val backBefore = backCount.value

        // Running 态会替换 Idle 提示文本（3 行 → 1 行），卡片布局随之位移；
        // 等待重组稳定后再读取坐标，且每步派发前重读（几何由 onGloballyPositioned
        // 持续更新）。
        delay(300)

        // 1. 直接 ABI 契约探针。
        val probeJson = HostBridge.inputContractProbe(geometry.tapX, geometry.tapY)
        val probe = InputSelfTestResult.parseProbe(probeJson)
        if (!probe.ok) {
            _state.value = InputState.Failed(
                probe.stage.ifEmpty { "probe" },
                probe.error.ifEmpty { probe.probeSteps.joinToString("; ") { "${it.name}=${it.detail}" } },
            )
            return
        }
        val tapAfterProbe = tapCount.value

        // 2. adapter 会话（tap/long_press/swipe/back/tap(field)/type/home）。
        val open = HostBridge.inputTestOpen()
        if (open != 1) {
            _state.value = InputState.Failed("session_open", "inputTestOpen=$open（host 冲突或运行时未就绪）")
            return
        }
        val steps = mutableListOf<InputSelfTestResult.AdapterStep>()
        var launcherSeen = false
        try {
            val sequence = listOf(
                Step(HostAbiInput.KIND_TAP) { arrayOf(it.tapX, it.tapY, 0.0, 0.0) },
                Step(HostAbiInput.KIND_LONG_PRESS) { arrayOf(it.tapX, it.tapY, 0.0, 0.0) },
                Step(HostAbiInput.KIND_SWIPE) {
                    arrayOf(it.swipeX1, it.swipeY1, it.swipeX2, it.swipeY2)
                },
                Step(HostAbiInput.KIND_BACK) { arrayOf(0.0, 0.0, 0.0, 0.0) },
                Step(HostAbiInput.KIND_TAP) { arrayOf(it.typeX, it.typeY, 0.0, 0.0) },
                Step(HostAbiInput.KIND_TYPE, text = TYPE_TEXT) { arrayOf(0.0, 0.0, 0.0, 0.0) },
                Step(HostAbiInput.KIND_HOME) { arrayOf(0.0, 0.0, 0.0, 0.0) },
            )
            for (step in sequence) {
                // 每步重读几何：IME 弹出/重组导致的位移即时生效。
                val coords = step.resolve(geometry)
                if (step.kind == HostAbiInput.KIND_TYPE) {
                    // 确定性聚焦辅助：tap(field) 的落点受滚动/布局影响，type 的
                    // 被测对象是焦点节点 SET_TEXT 链路——焦点前置条件由此保证。
                    withContext(Dispatchers.Main) {
                        try {
                            requestFieldFocus?.invoke()
                        } catch (error: IllegalStateException) {
                            android.util.Log.w(
                                "miracle/input",
                                "field focus assist unavailable: ${error.message}",
                            )
                        }
                    }
                    delay(150) // 等待焦点生效（主线程串行后通常即时）。
                }
                val json = HostBridge.inputTestDispatch(
                    step.kind, coords[0], coords[1], coords[2], coords[3],
                    step.text, 0, 10_000,
                )
                val parsed = InputSelfTestResult.parseStep(json)
                steps.add(parsed)
                if (!parsed.ok) {
                    break
                }
                if (step.kind == HostAbiInput.KIND_HOME) {
                    launcherSeen = awaitLauncher(2_000)
                }
            }
        } finally {
            val closeJson = HostBridge.inputTestClose()
            val close = InputSelfTestResult.parseClose(closeJson)

            val checks = UiChecks(
                probeTapCounted = tapAfterProbe > tapBefore,
                tapCounted = tapCount.value > tapAfterProbe,
                longPressCounted = longPressCount.value > longBefore,
                backCounted = backCount.value > backBefore,
                textMatched = typedValue.value == TYPE_TEXT,
            )
            val allStepsOk = steps.isNotEmpty() && steps.all { it.ok }
            val violationsClean = close.duplicates == 0L && close.unknowns == 0L &&
                close.late == 0L && close.violations == 0L &&
                close.hostUnknownCompletions == 0L && close.hostLateCompletions == 0L
            // launcher 前台为观察项：home 回执以派发结果为准；厂商对无障碍源
            // HOME 的导航拦截记录于兼容性证据（PJE110 实测：派发成功不导航，
            // 前台会话经 intent 兜底可达）。
            val ok = allStepsOk && checks.probeTapCounted && checks.tapCounted &&
                checks.longPressCounted && checks.backCounted && checks.textMatched &&
                violationsClean
            _state.value = InputState.Done(ok, probe, steps, close, checks, launcherSeen)
        }
    }

    private suspend fun awaitLauncher(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (MiracleAccessibilityService.lastForegroundPackage
                    ?.contains("launcher", ignoreCase = true) != true
            ) {
                delay(100)
            }
            true
        } ?: false

    private data class Step(
        val kind: Int,
        val text: String = "",
        val resolve: (UiGeometry) -> Array<Double>,
    )
}
