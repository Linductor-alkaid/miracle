package dev.linductor.miracle.settings

/**
 * 模型配置（设置页维护；密钥仅以密文持久化，出库后只在内存）。
 *
 * toNativeJson 产出的对象经 JNI 进入 native（API key 为内存传输凭据，
 * 架构 §6：不入日志/事件/崩溃报告）。
 */
data class ModelConfig(
    val endpoint: String = "",
    val apiPrefix: String = "/v1",
    val model: String = "",
    /** "responses"（OpenAI Responses v1）或 "chat"（Chat Completions v1）。 */
    val dialect: String = "chat",
    val maxSteps: Int = 16,
    val callTimeoutMs: Int = 30_000,
    val hasApiKey: Boolean = false,
) {
    val httpsValid: Boolean get() = endpoint.startsWith("https://") && endpoint.length > 8

    /** 会话可启动的配置完整性（SessionGate 用；key 存在性由 store 判定）。 */
    val complete: Boolean
        get() = httpsValid && model.isNotBlank() && hasApiKey

    /** native loopOpen/modelConnectivityTest 的配置 JSON（apiKey 为内存凭据）。 */
    fun toNativeJson(apiKey: String, transport: String = "kotlin"): String {
        val root = org.json.JSONObject()
        root.put("endpoint", endpoint)
        root.put("api_prefix", apiPrefix)
        root.put("model", model)
        root.put("dialect", dialect)
        root.put("api_key", apiKey)
        root.put("max_steps", maxSteps)
        root.put("call_timeout_ms", callTimeoutMs)
        root.put("timeout_ms", 0)
        root.put("transport", transport)
        return root.toString()
    }
}
