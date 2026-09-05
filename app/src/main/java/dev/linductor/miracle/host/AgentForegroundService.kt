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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 宿主前台服务（P1：承载 MediaProjection 截屏管线）。
 *
 * 生命周期顺序遵守 Android 14+ 语义：先 startForeground(mediaProjection 类型)，
 * 再 getMediaProjection。投影授权每次会话独立（DEC-003/构建打包设计 §5.1）。
 */
class AgentForegroundService : Service() {

    enum class HostState { Idle, Starting, Bound, Stopped, Failed }

    companion object {
        private const val TAG = "miracle/service"
        private const val CHANNEL_ID = "agent_session"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "dev.linductor.miracle.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "dev.linductor.miracle.extra.RESULT_DATA"

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

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等：已绑定的宿主忽略重复 start（Activity 重建可能重放授权结果，
        // 而 consent data 只能消费一次，重复消费必然失败且无意义）。
        if (provider != null) {
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.ParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            _state.value = HostState.Failed
            _stateMessage.value = "缺少投影授权结果"
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithType()
        _state.value = HostState.Starting
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val active = manager.getMediaProjection(resultCode, resultData)
            projection = active
            active.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun teardown() {
        HostBridge.unbind()
        provider?.release()
        provider = null
        projection?.stop()
        projection = null
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
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
