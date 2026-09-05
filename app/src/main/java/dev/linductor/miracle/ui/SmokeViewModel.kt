package dev.linductor.miracle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.runtime.NativeBridge
import dev.linductor.miracle.runtime.SmokeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * P0 自检页状态（UDF：UI 事件 -> 意图 -> 状态流）。native 调用不阻塞主线程。
 */
sealed interface SmokeUiState {
    data object Idle : SmokeUiState
    data object Running : SmokeUiState
    data class Done(val result: SmokeResult) : SmokeUiState
    data class NativeUnavailable(val message: String) : SmokeUiState
}

class SmokeViewModel : ViewModel() {

    private val _state = MutableStateFlow<SmokeUiState>(SmokeUiState.Idle)
    val state: StateFlow<SmokeUiState> = _state.asStateFlow()

    fun runSelfTest() {
        if (_state.value is SmokeUiState.Running) {
            return
        }
        if (NativeBridge.availability() == NativeBridge.Availability.Unavailable) {
            _state.value = SmokeUiState.NativeUnavailable(
                "libmiracle_host.so 加载失败：设备 ABI 或安装异常（产品 ABI：arm64-v8a）"
            )
            return
        }
        _state.value = SmokeUiState.Running
        viewModelScope.launch(Dispatchers.Default) {
            val result = SmokeResult.parse(NativeBridge.selfTestJson())
            _state.value = SmokeUiState.Done(result)
        }
    }
}
