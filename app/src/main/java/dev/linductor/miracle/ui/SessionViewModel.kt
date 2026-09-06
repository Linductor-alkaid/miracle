package dev.linductor.miracle.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.consent.SessionGate
import dev.linductor.miracle.runtime.AgentRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务台 ViewModel（UDF：意图 → AgentRuntime 门面 → 状态流 → UI 重组）。
 * 会话状态/时间线/确认请求均为 AgentRuntime 投影的只读视图。
 */
class SessionViewModel : ViewModel() {

    val sessionState: StateFlow<AgentRuntime.SessionState> = AgentRuntime.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentRuntime.SessionState.Idle)

    val timeline = AgentRuntime.timeline
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val confirmation = AgentRuntime.confirmation
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _gate = MutableStateFlow<SessionGate.GateStatus?>(null)
    val gate: StateFlow<SessionGate.GateStatus?> = _gate.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    /** 每次回到前台/授权返回后刷新准入状态。 */
    fun refreshGate(context: Context) {
        viewModelScope.launch {
            val status = withContext(Dispatchers.Default) {
                val store = dev.linductor.miracle.settings.ModelConfigStore(context.applicationContext)
                SessionGate.check(context, store.load().complete && store.loadApiKey() != null)
            }
            _gate.value = status
        }
    }

    /** 提交目标（会话开启 + 任务提交；阻塞 JNI 在 Default 协程）。 */
    fun startGoal(context: Context, goal: String) {
        if (goal.isBlank()) {
            _startError.value = "请输入任务目标"
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.Default) {
                AgentRuntime.startSession(context, goal.trim())
            }
            _startError.value = error
        }
    }

    fun cancelSession() = AgentRuntime.cancelSession()

    fun takeover() = AgentRuntime.takeover()

    fun closeSession() {
        viewModelScope.launch(Dispatchers.Default) {
            AgentRuntime.closeSession()
        }
    }

    fun resolveConfirmation(approve: Boolean) {
        val request = confirmation.value ?: return
        AgentRuntime.resolveConfirmation(request, approve)
    }

    fun clearStartError() {
        _startError.value = null
    }
}
