package dev.linductor.miracle.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1 自检状态机：首跑、再次授权重跑、在途折叠、拒绝、Bound 补跑与服务超时。
 * 根因回归：此前守卫进程级一次性而状态实例私有，"再次自检"停在 Running 无人完成。
 */
class CaptureSelfTestCoordinatorTest {

    private fun okPayload() =
        """{"ok":true,"stage":"complete","frames":[],"shutdown":"Completed",""" +
            """"mira_version":"0.1.0"}"""

    private fun failedPayload() =
        """{"ok":false,"stage":"observe","error":"no frame"}"""

    private fun coordinator(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        awaitServiceBound: suspend (Long) -> Boolean = { true },
        payload: () -> String = { okPayload() },
        boundFailureMessage: () -> String = { "" },
        calls: AtomicInteger? = null,
    ): CaptureSelfTestCoordinator {
        val count = calls ?: AtomicInteger()
        return CaptureSelfTestCoordinator(
            scope = scope,
            awaitServiceBound = awaitServiceBound,
            environmentSelfTest = {
                count.incrementAndGet()
                payload()
            },
            boundFailureMessage = boundFailureMessage,
        )
    }

    @Test
    fun `首跑_授权消费后呈现Done`() {
        val calls = AtomicInteger()
        val c = coordinator(calls = calls)

        c.begin()
        assertTrue(c.state.value is CaptureState.Requesting)
        c.markConsumed()

        val state = c.state.value
        assertTrue("expected Done, was $state", state is CaptureState.Done)
        assertEquals(1, calls.get())
    }

    @Test
    fun `再次授权_从Done重新执行自检`() {
        val calls = AtomicInteger()
        val c = coordinator(calls = calls)
        c.markConsumed()
        assertTrue(c.state.value is CaptureState.Done)

        // 用户再次授权（"再次自检"）：begin → 消费 → 必须重新跑完整链路。
        c.begin()
        assertTrue(c.state.value is CaptureState.Requesting)
        c.markConsumed()

        assertTrue(c.state.value is CaptureState.Done)
        assertEquals(2, calls.get())
    }

    @Test
    fun `在途请求_折叠为一次重跑`() {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val c = coordinator(
            awaitServiceBound = { gate.await(); true },
            calls = calls,
        )

        c.markConsumed() // 首跑在 gate 处挂起（在途）
        assertTrue(c.state.value is CaptureState.Running)
        c.markConsumed() // 在途期间的再次授权 → 折叠

        gate.complete(Unit)

        assertTrue(c.state.value is CaptureState.Done)
        assertEquals("重叠请求折叠为恰好一次重跑", 2, calls.get())
    }

    @Test
    fun `授权拒绝_呈现PermissionDenied`() {
        val calls = AtomicInteger()
        val c = coordinator(calls = calls)
        c.begin()
        c.markDenied()
        assertTrue(c.state.value is CaptureState.PermissionDenied)
        assertEquals(0, calls.get())
    }

    @Test
    fun `服务已Bound_仅Idle时补跑_终态不重跑`() {
        val calls = AtomicInteger()
        val c = coordinator(calls = calls)

        c.onServiceBound() // Idle → 补跑
        assertTrue(c.state.value is CaptureState.Done)
        assertEquals(1, calls.get())

        c.onServiceBound() // Done（重建重放）→ 不重复执行
        assertEquals(1, calls.get())
    }

    @Test
    fun `服务未Bound超时_呈现Failed并带服务消息`() {
        val c = coordinator(
            awaitServiceBound = { false },
            boundFailureMessage = { "媒体投影已停止" },
        )
        c.markConsumed()
        val state = c.state.value
        assertTrue("expected Failed, was $state", state is CaptureState.Failed)
        state as CaptureState.Failed
        assertEquals("service", state.stage)
        assertEquals("媒体投影已停止", state.message)
    }

    @Test
    fun `native自检失败负载_呈现Failed与stage`() {
        val c = coordinator(payload = { failedPayload() })
        c.markConsumed()
        val state = c.state.value
        assertTrue(state is CaptureState.Failed)
        state as CaptureState.Failed
        assertEquals("observe", state.stage)
        assertEquals("no frame", state.message)
    }

    @Test
    fun `native自检抛出异常_呈现Failed不搁浅`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = CaptureSelfTestCoordinator(
            scope = scope,
            awaitServiceBound = { true },
            environmentSelfTest = { error("jni blew up") },
        )
        c.markConsumed()
        val state = c.state.value
        assertTrue("expected Failed, was $state", state is CaptureState.Failed)
        state as CaptureState.Failed
        assertEquals("native", state.stage)
        // 失败后仍可重试（守卫已释放）。
        assertTrue(scope.isActive)
    }
}
