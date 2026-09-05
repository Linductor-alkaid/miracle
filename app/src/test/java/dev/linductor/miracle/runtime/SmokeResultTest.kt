package dev.linductor.miracle.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SmokeResult 解析器单元测试（bridge JSON 契约的 Kotlin 侧锁定）。 */
class SmokeResultTest {

    @Test
    fun `parses successful baseline payload`() {
        val json = """
            {"ok":true,"stage":"complete","detail":"baseline completed",
             "init_ms":12,"wait_ms":3,"result_code":"Applied",
             "task_terminal":false,"final_state":"Stopped","mira_version":"0.1.0"}
        """.trimIndent()

        val result = SmokeResult.parse(json)

        assertTrue(result.ok)
        assertEquals("complete", result.stage)
        assertEquals(12L, result.initMs)
        assertEquals(3L, result.waitMs)
        assertEquals("Applied", result.resultCode)
        assertEquals("Stopped", result.finalState)
        assertEquals("0.1.0", result.miraVersion)
        assertFalse(result.taskTerminal)
    }

    @Test
    fun `parses failure payload with missing optional fields`() {
        val json = """
            {"ok":false,"stage":"submit","detail":"command was not admitted",
             "init_ms":0,"wait_ms":0}
        """.trimIndent()

        val result = SmokeResult.parse(json)

        assertFalse(result.ok)
        assertEquals("submit", result.stage)
        assertEquals("", result.resultCode)
    }

    @Test
    fun `malformed payload degrades to explicit parse error`() {
        val result = SmokeResult.parse("not json at all")

        assertFalse(result.ok)
        assertEquals("parse", result.stage)
        assertEquals("自检结果解析失败", result.detail)
    }
}
