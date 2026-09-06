package dev.linductor.miracle.settings

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 模型配置存储（SharedPreferences 明文非敏感项 + 密文 API key 文件）。
 *
 * API key 经 [AesGcmEngine]（AndroidKeyStore）加密后落 filesDir，绝不进入
 * 日志/事件/共享存储；明文仅在 load 后驻留内存（P3 计划决策 7）。
 */
class ModelConfigStore(
    context: Context,
    private val engine: AesGcmEngine = AndroidKeystoreEngine(),
) {
    private val prefs = context.getSharedPreferences("model_config", Context.MODE_PRIVATE)
    private val keyFile = File(context.filesDir, "model.key.enc")

    fun load(): ModelConfig {
        val hasKey = keyFile.exists() && keyFile.length() > 0
        return ModelConfig(
            endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
            apiPrefix = prefs.getString(KEY_PREFIX, "/v1").orEmpty().ifBlank { "/v1" },
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            dialect = prefs.getString(KEY_DIALECT, "chat").orEmpty().ifBlank { "chat" },
            maxSteps = prefs.getInt(KEY_MAX_STEPS, 16).coerceIn(1, 128),
            callTimeoutMs = prefs.getInt(KEY_TIMEOUT, 30_000).coerceIn(5_000, 120_000),
            hasApiKey = hasKey,
        )
    }

    /** 解密 API key；不存在或校验失败返回 null（fail-closed，不抛出）。 */
    fun loadApiKey(): String? {
        if (!keyFile.exists()) {
            return null
        }
        return try {
            val plain = engine.decrypt(keyFile.readBytes())
            String(plain, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun save(config: ModelConfig, apiKey: String?) {
        prefs.edit()
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_PREFIX, config.apiPrefix.trim().ifBlank { "/v1" })
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_DIALECT, if (config.dialect == "responses") "responses" else "chat")
            .putInt(KEY_MAX_STEPS, config.maxSteps.coerceIn(1, 128))
            .putInt(KEY_TIMEOUT, config.callTimeoutMs.coerceIn(5_000, 120_000))
            .apply()
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                keyFile.delete()
            } else {
                keyFile.writeBytes(engine.encrypt(apiKey.trim().toByteArray(Charsets.UTF_8)))
            }
        }
    }

    /** 披露确认状态（首启一次性；可重置以便复验引导流程）。 */
    fun disclosureAccepted(): Boolean = prefs.getBoolean(KEY_DISCLOSURE, false)

    fun setDisclosureAccepted(value: Boolean) {
        prefs.edit().putBoolean(KEY_DISCLOSURE, value).apply()
    }

    /** 序列化（不含 key 明文；诊断/测试用）。 */
    fun describe(): String = JSONObject().apply {
        put("endpoint", load().endpoint)
        put("model", load().model)
        put("dialect", load().dialect)
        put("has_api_key", load().hasApiKey)
    }.toString()

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_PREFIX = "api_prefix"
        const val KEY_MODEL = "model"
        const val KEY_DIALECT = "dialect"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_TIMEOUT = "call_timeout_ms"
        const val KEY_DISCLOSURE = "disclosure_accepted"
    }
}
