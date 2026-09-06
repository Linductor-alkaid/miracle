package dev.linductor.miracle.settings

/**
 * 模型提供商预设目录。
 *
 * mira 公共 API 不含提供商注册表：安装包只提供 OpenAiCompatibleProvider，
 * 经两种冻结方言（openai.responses.v1 / openai.chat-completions.v1）对接任意
 * OpenAI 兼容端点（model_provider.hpp / model_profile.hpp）。本目录据此把
 * 主流厂商的公开兼容端点做成一键预设——套用后仅剩 API key 需要用户填写。
 *
 * 证据口径（对齐 mira CapabilityEvidence）：预设条目为 Configured 级
 * （端点/前缀/方言按各厂商公开的 OpenAI 兼容文档填写），与任一厂商的实际
 * 互操作以设置页"模型连通性自检"结果为准，不做已验证宣称。
 * 默认模型名是建议值，保存前可任意修改。
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val apiPrefix: String,
    /** "responses"（OpenAI Responses v1）或 "chat"（Chat Completions v1）。 */
    val dialect: String,
    val defaultModel: String,
) {
    /** 套用到当前配置：只替换端点/前缀/方言/模型建议值，保留其余字段与密钥状态。 */
    fun applyTo(config: ModelConfig): ModelConfig = config.copy(
        endpoint = endpoint,
        apiPrefix = apiPrefix,
        dialect = dialect,
        model = defaultModel,
    )

    /** 选中态判定：四个套用字段与当前配置一致（模型可另行修改，不参与匹配）。 */
    fun matches(config: ModelConfig): Boolean =
        config.endpoint.trim() == endpoint &&
            config.apiPrefix.trim() == apiPrefix &&
            config.dialect == dialect
}

object ProviderPresets {

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            endpoint = "https://api.openai.com",
            apiPrefix = "/v1",
            dialect = "responses",
            defaultModel = "gpt-4o-mini",
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            endpoint = "https://api.deepseek.com",
            apiPrefix = "/v1",
            dialect = "chat",
            defaultModel = "deepseek-chat",
        ),
        ProviderPreset(
            id = "zhipu",
            displayName = "智谱 GLM",
            endpoint = "https://open.bigmodel.cn",
            apiPrefix = "/api/paas/v4",
            dialect = "chat",
            defaultModel = "glm-4-flash",
        ),
        ProviderPreset(
            id = "moonshot",
            displayName = "Moonshot Kimi",
            endpoint = "https://api.moonshot.cn",
            apiPrefix = "/v1",
            dialect = "chat",
            defaultModel = "moonshot-v1-8k",
        ),
        ProviderPreset(
            id = "dashscope",
            displayName = "阿里云百炼 Qwen",
            endpoint = "https://dashscope.aliyuncs.com",
            apiPrefix = "/compatible-mode/v1",
            dialect = "chat",
            defaultModel = "qwen-plus",
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            endpoint = "https://openrouter.ai",
            apiPrefix = "/api/v1",
            dialect = "chat",
            defaultModel = "openrouter/auto",
        ),
    )

    /** 当前配置命中的预设；无命中（手动编辑/自定义端点）返回 null。 */
    fun match(config: ModelConfig): ProviderPreset? = ALL.firstOrNull { it.matches(config) }
}
