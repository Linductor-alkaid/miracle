package dev.linductor.miracle.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3h-03 用户消息入队前数据策略（长度上限 + 凭据模式过滤；fail-closed 拒绝）。 */
class UserMessagePolicyTest {

    @Test
    fun `空消息与纯空白拒绝`() {
        val empty = UserMessagePolicy.sanitize("")
        assertTrue(empty is UserMessagePolicy.Verdict.Rejected)
        val blank = UserMessagePolicy.sanitize("   \n\t ")
        assertTrue(blank is UserMessagePolicy.Verdict.Rejected)
        assertEquals("消息为空", (blank as UserMessagePolicy.Verdict.Rejected).reason)
    }

    @Test
    fun `超长消息拒绝`() {
        val verdict = UserMessagePolicy.sanitize("x".repeat(UserMessagePolicy.MAX_LENGTH + 1))
        assertTrue(verdict is UserMessagePolicy.Verdict.Rejected)
        assertTrue(
            (verdict as UserMessagePolicy.Verdict.Rejected).reason.contains("消息过长"),
        )
    }

    @Test
    fun `边界长度接受`() {
        val verdict = UserMessagePolicy.sanitize("好".repeat(UserMessagePolicy.MAX_LENGTH))
        assertTrue(verdict is UserMessagePolicy.Verdict.Accepted)
    }

    @Test
    fun `凭据模式命中拒绝`() {
        val samples = listOf(
            "我的密钥 sk-abcdefghijklmnopqrstuvwxyz1234 请使用",
            "AKIA1234567890ABCDEF 是访问键",
            "用 Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 认证",
            "-----BEGIN RSA PRIVATE KEY-----",
            "token 是 d41d8cd98f00b204e9800998ecf8427e7f",
            "密码 password=hunter2secret",
            "apikey:0123456789abcdef0123456789abcdef",
        )
        for (sample in samples) {
            val verdict = UserMessagePolicy.sanitize(sample)
            assertTrue("应拒绝：$sample", verdict is UserMessagePolicy.Verdict.Rejected)
            assertTrue(
                (verdict as UserMessagePolicy.Verdict.Rejected).reason.contains("凭据"),
            )
        }
    }

    @Test
    fun `正常指令接受且保留原文`() {
        val text = "  请改为点击右上角的设置图标  "
        val verdict = UserMessagePolicy.sanitize(text)
        assertTrue(verdict is UserMessagePolicy.Verdict.Accepted)
        assertEquals("请改为点击右上角的设置图标", (verdict as UserMessagePolicy.Verdict.Accepted).text)
    }

    @Test
    fun `普通文本不误伤`() {
        val samples = listOf(
            "继续以靶点为动作对象",
            "打开设置后向下滑动一屏",
            "step 3 已完成，请继续",
            "输入 hello world 后回车",
        )
        for (sample in samples) {
            assertTrue(
                "不应拒绝：$sample",
                UserMessagePolicy.sanitize(sample) is UserMessagePolicy.Verdict.Accepted,
            )
        }
    }

    @Test
    fun `日志摘要是十六进制且稳定`() {
        val digest = UserMessagePolicy.logDigest("继续以靶点为动作对象")
        assertEquals(16, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
        assertEquals(digest, UserMessagePolicy.logDigest("继续以靶点为动作对象"))
        assertNotEquals(digest, UserMessagePolicy.logDigest("其他消息"))
    }
}
