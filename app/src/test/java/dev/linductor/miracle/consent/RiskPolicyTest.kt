package dev.linductor.miracle.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R3 策略表全分支（P3 计划决策 6；总计划 §6 从严默认）。 */
class RiskPolicyTest {

    private fun tap() = RiskPolicy.InputAction(RiskPolicy.KIND_TAP)
    private fun type() = RiskPolicy.InputAction(RiskPolicy.KIND_TYPE, text = "abc")
    private fun releaseAll() = RiskPolicy.InputAction(RiskPolicy.KIND_RELEASE_ALL)

    @Test
    fun `普通目标与普通动作为会话级同意覆盖`() {
        assertEquals(
            RiskPolicy.Decision.Allow,
            RiskPolicy.decide("打开设置并调高亮度", listOf(tap(), type()), "com.android.settings"),
        )
    }

    @Test
    fun `目标命中支付关键词时全部动作要求确认`() {
        assertEquals(
            RiskPolicy.Decision.RequireConfirmation,
            RiskPolicy.decide("帮我支付订单", listOf(tap()), "com.shop.app"),
        )
    }

    @Test
    fun `目标关键词大小写不敏感`() {
        assertEquals(
            RiskPolicy.Decision.RequireConfirmation,
            RiskPolicy.decide("Login to the app", listOf(tap()), null),
        )
    }

    @Test
    fun `release_all 即使在敏感目标下也不要求确认`() {
        assertEquals(
            RiskPolicy.Decision.Allow,
            RiskPolicy.decide("删除这些照片", listOf(releaseAll()), null),
        )
    }

    @Test
    fun `敏感目标下混合动作仍要求确认（存在非 release_all 动作）`() {
        assertEquals(
            RiskPolicy.Decision.RequireConfirmation,
            RiskPolicy.decide("发送消息给张三", listOf(releaseAll(), tap()), "com.miui.home"),
        )
    }

    @Test
    fun `敏感应用内 type 动作要求确认`() {
        assertEquals(
            RiskPolicy.Decision.RequireConfirmation,
            RiskPolicy.decide("登录", listOf(type()), "com.eg.android.AlipayGphone"),
        )
    }

    @Test
    fun `非敏感目标且非敏感应用内 type 动作放行`() {
        assertEquals(
            RiskPolicy.Decision.Allow,
            RiskPolicy.decide("在备注里输入文字", listOf(type()), "com.android.settings"),
        )
    }

    @Test
    fun `敏感应用内非 type 动作默认放行`() {
        assertEquals(
            RiskPolicy.Decision.Allow,
            RiskPolicy.decide("查看页面", listOf(tap()), "com.eg.android.AlipayGphone"),
        )
    }

    @Test
    fun `parseActions 解析合法事件数组`() {
        val json = """[{"k":1,"x":0.5,"y":0.5},{"k":4,"x":0,"y":0,"t":"hello"}]"""
        val actions = RiskPolicy.parseActions(json)
        assertNotNull(actions)
        assertEquals(2, actions!!.size)
        assertEquals(RiskPolicy.KIND_TAP, actions[0].kind)
        assertTrue(actions[1].isType)
        assertEquals("hello", actions[1].text)
    }

    @Test
    fun `parseActions 拒绝空数组与非法 kind`() {
        assertNull(RiskPolicy.parseActions("[]"))
        assertNull(RiskPolicy.parseActions("""[{"k":0}]"""))
        assertNull(RiskPolicy.parseActions("""[{"k":8}]"""))
        assertNull(RiskPolicy.parseActions("not json"))
    }

    @Test
    fun `summarize 不包含 type 文本内容`() {
        val summary = RiskPolicy.summarize(
            listOf(RiskPolicy.InputAction(RiskPolicy.KIND_TYPE, text = "secret-credential")),
        )
        assertEquals("输入文本", summary)
    }
}
