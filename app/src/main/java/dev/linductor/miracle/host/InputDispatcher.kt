package dev.linductor.miracle.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 输入能力提供者（L0）：手势合成与 dispatchGesture、文本注入、全局导航动作、
 * 协作取消与 RELEASE_ALL。
 *
 * **平台事实（实测 android.jar，API 35 公共面）**：`AccessibilityService` 无
 * `cancelGesture`，`dispatchGesture` 返回 boolean——应用侧无法中断已进入输入管线的
 * 手势。因此取消语义采用 host_abi.h 冻结的原子输入约定：
 *  - 序列未提交平台（或事件间隙）→ 停止后续事件，`CANCELLED(side=0)`；
 *  - 手势在途 → 等其自然结束（有界时长），`EXECUTION_UNCERTAIN(side=1)`；
 *  - 竞态下先完成 → 原结果如实上报。
 * RELEASE_ALL 在序列粒度生效（阻断未派发事件；在途手势按时长自然收敛，最长
 * 60s）。“合成抬起事件”需要非公共 API，留待上游/设备证据再评估（P2 已知限制）。
 *
 * 并发模型：native 在任意线程调用 [dispatch]/[cancel]/[releaseAll]，受理仅做
 * volatile 快检后立即返回，实际执行投递到主线程（Main.immediate 作用域）串行
 * 处理——[inflight]/[preCancelled]/各 [OpState] 字段只在主线程访问，无需锁。
 * 每个受理的 correlation 恰好一次完成回流（[OpState.done] 守卫），与 native
 * 操作注册表的 exactly-once 终态互补。
 */
class InputDispatcher(
    private val service: AccessibilityService,
    context: Context,
) {

    companion object {
        /** 在途输入操作上限（EXEC-04：队列必须有界），超限经回执 CAPACITY 结算。 */
        const val MAX_INFLIGHT = 16

        private const val TAG = "miracle/input"
        private const val DEFAULT_TAP_MS = 60L
        private const val DEFAULT_LONG_PRESS_MS = 600L
        private const val DEFAULT_SWIPE_MS = 350L
        private const val MAX_DURATION_MS = 60_000L
        // 焦点语义曝光时序：tap 落点正确时 Compose 焦点到无障碍树的可见性在
        // 部分厂商上可超 500ms（PJE110 实测失败样本 524ms 窗口耗尽），放宽到
        // 2s 仍有界；无焦点时按 Rejected fail-closed，不猜测目标。
        private const val FOCUS_RETRY_MS = 2_000L
        private const val FOCUS_RETRY_STEP_MS = 50L
        private const val NANOS_PER_MS = 1_000_000L
    }

    /** 单个手势在平台的终局。 */
    private enum class GestureEnd { COMPLETED, CANCELLED, REJECTED }

    /** 每个 correlation 的状态（仅主线程访问）。 */
    private class OpState {
        val job: CompletableJob = Job()
        var anySubmitted = false
        var interrupting = false
        var done = false
        var completion: ((HostAbiInput.InputCompletion) -> Unit)? = null
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val inflight = HashMap<Long, OpState>()
    private val preCancelled = HashSet<Long>()

    @Volatile
    private var released = false

    /**
     * 受理一次输入派发（native → Kotlin，任意线程调用）。立即返回：
     * true＝已受理（结果恰好一次经 [complete] 回流）；false＝provider 已释放。
     *
     * [deadlineNs] 约定：0＝无超时；负值＝deadline 已过期（立即按 DEADLINE_EXCEEDED
     * 结算，不触碰平台）；正值＝剩余等待时长（ns）。
     */
    fun dispatch(
        correlation: Long,
        events: List<HostAbiInput.InputEventSpec>,
        deadlineNs: Long,
        complete: (HostAbiInput.InputCompletion) -> Unit,
    ): Boolean {
        if (released) {
            return false
        }
        // 经 mainHandler 投递（而非 scope.launch）：作用域被 release 取消后仍能执行，
        // 保证已受理操作必有恰好一次结算。
        mainHandler.post {
            dispatchOnMain(correlation, events, deadlineNs, complete)
        }
        return true
    }

    /** 协作取消：标记中断；未提交的序列立即按 CANCELLED 结算，在途手势自然收敛。 */
    fun cancel(correlation: Long) {
        mainHandler.post {
            val state = inflight[correlation] ?: run {
                if (!released) {
                    // 取消先于派发协程执行：登记后到者直接按已取消结算。
                    preCancelled.add(correlation)
                }
                return@post
            }
            state.interrupting = true
        }
    }

    /** RELEASE_ALL：阻断全部在途序列的未派发事件（安全原语，无需确认）。 */
    fun releaseAll() {
        mainHandler.post {
            for (state in inflight.values) {
                state.interrupting = true
            }
        }
    }

    /** 服务销毁路径：在途操作按取消/不确定结算后关闭作用域。 */
    fun release() {
        if (released) {
            return
        }
        released = true
        mainHandler.post {
            for (state in inflight.values) {
                state.interrupting = true
                settle(state, interruptedResult(state))
            }
            inflight.clear()
            preCancelled.clear()
            scope.cancel()
        }
    }

    // ---- 内部（主线程） ----

    private fun dispatchOnMain(
        correlation: Long,
        events: List<HostAbiInput.InputEventSpec>,
        deadlineNs: Long,
        complete: (HostAbiInput.InputCompletion) -> Unit,
    ) {
        if (released) {
            // 受理后、执行前服务被销毁：未触碰平台，按已取消结算。
            complete(cancelledResult())
            return
        }
        if (preCancelled.remove(correlation)) {
            complete(cancelledResult())
            return
        }
        if (inflight.size >= MAX_INFLIGHT) {
            complete(
                HostAbiInput.InputCompletion(
                    HostAbiInput.ERR_CAPACITY, HostAbiInput.RECEIPT_UNKNOWN, false,
                ),
            )
            return
        }
        val state = OpState()
        state.completion = complete
        inflight[correlation] = state
        val sequence = scope.launch(state.job) {
            settle(state, runSequence(events, deadlineNs, state))
        }
        if (sequence.isCancelled && !state.done) {
            // 作用域已被 release 取消（与 release 的排空块同线程串行，无中间态）：
            // 序列协程不会执行，就地按已取消结算。
            inflight.remove(correlation)
            settle(state, cancelledResult())
        }
    }

    private suspend fun runSequence(
        events: List<HostAbiInput.InputEventSpec>,
        deadlineNs: Long,
        state: OpState,
    ): HostAbiInput.InputCompletion {
        val outcome = when {
            deadlineNs < 0 -> null // deadline 已过期：不触碰平台。
            deadlineNs == 0L -> runEvents(events, state) // 无超时。
            else -> withTimeoutOrNull((deadlineNs / NANOS_PER_MS).coerceAtLeast(1L)) {
                runEvents(events, state)
            }
        }
        return outcome ?: HostAbiInput.InputCompletion(
            HostAbiInput.ERR_DEADLINE_EXCEEDED,
            HostAbiInput.RECEIPT_UNKNOWN,
            state.anySubmitted,
        )
    }

    private suspend fun runEvents(
        events: List<HostAbiInput.InputEventSpec>,
        state: OpState,
    ): HostAbiInput.InputCompletion {
        for (event in events) {
            if (state.interrupting) {
                return interruptedResult(state)
            }
            when (event.kind) {
                HostAbiInput.KIND_RELEASE_ALL -> releaseAllExcept(state)

                HostAbiInput.KIND_BACK, HostAbiInput.KIND_HOME ->
                    if (!performGlobalAction(event.kind)) {
                        return rejected()
                    }

                HostAbiInput.KIND_TYPE ->
                    if (!performType(event.text, state)) {
                        return rejected()
                    }

                else -> when (performGesture(event, state)) {
                    GestureEnd.COMPLETED -> Unit
                    GestureEnd.REJECTED -> return rejected()
                    GestureEnd.CANCELLED ->
                        // 系统发起的取消（用户触摸/服务干扰）：副作用不确定。
                        return uncertainResult()
                }
            }
        }
        // 序列尾复查：取消可能在最后一个事件执行中/后到达（手势无法中断，
        // 自然完成也属"可能已发生副作用"，按取消时序如实标注）。
        if (state.interrupting) {
            return interruptedResult(state)
        }
        return HostAbiInput.InputCompletion(
            HostAbiInput.OK, HostAbiInput.RECEIPT_COMPLETED, false,
        )
    }

    private fun releaseAllExcept(carrier: OpState) {
        for (state in inflight.values) {
            if (state !== carrier) {
                state.interrupting = true
            }
        }
    }

    private fun interruptedResult(state: OpState) = HostAbiInput.InputCompletion(
        if (state.anySubmitted) {
            HostAbiInput.ERR_EXECUTION_UNCERTAIN
        } else {
            HostAbiInput.ERR_CANCELLED
        },
        HostAbiInput.RECEIPT_UNKNOWN,
        state.anySubmitted,
    )

    private fun uncertainResult() = HostAbiInput.InputCompletion(
        HostAbiInput.ERR_EXECUTION_UNCERTAIN, HostAbiInput.RECEIPT_UNKNOWN, true,
    )

    private fun cancelledResult() = HostAbiInput.InputCompletion(
        HostAbiInput.ERR_CANCELLED, HostAbiInput.RECEIPT_UNKNOWN, false,
    )

    private fun rejected() = HostAbiInput.InputCompletion(
        HostAbiInput.OK, HostAbiInput.RECEIPT_REJECTED, false,
    )

    /** 恰好一次结算（与 release/竞态路径共享守卫）。 */
    private fun settle(state: OpState, result: HostAbiInput.InputCompletion) {
        if (state.done) {
            return
        }
        state.done = true
        state.job.complete()
        state.completion?.invoke(result)
        state.completion = null
    }

    private suspend fun performGesture(
        event: HostAbiInput.InputEventSpec,
        state: OpState,
    ): GestureEnd = suspendCancellableCoroutine { continuation ->
        val gesture = try {
            buildGesture(event)
        } catch (_: Exception) {
            null
        }
        if (gesture == null) {
            continuation.resumeWith(kotlin.Result.success(GestureEnd.REJECTED))
            return@suspendCancellableCoroutine
        }
        val accepted = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                kotlin.Result.success(GestureEnd.COMPLETED),
                            )
                        }
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                kotlin.Result.success(GestureEnd.CANCELLED),
                            )
                        }
                    }
                },
                mainHandler,
            )
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            continuation.resumeWith(kotlin.Result.success(GestureEnd.REJECTED))
            return@suspendCancellableCoroutine
        }
        state.anySubmitted = true
        // 注意：已进入输入管线的手势无法从应用侧中断（公共 API 无 cancelGesture）；
        // 协程超时取消后平台回调被 isActive 守卫丢弃，副作用以 side_effect 如实标注。
    }

    /**
     * 导航动作。back 走 performGlobalAction；home 在部分厂商（ColorOS 实测）对
     * 无障碍源 HOME 拦截（派发成功但不导航），追加 CATEGORY_HOME intent 兜底——
     * 本应用前台会话可用；后台自动化场景受后台启动限制，待 P3 悬浮窗权限后覆盖。
     */
    private fun performGlobalAction(kind: Int): Boolean {
        val action = if (kind == HostAbiInput.KIND_HOME) {
            AccessibilityService.GLOBAL_ACTION_HOME
        } else {
            AccessibilityService.GLOBAL_ACTION_BACK
        }
        val dispatched = try {
            service.performGlobalAction(action)
        } catch (_: Exception) {
            false
        }
        if (!dispatched || kind != HostAbiInput.KIND_HOME) {
            return dispatched
        }
        return try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(home)
            true
        } catch (_: Exception) {
            // 兜底失败时以 GLOBAL_ACTION_HOME 的派发结果为准（如实，不重试）。
            dispatched
        }
    }

    /** 文本注入：焦点可编辑节点 ACTION_SET_TEXT；无焦点时 500ms 内有界重试。 */
    private suspend fun performType(text: String, state: OpState): Boolean {
        val deadline = SystemClock.uptimeMillis() + FOCUS_RETRY_MS
        while (SystemClock.uptimeMillis() < deadline && !state.interrupting) {
            val applied = try {
                typeIntoFocusedNode(text)
            } catch (_: Exception) {
                false
            }
            if (applied) {
                return true
            }
            delay(FOCUS_RETRY_STEP_MS)
        }
        return false
    }

    private fun typeIntoFocusedNode(text: String): Boolean {
        val root = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: run {
            android.util.Log.w(TAG, "type: no active window root")
            return false
        }
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        // 焦点可能在宿主/容器节点上（Compose 的 a11y 暴露差异：tap 聚焦内部
        // 可编辑节点，程序化聚焦可能落在容器）——在焦点子树内有界查找可编辑
        // 节点；不跨出焦点子树猜测目标（fail-closed）。
        val node = focused?.let { resolveEditableTarget(it) }
        if (node == null) {
            android.util.Log.w(
                TAG,
                "type: no editable node under focus " +
                    "(focused=${focused?.className ?: "none"})",
            )
            return false
        }
        return try {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text,
            )
            val applied = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!applied) {
                android.util.Log.w(TAG, "type: ACTION_SET_TEXT returned false")
            }
            applied
        } catch (_: Exception) {
            false
        }
    }

    /** 焦点子树内的有界可编辑节点解析（BFS，≤64 节点，防深树失控）。 */
    private fun resolveEditableTarget(focused: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (focused.isEditable) {
            return focused
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(focused)
        var visited = 0
        while (queue.isNotEmpty() && visited < 64) {
            val current = queue.removeFirst()
            visited += 1
            val count = try {
                current.childCount
            } catch (_: Exception) {
                0
            }
            for (index in 0 until count) {
                val child = try {
                    current.getChild(index)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (child.isEditable) {
                    return child
                }
                queue.add(child)
            }
        }
        return null
    }

    /** 手势合成：[0,1] 规范坐标 → 当前屏幕像素（每次重读几何，clamp 屏内）。 */
    internal fun buildGesture(event: HostAbiInput.InputEventSpec): GestureDescription? {
        val bounds: Rect = DisplayGeometry.screenBounds(appContext)
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) {
            return null
        }

        fun px(value: Double, extent: Int): Float =
            HostAbiInput.toPixel(value, extent).toFloat()

        val builder = GestureDescription.Builder()
        when (event.kind) {
            HostAbiInput.KIND_TAP -> builder.addStroke(
                pointStroke(px(event.x, width), px(event.y, height), DEFAULT_TAP_MS),
            )

            HostAbiInput.KIND_LONG_PRESS -> builder.addStroke(
                pointStroke(
                    px(event.x, width), px(event.y, height),
                    gestureDuration(event.durationMs, DEFAULT_LONG_PRESS_MS),
                ),
            )

            HostAbiInput.KIND_SWIPE -> {
                val duration = gestureDuration(event.durationMs, DEFAULT_SWIPE_MS)
                var startX = px(event.x, width)
                var startY = px(event.y, height)
                var endX = px(event.x2, width)
                var endY = px(event.y2, height)
                if (startX == endX && startY == endY) {
                    // 零长度路径不可描画：终点偏移 1px（有界、无行为差异）。
                    endX += 1f
                }
                val path = Path()
                path.moveTo(startX, startY)
                path.lineTo(endX, endY)
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            }

            else -> return null
        }
        return builder.build()
    }

    /** 定点 stroke：公共 API 仅提供 Path 构造（无 PointF 版本），用同点线段表达按压。 */
    private fun pointStroke(x: Float, y: Float, duration: Long): GestureDescription.StrokeDescription {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, y)
        return GestureDescription.StrokeDescription(path, 0L, duration)
    }

    private fun gestureDuration(requested: Long, default: Long): Long = when {
        requested in 1..MAX_DURATION_MS -> requested
        else -> default
    }
}
