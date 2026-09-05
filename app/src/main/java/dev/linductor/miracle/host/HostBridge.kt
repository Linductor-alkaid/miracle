package dev.linductor.miracle.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeoutException

/**
 * native 宿主桥门面：双向 JNI 的 Kotlin 端。
 *
 * native → Kotlin（低频控制面）：[requestFrame]、[topologyJson]、[permissionState]。
 * Kotlin → native：[nativeCompleteFrame]（帧完成）、[nativeNotifyEpochChanged]
 * （旋转/投影失效）、[environmentSelfTest]（P1 链路自检）。
 *
 * 本对象不承载业务状态机；frames 流仅供 UI 预览（权威链路是 mira EventStore）。
 */
object HostBridge {

    /** 单帧拷贝结果（RGBA8888，行已紧凑）。 */
    data class CapturedFrame(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val pixels: ByteArray,
        val beginNs: Long,
        val endNs: Long,
    )

    @Volatile
    private var provider: ScreenCaptureProvider? = null

    private val _frames = MutableStateFlow<List<CapturedFrame>>(emptyList())

    /** 最近完成的帧（UI 预览用，最多保留 4 帧）。 */
    val frames: StateFlow<List<CapturedFrame>> = _frames.asStateFlow()

    /** 绑定宿主能力提供者（由 AgentForegroundService 调用）。 */
    fun bind(value: ScreenCaptureProvider) {
        provider = value
        value.setEpochListener { nativeNotifyEpochChanged() }
    }

    /** 解除绑定；提供者的释放由其所有者（服务）负责。 */
    fun unbind() {
        provider = null
        _frames.value = emptyList()
    }

    /** native 调用：受理一次截屏请求。立即返回，完成经 [nativeCompleteFrame] 回流。 */
    @JvmStatic
    fun requestFrame(correlation: Long, deadlineNs: Long): Boolean {
        val current = provider
        if (current == null) {
            android.util.Log.w("miracle/hostbridge", "requestFrame: provider not bound")
            return false
        }
        return try {
            current.requestFrame(correlation, deadlineNs) { result ->
                complete(correlation, result)
            }
            true
        } catch (error: Throwable) {
            android.util.Log.w("miracle/hostbridge", "requestFrame dispatch failed", error)
            false
        }
    }

    /** native 调用：显示拓扑（native 尺寸 + 实际采集尺寸 cap_w/cap_h）。 */
    @JvmStatic
    fun topologyJson(): String = provider?.topologyJson() ?: "{}"

    /** native 调用：0=granted 1=revoked 2=unknown。 */
    @JvmStatic
    fun permissionState(): Int {
        val current = provider ?: return 2
        return if (current.isActive) 0 else 1
    }

    private fun complete(correlation: Long, result: Result<CapturedFrame>) {
        result.onSuccess { frame ->
            android.util.Log.i(
                "miracle/hostbridge",
                "frame ${frame.width}x${frame.height} rot=${frame.rotation} " +
                    "${frame.pixels.size}B in ${(frame.endNs - frame.beginNs) / 1_000_000}ms",
            )
            _frames.value = (_frames.value + frame).takeLast(4)
            nativeCompleteFrame(
                correlation, 1, frame.width, frame.height, frame.rotation,
                frame.pixels, frame.beginNs, frame.endNs, 0,
            )
        }.onFailure { error ->
            android.util.Log.w(
                "miracle/hostbridge",
                "frame failed for correlation $correlation: $error",
            )
            nativeCompleteFrame(correlation, 0, 0, 0, 0, ByteArray(0), 0, 0, mapError(error))
        }
    }

    // MiraHostStatus：3=UNAVAILABLE 4=PERMISSION_DENIED 6=DEADLINE_EXCEEDED
    private fun mapError(error: Throwable): Int = when (error) {
        is TimeoutException -> 6
        is SecurityException -> 4
        else -> 3
    }

    @JvmStatic
    external fun nativeCompleteFrame(
        correlation: Long,
        ok: Int,
        width: Int,
        height: Int,
        rotation: Int,
        pixels: ByteArray,
        beginNs: Long,
        endNs: Long,
        errCode: Int,
    )

    @JvmStatic
    external fun nativeNotifyEpochChanged()

    /** P1 环境自检：Executor + AndroidHostAdapter + observe×2 + 干净关闭。 */
    @JvmStatic
    external fun environmentSelfTest(): String
}
