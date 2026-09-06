package dev.linductor.miracle.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** native loop 事件/结果 JSON 投影解析全分支。 */
class LoopEventParserTest {

    @Test
    fun `解析相位事件`() {
        assertEquals(
            LoopEventParser.Phase.Observing,
            (LoopEventParser.parse("""{"kind":"phase","payload":{"phase":"observing"}}""")
                as LoopEventParser.LoopEvent.PhaseEvent).phase,
        )
        assertEquals(
            LoopEventParser.Phase.Reasoning,
            (LoopEventParser.parse("""{"kind":"phase","payload":{"phase":"reasoning"}}""")
                as LoopEventParser.LoopEvent.PhaseEvent).phase,
        )
        assertEquals(
            LoopEventParser.Phase.Acting,
            (LoopEventParser.parse("""{"kind":"phase","payload":{"phase":"acting"}}""")
                as LoopEventParser.LoopEvent.PhaseEvent).phase,
        )
        assertEquals(
            LoopEventParser.Phase.Unknown,
            (LoopEventParser.parse("""{"kind":"phase","payload":{"phase":"weird"}}""")
                as LoopEventParser.LoopEvent.PhaseEvent).phase,
        )
    }

    @Test
    fun `解析终态结果事件含步进记录`() {
        val json = """
            {"kind":"result","payload":{
              "outcome":"Completed","summary":"goal achieved",
              "steps_count":3,"recoveries":1,"repairs":0,"events":42,
              "steps":[
                {"step":1,"phase":"acted","summary":"tap(0.50,0.50)","verified":false,"note":""},
                {"step":2,"phase":"verified","summary":"","verified":true,"note":"ok"}
              ],
              "mira_version":"0.1.0"}}
        """.trimIndent()
        val event = LoopEventParser.parse(json) as LoopEventParser.LoopEvent.LoopResultEvent
        val result = event.result
        assertTrue(result.completed)
        assertEquals("goal achieved", result.summary)
        assertEquals(3, result.stepsCount)
        assertEquals(2, result.steps.size)
        assertEquals("tap(0.50,0.50)", result.steps[0].summary)
        assertTrue(result.steps[1].verified)
        assertEquals(42L, result.events)
    }

    @Test
    fun `cancelled 与 maxsteps 终态标记`() {
        val cancelled = LoopEventParser.parse(
            """{"kind":"result","payload":{"outcome":"Cancelled","summary":"cancellation requested"}}""",
        ) as LoopEventParser.LoopEvent.LoopResultEvent
        assertTrue(cancelled.result.cancelled)
        assertTrue(!cancelled.result.completed)

        val maxSteps = LoopEventParser.parse(
            """{"kind":"result","payload":{"outcome":"MaxSteps","summary":"steps"}}""",
        ) as LoopEventParser.LoopEvent.LoopResultEvent
        assertTrue(!maxSteps.result.completed && !maxSteps.result.cancelled)
    }

    @Test
    fun `解析确认请求与结算事件`() {
        val request = LoopEventParser.parse(
            """
            {"kind":"confirmation_request","payload":{
              "challenge":"a1b2","nonce":"c3d4","digest":"e5f6",
              "summary":"tap(0.50,0.50)","risk":"R3","expires_at":1700000000,
              "correlation":9,"lifetime_ms":60000}}
            """.trimIndent(),
        ) as LoopEventParser.LoopEvent.ConfirmationRequestEvent
        assertEquals("a1b2", request.request.challenge)
        assertEquals("c3d4", request.request.nonce)
        assertEquals(9L, request.request.correlation)
        assertEquals(60_000L, request.request.lifetimeMs)

        val settled = LoopEventParser.parse(
            """{"kind":"confirmation_settled","payload":{"challenge":"a1b2","outcome":"approved"}}""",
        ) as LoopEventParser.LoopEvent.ConfirmationSettledEvent
        assertEquals("approved", settled.outcome)
    }

    @Test
    fun `解析会话事件与未知事件`() {
        val session = LoopEventParser.parse(
            """{"kind":"session","payload":{"state":"closed"}}""",
        ) as LoopEventParser.LoopEvent.SessionEvent
        assertEquals("closed", session.state)

        assertNull(LoopEventParser.parse("""{"kind":"mystery","payload":{}}"""))
        assertNull(LoopEventParser.parse("not json"))
        // payload 缺失时以空对象容忍（不崩溃）。
        assertEquals(
            LoopEventParser.Phase.Unknown,
            (LoopEventParser.parse("""{"kind":"phase"}""")
                as LoopEventParser.LoopEvent.PhaseEvent).phase,
        )
    }

    @Test
    fun `解析 loopClose 汇总`() {
        val (ok, shutdown, lastResult) = LoopEventParser.parseCloseSummary(
            """{"ok":true,"shutdown":"Completed","last_result":null}""",
        )
        assertTrue(ok)
        assertEquals("Completed", shutdown)
        assertNull(lastResult)

        val (ok2, shutdown2, result) = LoopEventParser.parseCloseSummary(
            """{"ok":false,"shutdown":"Incomplete","last_result":{"outcome":"Cancelled"}}""",
        )
        assertTrue(!ok2)
        assertEquals("Incomplete", shutdown2)
        assertTrue(result!!.contains("Cancelled"))
    }
}
