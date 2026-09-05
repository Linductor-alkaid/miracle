package dev.linductor.miracle.host

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 显示几何（L0 公共工具）：读取当前真实屏幕尺寸与旋转。
 *
 * 用途：
 *  - [InputDispatcher] 把 [0,1] 规范坐标映射到当前屏幕像素（每次手势前重读，
 *    旋转后旧坐标由 epoch 失效）。
 *  - 无 ScreenCaptureProvider 绑定时为 [HostBridge.topologyJson] 提供
 *    display 拓扑兜底（cap_w/cap_h=0：无投影即无截屏能力，诚实上报）。
 */
object DisplayGeometry {

    /** 当前旋转下的整屏边界（含系统栏区域，触摸可达范围）。 */
    fun screenBounds(context: Context): Rect {
        val manager = context.getSystemService(WindowManager::class.java) ?: return Rect(0, 0, 0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    /** 当前旋转（0/1/2/3）；不可得时返回 0。 */
    fun rotation(context: Context): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            val manager = context.getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            manager?.defaultDisplay?.rotation ?: 0
        }
    } catch (_: Exception) {
        0
    }

    /** 逻辑密度（160dpi 基准）；不可得时返回 1。 */
    fun density(context: Context): Float = try {
        context.resources.displayMetrics.density.coerceAtLeast(1f)
    } catch (_: Exception) {
        1f
    }

    /** 无投影会话的拓扑兜底 JSON（结构对齐 ScreenCaptureProvider.topologyJson）。 */
    fun topologyJson(context: Context): String {
        val bounds = screenBounds(context)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return "{}"
        }
        val rotation = rotation(context)
        val density = density(context)
        return "{\"w\":${bounds.width()},\"h\":${bounds.height()}," +
            "\"rot\":$rotation,\"den\":$density,\"active\":1," +
            "\"il\":0,\"it\":0,\"ir\":0,\"ib\":0,\"cap_w\":0,\"cap_h\":0}"
    }
}
