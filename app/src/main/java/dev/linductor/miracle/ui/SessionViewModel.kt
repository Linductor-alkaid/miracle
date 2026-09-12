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

    /** 指令注入结果（一次性消费；null 值＝成功或无待显示项）。 */
    private val _instructionError = MutableStateFlow<String?>(null)
    val instructionError: StateFlow<String?> = _instructionError.asStateFlow()

    /** 会话对话投影（DEC-016 只读；终态/注入后刷新）。 */
    private val _conversation =
        MutableStateFlow<List<dev.linductor.miracle.runtime.LoopEventParser.ConversationEntry>>(emptyList())
    val conversation = _conversation.asStateFlow()

    init {
        // 终态后刷新会话投影（LoopSettled 已入存储）。
        viewModelScope.launch {
            AgentRuntime.state.collect { state ->
                if (state is AgentRuntime.SessionState.Terminal) {
                    refreshConversation()
                }
            }
        }
    }

    fun refreshConversation() {
        _conversation.value = AgentRuntime.conversation()
    }

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

    /** 运行中介入指令（DEC-016；阻塞 JNI 在 Default 协程）。 */
    fun sendInstruction(text: String) {
        if (text.isBlank()) {
            _instructionError.value = "请输入指令内容"
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _instructionError.value = AgentRuntime.sendUserInstruction(text)
        }
    }

    fun clearInstructionError() {
        _instructionError.value = null
    }

    fun clearStartError() {
        _startError.value = null
    }
}
