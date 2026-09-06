package dev.linductor.miracle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.runtime.EnvSelfTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P1 截屏自检页状态（UDF）。
 *
 * 触发链：Activity 直接消费授权结果并启动宿主服务（consent 一次性，不能等待）；
 * 自检由"服务进入 Bound"事件驱动，与 Activity/ViewModel 重建解耦。状态机收敛在
 * 进程级 [CaptureSelfTestCoordinator]（本 VM 仅作薄委托），任意实例投影同一状态。
 */
sealed interface CaptureState {
    data object Idle : CaptureState
    data object Requesting : CaptureState
    data object Running : CaptureState
    data class Done(val result: EnvSelfTestResult) : CaptureState
    data class Failed(val stage: String, val message: String) : CaptureState
    data object PermissionDenied : CaptureState
}

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator: CaptureSelfTestCoordinator = defaultCoordinator()

    val state: StateFlow<CaptureState> = coordinator.state

    val frames = HostBridge.frames

    init {
        // 服务已绑定（例如 Activity 重建后）也能呈现/执行自检。
        if (AgentForegroundService.state.value == AgentForegroundService.HostState.Bound) {
            coordinator.onServiceBound()
        }
    }

    fun begin() = coordinator.begin()

    /** 授权被拒绝。 */
    fun markDenied() = coordinator.markDenied()

    /** 授权结果已由 Activity 直接消费（服务已启动）。 */
    fun markConsumed() = coordinator.markConsumed()

    private fun defaultCoordinator(): CaptureSelfTestCoordinator =
        Companion.coordinator ?: synchronized(Companion) {
            Companion.coordinator ?: CaptureSelfTestCoordinator(
                // 协调器自有单例作用域：任务有限（单在途 + 折叠重跑），生命周期为
                // 进程级，与所投影状态的进程级语义一致。
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                awaitServiceBound = { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        AgentForegroundService.state.first {
                            it == AgentForegroundService.HostState.Bound
                        }
                    } != null
                },
                environmentSelfTest = {
                    if (!HostBridge.ensureNative()) {
                        NATIVE_UNAVAILABLE_PAYLOAD
                    } else {
                        HostBridge.environmentSelfTest()
                    }
                },
                boundFailureMessage = { AgentForegroundService.stateMessage.value },
            ).also { Companion.coordinator = it }
        }

    private companion object {
        @Volatile
        private var coordinator: CaptureSelfTestCoordinator? = null

        private const val NATIVE_UNAVAILABLE_PAYLOAD =
            """{"ok":false,"stage":"native","error":"native 库不可用"}"""
    }
}
