package dev.linductor.miracle.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 输入契约单测：事件 JSON 解析（含 UTF-8 文本与非法输入拒绝）、
 * [0,1]²→像素映射（clamp/边界）。
 */
class InputSpecTest {

    @Test
    fun `parses all gesture kinds`() {
        val json = """
            [
              {"k":1,"x":0.25,"y":0.75,"x2":0,"y2":0,"d":0,"t":""},
              {"k":2,"x":0.5,"y":0.5,"x2":0,"y2":0,"d":1500,"t":""},
              {"k":3,"x":0.1,"y":0.2,"x2":0.9,"y2":0.8,"d":350,"t":""},
              {"k":4,"x":0,"y":0,"x2":0,"y2":0,"d":0,"t":"你好 miracle"},
              {"k":5,"x":0,"y":0,"x2":0,"y2":0,"d":0,"t":""},
              {"k":6,"x":0,"y":0,"x2":0,"y2":0,"d":0,"t":""},
              {"k":7,"x":0,"y":0,"x2":0,"y2":0,"d":0,"t":""}
            ]
        """.trimIndent()
        val events = HostAbiInput.parseEvents(json)
        assertNotNull(events)
        assertEquals(7, events!!.size)
        assertEquals(HostAbiInput.KIND_TAP, events[0].kind)
        assertEquals(0.25, events[0].x, 0.0)
        assertEquals(HostAbiInput.KIND_LONG_PRESS, events[1].kind)
        assertEquals(1500L, events[1].durationMs)
        assertEquals(HostAbiInput.KIND_SWIPE, events[2].kind)
        assertEquals(0.9, events[2].x2, 0.0)
        assertEquals(HostAbiInput.KIND_TYPE, events[3].kind)
        assertEquals("你好 miracle", events[3].text)
        assertEquals(HostAbiInput.KIND_BACK, events[4].kind)
        assertEquals(HostAbiInput.KIND_HOME, events[5].kind)
        assertEquals(HostAbiInput.KIND_RELEASE_ALL, events[6].kind)
    }

    @Test
    fun `rejects empty array`() {
        assertNull(HostAbiInput.parseEvents("[]"))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(HostAbiInput.parseEvents("not json"))
        assertNull(HostAbiInput.parseEvents("{\"k\":1}"))
    }

    @Test
    fun `rejects out of range coordinates`() {
        assertNull(HostAbiInput.parseEvents("""[{"k":1,"x":1.5,"y":0.5}]"""))
        assertNull(HostAbiInput.parseEvents("""[{"k":3,"x":0.5,"y":0.5,"x2":-0.1,"y2":0.5}]"""))
        assertNull(HostAbiInput.parseEvents("""[{"k":1,"x":0.5}]""")) // y 缺省 NaN
    }

    @Test
    fun `rejects invalid text and duration`() {
        assertNull(HostAbiInput.parseEvents("""[{"k":4,"x":0,"y":0,"t":""}]""")) // 空文本
        val longText = "a".repeat(4097)
        assertNull(HostAbiInput.parseEvents("""[{"k":4,"x":0,"y":0,"t":"$longText"}]"""))
        assertNull(HostAbiInput.parseEvents("""[{"k":1,"x":0.5,"y":0.5,"d":60001}]"""))
        assertNull(HostAbiInput.parseEvents("""[{"k":99,"x":0.5,"y":0.5}]""")) // 未知 kind
    }

    @Test
    fun `accepts maximum boundary coordinates and text`() {
        val maxText = "a".repeat(4096)
        val events = HostAbiInput.parseEvents(
            """[{"k":3,"x":0.0,"y":1.0,"x2":1.0,"y2":0.0,"d":60000,"t":""},
                {"k":4,"x":0,"y":0,"t":"$maxText"}]""",
        )
        assertNotNull(events)
        assertEquals(2, events!!.size)
        assertTrue(HostAbiInput.isEventValid(events[0]))
        assertTrue(HostAbiInput.isEventValid(events[1]))
    }

    @Test
    fun `maps canonical coordinates to clamped pixels`() {
        // 真机纵向屏（1080×2376）典型值。
        assertEquals(540, HostAbiInput.toPixel(0.5, 1080))
        assertEquals(1188, HostAbiInput.toPixel(0.5, 2376))
        assertEquals(0, HostAbiInput.toPixel(0.0, 1080))
        assertEquals(1079, HostAbiInput.toPixel(1.0, 1080))
        assertEquals(1, HostAbiInput.toPixel(0.001, 1080))
        assertEquals(3, HostAbiInput.toPixel(0.5, 5)) // 半值向上取整
        // 极小屏（clamp 下界保护 extent=1）。
        assertEquals(0, HostAbiInput.toPixel(1.0, 1))
    }

    @Test
    fun `completion json round trip`() {
        val json = HostAbiInput.completionToJson(
            HostAbiInput.InputCompletion(
                HostAbiInput.ERR_EXECUTION_UNCERTAIN,
                HostAbiInput.RECEIPT_UNKNOWN,
                true,
            ),
        )
        val parsed = org.json.JSONObject(json)
        assertEquals(HostAbiInput.ERR_EXECUTION_UNCERTAIN, parsed.getInt("status"))
        assertEquals(HostAbiInput.RECEIPT_UNKNOWN, parsed.getInt("receipt"))
        assertEquals(1, parsed.getInt("side"))
    }
}
