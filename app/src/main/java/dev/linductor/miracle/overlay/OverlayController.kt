package dev.linductor.miracle.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.linductor.miracle.MainActivity
import dev.linductor.miracle.runtime.AgentRuntime
import dev.linductor.miracle.runtime.LoopEventParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 悬浮球控制器（前端设计 §5）：球体（拖动/单击展开/长按 ≥600ms＝Human Takeover，
 * 安全操作无需确认）+ 展开面板（第二 overlay 窗口承载 ComposeView，关闭即回收）。
 *
 * 生命周期 owner＝AgentForegroundService（show/hide 由其调用）；未授权悬浮窗时
 * 不显示（降级矩阵 §7）。状态与通知消费同一 AgentRuntime.state 流。
 */
class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: AgentRuntime = AgentRuntime,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var ballView: FloatingBallView? = null
    private var panelHost: PanelHostHolder? = null
    private var stateJob: Job? = null
    private var pulseJob: Job? = null

    val isShown: Boolean get() = ballView != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (ballView != null || !canShow()) {
            return
        }
        val view = FloatingBallView(context)
        view.contentDescription = "Miracle 悬浮球"
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
            width = BALL_SIZE
            height = BALL_SIZE
        }
        attachGestures(view, params)
        windowManager.addView(view, params)
        ballView = view
        observeState()
    }

    fun hide() {
        stateJob?.cancel()
        pulseJob?.cancel()
        stateJob = null
        pulseJob = null
        collapsePanel()
        ballView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        ballView = null
    }

    fun updateState(state: AgentRuntime.SessionState) {
        ballView?.state = state
        panelHost?.composeView?.setContent { PanelContent(state) }
    }

    private fun observeState() {
        stateJob = scope.launch {
            runtime.state.collect { state ->
                updateState(state)
                // 活动指示：观察/推理/动作期间高频呼吸（800ms 周期）。
                val active = state is AgentRuntime.SessionState.Running
                pulseJob?.cancel()
                if (active) {
                    pulseJob = scope.launch {
                        var ascending = true
                        var level = 0f
                        while (isActive) {
                            level += if (ascending) 0.2f else -0.2f
                            if (level >= 1f) {
                                ascending = false
                            }
                            if (level <= 0f) {
                                ascending = true
                            }
                            ballView?.pulse = level
                            delay(120)
                        }
                    }
                } else {
                    ballView?.pulse = 0f
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var pressed = false
        var moved = false
        var longPressed = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = System.currentTimeMillis()
                    pressed = true
                    moved = false
                    longPressed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && dx * dx + dy * dy > SLOP_SQ) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (event.rawX - BALL_SIZE / 2f).toInt().coerceAtLeast(0)
                        params.y = (event.rawY - BALL_SIZE / 2f).toInt().coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    pressed = false
                    when {
                        longPressed -> Unit // 长按已处理
                        !moved && held < LONG_PRESS_MS -> togglePanel()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    true
                }

                else -> false
            }
        }
        // 长按检测：50ms 轮询按下状态（仅在 pressed 期间评估）。
        scope.launch {
            while (true) {
                delay(50)
                if (pressed && !longPressed && !moved &&
                    System.currentTimeMillis() - downAt >= LONG_PRESS_MS
                ) {
                    longPressed = true
                    runtime.takeover()
                }
            }
        }
    }

    private fun togglePanel() {
        if (panelHost != null) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        if (panelHost != null || !canShow()) {
            return
        }
        val host = PanelLifecycleHost()
        val composeView = ComposeView(context)
        composeView.setViewTreeLifecycleOwner(host)
        composeView.setViewTreeSavedStateRegistryOwner(host)
        val params = WindowManager.LayoutParams(
            (resourcesWidthPx() * 0.7f).toInt().coerceAtLeast(480),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(composeView, params)
        host.start()
        // 初值在组合外捕获（后续更新经 updateState 重建内容）。
        val initialState = runtime.state.value
        composeView.setContent { PanelContent(initialState) }
        // 点击面板外关闭（WATCH_OUTSIDE_TOUCH）。
        composeView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapsePanel()
            }
            false
        }
        panelHost = PanelHostHolder(host, composeView)
    }

    private fun collapsePanel() {
        val holder = panelHost ?: return
        panelHost = null
        try {
            holder.composeView.setContent {}
            holder.host.stop()
            windowManager.removeView(holder.composeView)
        } catch (_: Exception) {
        }
    }

    private fun resourcesWidthPx(): Int {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels
    }

    private class PanelHostHolder(
        val host: PanelLifecycleHost,
        val composeView: ComposeView,
    )

    /** ComposeView 在 overlay 窗口所需的最小生命周期宿主。 */
    private class PanelLifecycleHost : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    @Composable
    private fun PanelContent(state: AgentRuntime.SessionState) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val phaseText = when (state) {
                        is AgentRuntime.SessionState.Running -> when (state.phase) {
                            LoopEventParser.Phase.Observing -> "观察中"
                            LoopEventParser.Phase.Reasoning -> "推理中"
                            LoopEventParser.Phase.Acting -> "执行动作"
                            LoopEventParser.Phase.Unknown -> "进行中"
                        }

                        is AgentRuntime.SessionState.Terminal -> "已结束：${state.outcome}"
                        AgentRuntime.SessionState.Idle -> "空闲"
                    }
                    Text("Miracle · $phaseText", style = MaterialTheme.typography.titleSmall)
                    if (state is AgentRuntime.SessionState.Running) {
                        Text(
                            "目标：${state.goal}\n动作 ${state.stepEvents} 次",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { runtime.cancelSession() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("停止任务")
                        }
                        Button(
                            onClick = { runtime.takeover() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                            ),
                        ) {
                            Text("接管（Takeover）")
                        }
                    } else {
                        Button(
                            onClick = {
                                collapsePanel()
                                context.startActivity(
                                    android.content.Intent(context, MainActivity::class.java)
                                        .addFlags(
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                                        ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("新建任务")
                        }
                    }
                    Text(
                        "长按悬浮球可随时接管",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    private companion object {
        const val BALL_SIZE = 132
        const val LONG_PRESS_MS = 600L
        const val SLOP_SQ = 24f * 24f
    }
}
