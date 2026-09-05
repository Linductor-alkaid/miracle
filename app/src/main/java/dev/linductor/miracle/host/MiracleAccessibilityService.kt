package dev.linductor.miracle.host

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Miracle 无障碍服务（P2 输入链路的系统授权载体）。
 *
 * 职责（P2 计划决策 1）：
 *  - 拥有 [InputDispatcher] 及其主线程结构化作用域；连接/断开时经 [HostBridge]
 *    绑定/解绑并触发 epoch 递增（无障碍重连属 epoch 源，工具集设计 §3）。
 *  - 跟踪最近一次窗口状态变化的包名（AppMonitor 的种子，P2 仅用于 home 验证；
 *    只保留包名与时间戳，不缓冲事件内容）。
 *
 * 回调纪律（AGENTS.md）：无障碍回调内只做有界记录与投递，不执行业务逻辑。
 */
class MiracleAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "service created (pid=${android.os.Process.myPid()})")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dispatcher = InputDispatcher(this, this)
        this.dispatcher = dispatcher
        HostBridge.bindInput(dispatcher, this)
        _connected.value = true
        android.util.Log.i(TAG, "service connected: dispatcher bound")
        HostBridge.notifyHostCapabilitiesChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name = event.packageName?.toString()
            if (!name.isNullOrEmpty()) {
                lastForegroundPackage = name
                lastForegroundAtMs = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance == this) {
            instance = null
        }
        android.util.Log.i(TAG, "service teardown (unbind/destroy)")
        dispatcher?.release()
        dispatcher = null
        HostBridge.unbindInput()
        _connected.value = false
        HostBridge.notifyHostCapabilitiesChanged()
    }

    private var dispatcher: InputDispatcher? = null

    companion object {
        private const val TAG = "miracle/a11y"
        @Volatile
        private var instance: MiracleAccessibilityService? = null

        @Volatile
        var lastForegroundPackage: String? = null
            private set

        @Volatile
        var lastForegroundAtMs: Long = 0L
            private set

        private val _connected = MutableStateFlow(false)

        /** 无障碍服务连接状态（UI 引导与自检前置检查消费）。 */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** 服务实例是否已连接（能力位的 Kotlin 侧事实来源）。 */
        fun isConnected(): Boolean = instance != null
    }
}
