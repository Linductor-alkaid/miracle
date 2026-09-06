package dev.linductor.miracle.host

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 截屏能力提供者（L0）：MediaProjection → VirtualDisplay + ImageReader(RGBA_8888)。
 *
 * 约束（P1 计划"关键实现决策"）：
 *  - 降采样：mira adapter 的 MemoryArtifactStore 上限 8MB，采集分辨率按
 *    [MAX_PIXELS]（1.6M px ≈ 6.4MB）收敛；这是宿主侧事实能力，经 topology 上报。
 *  - 帧时钟使用 [System.nanoTime]（CLOCK_MONOTONIC，与 std::chrono::steady_clock
 *    同域），供 mira 的 freshness 判定使用。
 *  - “最新帧”语义：listener 常驻持有最新 Image（maxImages=3，持有 1），请求取最新
 *    并在 frameLock 内拷贝；静态画面下第二次请求复用同一帧（P1 已知，见计划）。
 *  - 旋转与投影停止都触发 epoch 回调（native 递增 epoch 并广播能力变化）。
 */
class ScreenCaptureProvider(context: Context, private val projection: MediaProjection) {

    companion object {
        private const val TAG = "miracle/capture"

        // mira AndroidHostAdapter 内置 MemoryArtifactStore 上限 8MB（不可配置，
        // 已登记 MIR-20260905-004）。连续两次 observe 需容纳两帧：0.9M px ≈
        // 3.6MB/帧，两帧 7.2MB；该分辨率对 VLM 决策亦足够。
        private const val MAX_PIXELS = 900_000
    }

    private val displayManager =
        context.getSystemService(DisplayManager::class.java)

    @Suppress("DEPRECATION")
    private val display: Display =
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay

    private val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
    val nativeWidth: Int = metrics.widthPixels
    val nativeHeight: Int = metrics.heightPixels
    private val density: Float = metrics.densityDpi / 160f

    // 降采样目标（偶数化，避免奇数尺寸的 stride 问题）。
    private val scale = min(1.0, sqrt(MAX_PIXELS.toDouble() / (nativeWidth * nativeHeight)))
    val captureWidth: Int = ((nativeWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    val captureHeight: Int = ((nativeHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)

    @Volatile
    var isActive = true
        private set

    @Volatile
    private var rotation: Int = display.rotation

    private val handlerThread = HandlerThread("miracle-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val reader: ImageReader =
        ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
    private val virtualDisplay: VirtualDisplay = projection.createVirtualDisplay(
        /* name = */ "miracle-capture",
        /* width = */ captureWidth,
        /* height = */ captureHeight,
        /* densityDpi = */ metrics.densityDpi,
        /* flags = */ DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        /* surface = */ reader.surface,
        /* callback = */ null,
        /* handler = */ null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Any()

    @Volatile
    private var latest: Image? = null

    private var epochListener: (() -> Unit)? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val changed = display.rotation
            if (changed != rotation) {
                rotation = changed
                epochListener?.invoke()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            isActive = false
            epochListener?.invoke()
        }
    }

    init {
        projection.registerCallback(projectionCallback, handler)
        displayManager.registerDisplayListener(displayListener, handler)
        reader.setOnImageAvailableListener({ available ->
            try {
                val next = available.acquireLatestImage()
                if (next != null) {
                    synchronized(frameLock) {
                        val old = latest
                        latest = next
                        old?.close()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "image available callback failed", error)
            }
        }, handler)
    }

    fun setEpochListener(listener: () -> Unit) {
        epochListener = listener
    }

    fun requestFrame(
        correlation: Long,
        deadlineNs: Long,
        done: (Result<HostBridge.CapturedFrame>) -> Unit,
    ) {
        scope.launch {
            val beginNs = System.nanoTime()
            val timeoutMs = if (deadlineNs > 0) {
                (deadlineNs / 1_000_000).coerceIn(500L, 10_000L)
            } else {
                4_000L
            }
            try {
                val captured = awaitFrameCopy(timeoutMs, beginNs)
                if (captured == null) {
                    Log.w(TAG, "no frame arrived within ${timeoutMs}ms (latest=${latest != null})")
                    done(Result.failure(TimeoutException("no frame within ${timeoutMs}ms")))
                } else {
                    done(Result.success(captured))
                }
            } catch (error: Exception) {
                done(Result.failure(error))
            }
        }
    }

    /**
     * 等待并拷贝最新帧。取引用与拷贝同处 frameLock 临界区：listener 替换/关闭旧帧
     * 也需该锁，杜绝"await 返回后、拷贝前持帧已被关闭"的竞争——整屏模式下录屏
     * 指示动画使帧持续翻动，该窗口不再是罕见路径。
     */
    private suspend fun awaitFrameCopy(timeoutMs: Long, beginNs: Long): HostBridge.CapturedFrame? =
        withTimeoutOrNull(timeoutMs) {
            while (isActive) {
                val captured = synchronized(frameLock) {
                    latest?.let { image -> copyFrameLocked(image, beginNs) }
                }
                if (captured != null) {
                    return@withTimeoutOrNull captured
                }
                delay(5)
            }
            null
        }

    /** 调用方持有 frameLock；image 必为锁内当前的 latest（未关闭）。 */
    private fun copyFrameLocked(image: Image, beginNs: Long): HostBridge.CapturedFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride == 4 && rowStride >= image.width * 4) {
            "unexpected RGBA plane layout: pixel=$pixelStride row=$rowStride w=${image.width}"
        }
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 4)
        val row = ByteArray(rowStride)
        var position = 0
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, rowStride)
            System.arraycopy(row, 0, out, position, width * 4)
            position += width * 4
        }
        return HostBridge.CapturedFrame(
            width = width,
            height = height,
            rotation = rotation,
            pixels = out,
            beginNs = beginNs,
            endNs = System.nanoTime(),
        )
    }

    fun topologyJson(): String {
        val active = if (isActive) 1 else 0
        return "{\"w\":$nativeWidth,\"h\":$nativeHeight,\"rot\":$rotation," +
            "\"den\":$density,\"active\":$active,\"il\":0,\"it\":0,\"ir\":0,\"ib\":0," +
            "\"cap_w\":$captureWidth,\"cap_h\":$captureHeight}"
    }

    fun release() {
        isActive = false
        scope.cancel()
        virtualDisplay.release()
        reader.setOnImageAvailableListener(null, null)
        synchronized(frameLock) {
            latest?.close()
            latest = null
        }
        reader.close()
        displayManager.unregisterDisplayListener(displayListener)
        projection.unregisterCallback(projectionCallback)
        handlerThread.quitSafely()
        projection.stop()
    }
}
