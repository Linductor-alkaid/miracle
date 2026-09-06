package dev.linductor.miracle.settings

import dev.linductor.miracle.host.HttpTransportBinding.EndpointPolicyCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提供商预设目录契约：端点可通过宿主传输策略（https、非私有地址）、方言合法、
 * 套用只替换四个目标字段、选中态匹配可往返。
 */
class ProviderPresetsTest {

    @Test
    fun `all presets use https endpoints accepted by endpoint policy`() {
        ProviderPresets.ALL.forEach { preset ->
            assertTrue("endpoint ${preset.endpoint} must be https", preset.endpoint.startsWith("https://"))
            assertTrue(
                "endpoint ${preset.endpoint} must pass EndpointPolicyCheck",
                EndpointPolicyCheck.isAllowed("${preset.endpoint}${preset.apiPrefix}/chat/completions"),
            )
        }
    }

    @Test
    fun `all presets have unique ids, valid dialect and non-blank defaults`() {
        val ids = mutableSetOf<String>()
        ProviderPresets.ALL.forEach { preset ->
            assertTrue("duplicate id ${preset.id}", ids.add(preset.id))
            assertTrue(
                "dialect ${preset.dialect} of ${preset.id}",
                preset.dialect == "chat" || preset.dialect == "responses",
            )
            assertTrue("model blank for ${preset.id}", preset.defaultModel.isNotBlank())
            assertTrue("prefix must start with / for ${preset.id}", preset.apiPrefix.startsWith("/"))
        }
    }

    @Test
    fun `applyTo replaces endpoint prefix dialect model only`() {
        val base = ModelConfig(
            endpoint = "https://old.example.com",
            apiPrefix = "/x",
            model = "old-model",
            dialect = "responses",
            maxSteps = 7,
            callTimeoutMs = 45_000,
            hasApiKey = true,
        )
        val preset = ProviderPresets.ALL.first { it.id == "zhipu" }
        val applied = preset.applyTo(base)

        assertEquals(preset.endpoint, applied.endpoint)
        assertEquals(preset.apiPrefix, applied.apiPrefix)
        assertEquals(preset.dialect, applied.dialect)
        assertEquals(preset.defaultModel, applied.model)
        // 非目标字段与密钥状态保留
        assertEquals(7, applied.maxSteps)
        assertEquals(45_000, applied.callTimeoutMs)
        assertTrue(applied.hasApiKey)
    }

    @Test
    fun `match round-trips after apply and survives model edit`() {
        val preset = ProviderPresets.ALL.first { it.id == "openai" }
        val applied = preset.applyTo(ModelConfig())
        assertTrue(preset.matches(applied))
        assertEquals(preset.id, ProviderPresets.match(applied)?.id)

        // 模型名可改（不参与匹配）；端点改动手动退出预设选中态
        assertTrue(preset.matches(applied.copy(model = "custom-model")))
        assertFalse(preset.matches(applied.copy(endpoint = "https://elsewhere.example.com")))
        assertNull(ProviderPresets.match(applied.copy(endpoint = "https://elsewhere.example.com")))
    }

    @Test
    fun `default config matches no preset`() {
        assertNull(ProviderPresets.match(ModelConfig()))
    }

    @Test
    fun `trimmed endpoint still matches`() {
        val preset = ProviderPresets.ALL.first()
        val applied = preset.applyTo(ModelConfig())
        assertNotNull(ProviderPresets.match(applied.copy(endpoint = " ${preset.endpoint} ")))
    }
}
