package dev.linductor.miracle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.runtime.EnvSelfTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P1 截屏自检页状态（UDF）。
 *
 * 触发链：Activity 直接消费授权结果并启动宿主服务（consent 一次性，不能等待）；
 * 自检由"服务进入 Bound"事件驱动，与 Activity/ViewModel 重建解耦——进程内只执行
 * 一次（[selfTestLaunched]），重建后的 VM 订阅同一状态流呈现结果。
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

    companion object {
        @Volatile
        private var selfTestLaunched = false
    }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    val frames = HostBridge.frames

    init {
        // 服务已绑定（例如 Activity 重建后）也能呈现/执行自检。
        if (AgentForegroundService.state.value == AgentForegroundService.HostState.Bound) {
            maybeLaunchSelfTest()
        }
    }

    fun begin() {
        if (_state.value is CaptureState.Requesting || _state.value is CaptureState.Running) {
            return
        }
        _state.value = CaptureState.Requesting
    }

    /** 授权被拒绝。 */
    fun markDenied() {
        if (_state.value is CaptureState.Requesting) {
            _state.value = CaptureState.PermissionDenied
        }
    }

    /** 授权结果已由 Activity 直接消费（服务已启动）。 */
    fun markConsumed() {
        if (_state.value is CaptureState.Requesting) {
            _state.value = CaptureState.Running
        }
        maybeLaunchSelfTest()
    }

    private fun maybeLaunchSelfTest() {
        if (selfTestLaunched) {
            if (_state.value is CaptureState.Idle) {
                _state.value = CaptureState.Running
            }
            return
        }
        selfTestLaunched = true
        _state.value = CaptureState.Running
        viewModelScope.launch(Dispatchers.Default) {
            val bound = withTimeoutOrNull(15_000) {
                AgentForegroundService.state.first { it == AgentForegroundService.HostState.Bound }
            }
            if (bound == null) {
                _state.value = CaptureState.Failed(
                    "service",
                    AgentForegroundService.stateMessage.value.ifEmpty { "宿主服务未就绪" },
                )
                return@launch
            }
            val payload = HostBridge.environmentSelfTest()
            val parsed = EnvSelfTestResult.parse(payload)
            _state.value = if (parsed.ok) {
                CaptureState.Done(parsed)
            } else {
                CaptureState.Failed(
                    parsed.stage.ifEmpty { "observe" },
                    parsed.error.ifEmpty { "未知失败" },
                )
            }
        }
    }
}
