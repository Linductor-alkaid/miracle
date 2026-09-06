package dev.linductor.miracle.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import dev.linductor.miracle.runtime.AgentRuntime
import dev.linductor.miracle.runtime.LoopEventParser

/**
 * 悬浮球常驻态（前端设计 §5）：TYPE_APPLICATION_OVERLAY + 自绘 View。
 *
 * 状态环颜色映射 SessionState（灰 Idle/蓝观察推理/橙动作/绿完成/红失败或待确认）；
 * 活动指示＝动作/观察相位期间高频呼吸。无障碍语义：contentDescription 携带状态。
 */
class FloatingBallView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /** 呼吸相位 [0,1]（外部按帧驱动；非活动时收敛到 0）。 */
    var pulse: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var state: AgentRuntime.SessionState = AgentRuntime.SessionState.Idle
        set(value) {
            field = value
            contentDescription = describeState(value)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.62f
        val (core, ring) = colorsFor(state)

        // 活动呼吸外环
        if (pulse > 0f) {
            glowPaint.color = ring
            glowPaint.alpha = (140 * pulse).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = 6f + 10f * pulse
            canvas.drawCircle(cx, cy, radius + 6f + 14f * pulse, glowPaint)
        }
        // 状态环
        ringPaint.color = ring
        ringPaint.strokeWidth = 10f
        canvas.drawCircle(cx, cy, radius + 12f, ringPaint)
        // 球体（中心亮渐变）
        corePaint.shader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.4f,
            Color.WHITE, core, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, corePaint)
        corePaint.shader = null
    }

    private fun colorsFor(state: AgentRuntime.SessionState): Pair<Int, Int> = when (state) {
        is AgentRuntime.SessionState.Running -> when (state.phase) {
            LoopEventParser.Phase.Acting -> COLOR_ORANGE to COLOR_ORANGE_RING
            else -> COLOR_BLUE to COLOR_BLUE_RING
        }

        is AgentRuntime.SessionState.Terminal ->
            if (state.ok) COLOR_GREEN to COLOR_GREEN_RING else COLOR_RED to COLOR_RED_RING

        AgentRuntime.SessionState.Idle -> COLOR_GRAY to COLOR_GRAY_RING
    }

    private fun describeState(state: AgentRuntime.SessionState): String = when (state) {
        AgentRuntime.SessionState.Idle -> "Miracle 空闲"
        is AgentRuntime.SessionState.Running -> "Miracle 运行中：${
            when (state.phase) {
                LoopEventParser.Phase.Observing -> "观察"
                LoopEventParser.Phase.Reasoning -> "推理"
                LoopEventParser.Phase.Acting -> "动作"
                LoopEventParser.Phase.Unknown -> "进行中"
            }
        }"

        is AgentRuntime.SessionState.Terminal ->
            if (state.ok) "Miracle 任务完成" else "Miracle 任务结束：${state.outcome}"
    }

    companion object {
        val COLOR_GRAY = Color.parseColor("#9E9E9E")
        val COLOR_GRAY_RING = Color.parseColor("#616161")
        val COLOR_BLUE = Color.parseColor("#42A5F5")
        val COLOR_BLUE_RING = Color.parseColor("#1565C0")
        val COLOR_ORANGE = Color.parseColor("#FFA726")
        val COLOR_ORANGE_RING = Color.parseColor("#E65100")
        val COLOR_GREEN = Color.parseColor("#66BB6A")
        val COLOR_GREEN_RING = Color.parseColor("#2E7D32")
        val COLOR_RED = Color.parseColor("#EF5350")
        val COLOR_RED_RING = Color.parseColor("#C62828")
    }
}
