package dev.linductor.miracle.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EnvSelfTestResult 解析器单元测试（runtime_glue 环境自检 JSON 契约锁定）。 */
class EnvSelfTestResultTest {

    @Test
    fun `parses successful two-frame environment self test`() {
        val json = """
            {"ok":true,"stage":"complete","frames":[
              {"ok":true,"w":836,"h":1836,"format":"RGBA8888","planes":1,"epoch":1,
               "artifact":"present","quality":"Good","ms":18.3},
              {"ok":true,"w":836,"h":1836,"format":"RGBA8888","planes":1,"epoch":1,
               "artifact":"present","quality":"Good","ms":4.2}],
             "bridge":{"submitted":2,"settled":2,"leases_released":2,"duplicates":0,
                       "unknowns":0,"late":0,"rejections":0,"violations":0},
             "host":{"unknown_completions":0,"late_completions":0,"cancelled_ops":0,
                     "outstanding_leases":0},
             "shutdown":"Completed","mira_version":"0.1.0"}
        """.trimIndent()

        val result = EnvSelfTestResult.parse(json)

        assertTrue(result.ok)
        assertEquals("complete", result.stage)
        assertEquals(2, result.frames.size)
        val frame = result.frames[0]
        assertTrue(frame.ok)
        assertEquals(836, frame.width)
        assertEquals(1836, frame.height)
        assertEquals("RGBA8888", frame.format)
        assertEquals(1L, frame.epoch)
        val bridge = result.bridge
        requireNotNull(bridge)
        assertEquals(2L, bridge.submitted)
        assertEquals(2L, bridge.leasesReleased)
        assertEquals(0L, bridge.violations)
        val host = result.host
        requireNotNull(host)
        assertEquals(0L, host.outstandingLeases)
        assertEquals("Completed", result.shutdown)
    }

    @Test
    fun `parses adapter create failure without frames`() {
        val json = """
            {"ok":false,"stage":"adapter_create","error":"host capability probe failed"}
        """.trimIndent()

        val result = EnvSelfTestResult.parse(json)

        assertFalse(result.ok)
        assertEquals("adapter_create", result.stage)
        assertEquals("host capability probe failed", result.error)
        assertNull(result.bridge)
        assertTrue(result.frames.isEmpty())
    }

    @Test
    fun `malformed payload degrades to explicit parse error`() {
        val result = EnvSelfTestResult.parse("<<<not-json>>>")

        assertFalse(result.ok)
        assertEquals("parse", result.stage)
        assertEquals("自检结果解析失败", result.error)
    }
}
