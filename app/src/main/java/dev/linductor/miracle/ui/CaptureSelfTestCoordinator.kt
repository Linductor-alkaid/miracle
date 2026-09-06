package dev.linductor.miracle.ui

import dev.linductor.miracle.runtime.EnvSelfTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * P1 环境自检协调器（进程级单例语义）。
 *
 * 自检由"服务进入 Bound"事件驱动、与 Activity/ViewModel 生命周期解耦；状态与在途
 * 标志收敛在协调器内，任意 VM 实例（Activity 重建、进程存活期内的再次授权）投影
 * 同一条状态流。此前 `_state` 按实例私有而启动守卫按进程单次，"再次自检"与重建
 * 实例都会停在 Running 且无人完成（2026-09-06 整屏授权报告的根因，见 P1 计划）。
 *
 * 并发纪律：同一时刻至多一个 native 自检在途（[inFlight]）；在途期间到达的再次
 * 授权折叠为一次重跑（[rerunRequested]），任务有限、终态必达。
 */
class CaptureSelfTestCoordinator(
    private val scope: CoroutineScope,
    private val awaitServiceBound: suspend (timeoutMs: Long) -> Boolean,
    private val environmentSelfTest: () -> String,
    private val boundFailureMessage: () -> String = { "" },
) {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile
    private var inFlight = false

    @Volatile
    private var rerunRequested = false

    /** 用户发起授权（自 Requesting 起至 Running 期间不可重复进入）。 */
    fun begin() {
        val current = _state.value
        if (current !is CaptureState.Requesting && current !is CaptureState.Running) {
            _state.value = CaptureState.Requesting
        }
    }

    /** 授权被拒绝。 */
    fun markDenied() {
        if (_state.value is CaptureState.Requesting) {
            _state.value = CaptureState.PermissionDenied
        }
    }

    /** 授权结果已消费（服务已启动）：请求一次全新自检，即使此前已有终态。 */
    fun markConsumed() {
        if (_state.value is CaptureState.Requesting) {
            _state.value = CaptureState.Running
        }
        requestRun(force = true)
    }

    /** 服务已 Bound（VM init 探测）：仅从未执行过时补跑，重建重放不重复触发。 */
    fun onServiceBound() {
        if (_state.value is CaptureState.Idle) {
            requestRun(force = false)
        }
    }

    private fun requestRun(force: Boolean) {
        if (inFlight) {
            if (force) {
                rerunRequested = true
            }
            return
        }
        startRun()
    }

    private fun startRun() {
        if (!scope.isActive) {
            return
        }
        inFlight = true
        _state.value = CaptureState.Running
        scope.launch {
            try {
                val bound = awaitServiceBound(BOUND_TIMEOUT_MS)
                if (!bound) {
                    _state.value = CaptureState.Failed(
                        "service",
                        boundFailureMessage().ifEmpty { "宿主服务未就绪" },
                    )
                    return@launch
                }
                val parsed = EnvSelfTestResult.parse(environmentSelfTest())
                _state.value = if (parsed.ok) {
                    CaptureState.Done(parsed)
                } else {
                    CaptureState.Failed(
                        parsed.stage.ifEmpty { "observe" },
                        parsed.error.ifEmpty { "未知失败" },
                    )
                }
            } catch (error: Throwable) {
                _state.value = CaptureState.Failed("native", error.message ?: "native 自检异常")
            } finally {
                inFlight = false
                val rerun = rerunRequested && scope.isActive
                rerunRequested = false
                if (rerun) {
                    startRun()
                }
            }
        }
    }

    private companion object {
        const val BOUND_TIMEOUT_MS = 15_000L
    }
}
