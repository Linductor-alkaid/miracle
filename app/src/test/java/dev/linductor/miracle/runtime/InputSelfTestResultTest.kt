package dev.linductor.miracle.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2 输入自检结果 JSON 投影解析测试（探针/步骤/关闭统计与降级路径）。 */
class InputSelfTestResultTest {

    @Test
    fun `parses probe payload`() {
        val json = """
            {"ok":true,"stage":"complete","error":"",
             "steps":[
               {"name":"invalid_coords","ok":true,"detail":"sync INVALID_ARGUMENT","ms":0.0},
               {"name":"cancel_midflight","ok":true,"detail":"status=EXECUTION_UNCERTAIN side=1","ms":820.5}
             ],
             "probe":{"duplicates":0,"unknowns":0},
             "host":{"unknown_completions":0,"late_completions":0,"cancelled_ops":2,
                     "outstanding_leases":0},
             "stop":"OK","destroy":"OK"}
        """.trimIndent()
        val result = InputSelfTestResult.parseProbe(json)
        assertTrue(result.ok)
        assertEquals("complete", result.stage)
        assertEquals(2, result.probeSteps.size)
        assertEquals("invalid_coords", result.probeSteps[0].name)
        assertTrue(result.probeSteps[1].ok)
        assertEquals(820.5, result.probeSteps[1].elapsedMs, 0.0)
    }

    @Test
    fun `probe parse failure degrades explicitly`() {
        val result = InputSelfTestResult.parseProbe("{{{")
        assertFalse(result.ok)
        assertEquals("parse", result.stage)
    }

    @Test
    fun `parses adapter step payload`() {
        val json = """
            {"ok":true,"kind":"tap","receipt":"Completed","side_effect":false,
             "epoch":3,"ms":85.2,"error":""}
        """.trimIndent()
        val step = InputSelfTestResult.parseStep(json)
        assertTrue(step.ok)
        assertEquals("tap", step.kind)
        assertEquals("Completed", step.receipt)
        assertFalse(step.sideEffect)
        assertEquals(3L, step.epoch)
    }

    @Test
    fun `parses adapter step error payload`() {
        val step = InputSelfTestResult.parseStep(
            """{"ok":false,"kind":"type","error":"no focused editable node","ms":520.0}""",
        )
        assertFalse(step.ok)
        assertTrue(step.error.isNotEmpty())
    }

    @Test
    fun `parses close stats payload`() {
        val json = """
            {"ok":true,
             "bridge":{"submitted":7,"settled":7,"leases_released":0,"duplicates":0,
                       "unknowns":0,"late":0,"rejections":0,"violations":0},
             "host":{"unknown_completions":0,"late_completions":0,"cancelled_ops":0,
                     "outstanding_leases":0},
             "shutdown":"Completed"}
        """.trimIndent()
        val close = InputSelfTestResult.parseClose(json)
        assertTrue(close.ok)
        assertEquals(7L, close.submitted)
        assertEquals(7L, close.settled)
        assertEquals(0L, close.violations)
        assertEquals("Completed", close.shutdown)
    }

    @Test
    fun `close stats parse failure degrades explicitly`() {
        val close = InputSelfTestResult.parseClose("not-json")
        assertFalse(close.ok)
        assertEquals("parse-failed", close.shutdown)
    }
}
