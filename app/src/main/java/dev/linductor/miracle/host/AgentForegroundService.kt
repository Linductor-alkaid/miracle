package dev.linductor.miracle.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.linductor.miracle.MainActivity
import dev.linductor.miracle.R
import dev.linductor.miracle.overlay.OverlayController
import dev.linductor.miracle.runtime.AgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Agent 宿主前台服务（P1：MediaProjection 截屏管线；P3：常驻通知状态联动 +
 * 通知 action（停止/接管）+ 悬浮球生命周期 owner）。
 *
 * 生命周期顺序遵守 Android 14+ 语义：先 startForeground(mediaProjection 类型)，
 * 再 getMediaProjection。投影授权每次会话独立（DEC-003/构建打包设计 §5.1）。
 * 通知与悬浮球消费同一 AgentRuntime.state 流（活动指示同源，前端设计 §5）。
 */
class AgentForegroundService : Service() {

    enum class HostState { Idle, Starting, Bound, Stopped, Failed }

    companion object {
        private const val TAG = "miracle/service"
        private const val CHANNEL_ID = "agent_session"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "dev.linductor.miracle.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "dev.linductor.miracle.extra.RESULT_DATA"
        const val ACTION_TAKEOVER = "dev.linductor.miracle.action.TAKEOVER"
        const val ACTION_CANCEL_TASK = "dev.linductor.miracle.action.CANCEL_TASK"

        private val _state = MutableStateFlow(HostState.Idle)
        val state: StateFlow<HostState> = _state.asStateFlow()

        private val _stateMessage = MutableStateFlow("")
        val stateMessage: StateFlow<String> = _stateMessage.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var provider: ScreenCaptureProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 服务拥有的结构化作用域（EXEC-03；onDestroy 取消）。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        overlay = OverlayController(this, serviceScope)
        // 常驻通知文案随会话状态联动（同一状态源）。
        serviceScope.launch {
            AgentRuntime.state.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKEOVER -> {
                AgentRuntime.takeover()
                return START_NOT_STICKY
            }

            ACTION_CANCEL_TASK -> {
                AgentRuntime.cancelSession()
                return START_NOT_STICKY
            }
        }
        // 无授权数据的重复 start（Activity 重建重放且未携带结果）忽略。
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.ParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            if (provider != null) {
                return START_NOT_STICKY
            }
            _state.value = HostState.Failed
            _stateMessage.value = "缺少投影授权结果"
            stopSelf()
            return START_NOT_STICKY
        }
        // 携带新授权数据＝用户主动再次授权（如换采集范围重跑自检）。Android 14+
        // 同一时刻仅允许一个活跃投影，旧授权可能随新授权即刻失效——必须拆旧建新，
        // 不能沿用旧会话（否则"整屏"授权被静默丢弃、实际仍在采旧范围）。
        if (provider != null) {
            Log.i(TAG, "re-consent: rebuilding projection session")
            teardown()
        }

        startForegroundWithType()
        _state.value = HostState.Starting
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val active = manager.getMediaProjection(resultCode, resultData)
            projection = active
            active.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 仅当前投影的 onStop 生效：重建会话时旧投影 stop 的迟到回调
                    // 不拆新管线（teardown 同步执行后回调才经主线程派发）。
                    if (projection !== active) {
                        return
                    }
                    Log.i(TAG, "media projection stopped")
                    _state.value = HostState.Stopped
                    _stateMessage.value = "媒体投影已停止（授权被撤销或会话结束）"
                    teardown()
                }
            }, mainHandler)
            val capture = ScreenCaptureProvider(this, active)
            provider = capture
            HostBridge.bind(capture)
            _state.value = HostState.Bound
            _stateMessage.value = ""
            Log.i(TAG, "host bound: ${capture.topologyJson()}")
            // 悬浮球随会话显示（未授权悬浮窗时跳过，主 GUI 仍可用——降级矩阵）。
            overlay?.show()
        } catch (error: Exception) {
            Log.e(TAG, "projection start failed", error)
            _state.value = HostState.Failed
            _stateMessage.value = error.message ?: "投影启动失败"
            teardown()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun teardown() {
        overlay?.hide()
        HostBridge.unbind()
        provider?.release()
        provider = null
        projection?.stop()
        projection = null
    }

    private fun startForegroundWithType() {
        val notification = buildNotificationText(getString(R.string.agent_notification_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: AgentRuntime.SessionState) {
        val text = when (state) {
            is AgentRuntime.SessionState.Running -> "运行中：${
                when (state.phase) {
                    dev.linductor.miracle.runtime.LoopEventParser.Phase.Observing -> "观察"
                    dev.linductor.miracle.runtime.LoopEventParser.Phase.Reasoning -> "推理"
                    dev.linductor.miracle.runtime.LoopEventParser.Phase.Acting -> "动作"
                    dev.linductor.miracle.runtime.LoopEventParser.Phase.Unknown -> "进行中"
                }
            } · 动作 ${state.stepEvents} 次"

            is AgentRuntime.SessionState.Terminal ->
                if (state.ok) "任务完成" else "任务结束：${state.outcome}"

            AgentRuntime.SessionState.Idle -> getString(R.string.agent_notification_text)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotificationText(text),
        )
    }

    private fun buildNotificationText(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_CANCEL_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val takeover = PendingIntent.getService(
            this, 2,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_TAKEOVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止任务", cancel).build())
            .addAction(Notification.Action.Builder(null, "接管", takeover).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.ParcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
