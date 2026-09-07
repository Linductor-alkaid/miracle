package dev.linductor.miracle.settings

import javax.crypto.KeyGenerator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 模型配置序列化与凭据加密核心（JVM 引擎注入；AndroidKeyStore 版本真机验证）。 */
class ModelConfigTest {

    @Test
    fun `https 校验与完整性`() {
        assertTrue(ModelConfig(endpoint = "https://api.example.com", model = "gpt-x", hasApiKey = true).complete)
        assertFalse(ModelConfig(endpoint = "http://api.example.com", model = "gpt-x", hasApiKey = true).complete)
        assertFalse(ModelConfig(endpoint = "https://api.example.com", model = "", hasApiKey = true).complete)
        assertFalse(ModelConfig(endpoint = "https://api.example.com", model = "gpt-x", hasApiKey = false).complete)
    }

    @Test
    fun `native 配置 JSON 携带全部字段`() {
        val json = ModelConfig(
            endpoint = "https://api.example.com",
            apiPrefix = "/v2",
            model = "test-model",
            dialect = "chat",
            maxSteps = 24,
            callTimeoutMs = 45_000,
        ).toNativeJson(apiKey = "sk-test")
        val root = JSONObject(json)
        assertEquals("https://api.example.com", root.getString("endpoint"))
        assertEquals("/v2", root.getString("api_prefix"))
        assertEquals("test-model", root.getString("model"))
        assertEquals("chat", root.getString("dialect"))
        assertEquals("sk-test", root.getString("api_key"))
        assertEquals(24, root.getInt("max_steps"))
        assertEquals(45_000, root.getInt("call_timeout_ms"))
        assertEquals("kotlin", root.getString("transport"))
        // 总超时不显式传递：0 会被 native 当有效值把会话 deadline 设成立即过期
        // （observe 即被拒）；缺省由 native 按步数×调用期限推默认预算。
        assertFalse(root.has("timeout_ms"))
    }

    @Test
    fun `AES-GCM roundtrip 与随机 IV`() {
        val engine = testEngine()
        val plain = "sk-中文-key-12345".toByteArray(Charsets.UTF_8)
        val first = engine.encrypt(plain)
        val second = engine.encrypt(plain)
        // 随机 IV：两次密文不同
        assertNotEquals(first.toList(), second.toList())
        assertEquals(String(plain, Charsets.UTF_8), String(engine.decrypt(first), Charsets.UTF_8))
        assertEquals(String(plain, Charsets.UTF_8), String(engine.decrypt(second), Charsets.UTF_8))
    }

    @Test
    fun `篡改密文被拒绝`() {
        val engine = testEngine()
        val blob = engine.encrypt("secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        try {
            engine.decrypt(blob)
            fail("tampered blob must not decrypt")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `过短 blob 被拒绝`() {
        val engine = testEngine()
        try {
            engine.decrypt(ByteArray(10))
            fail("short blob must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun testEngine(): SymmetricEngine {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return SymmetricEngine(generator.generateKey())
    }
}
