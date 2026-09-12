package dev.linductor.miracle.runtime

import java.security.MessageDigest

/**
 * 用户消息入队前数据策略（P3h-03；DEC-016 把脱敏义务落在宿主）。
 *
 * 纯函数：长度上限 + 凭据模式过滤。命中凭据模式一律**拒绝**（fail-closed、原因
 * 对 UI 可见），不做静默改写——改写用户指令会改变语义，拒绝更诚实。策略表变更
 * 走决策记录（对齐 consent.RiskPolicy 纪律）。
 */
object UserMessagePolicy {

    /** 入队消息长度上限（UTF-16 字符；约 1KB 级，足够单条任务调整指令）。 */
    const val MAX_LENGTH = 1024

    /** 凭据启发式模式（命中即拒绝；与日志脱敏同向从严）。 */
    private val CREDENTIAL_PATTERNS = listOf(
        // 常见服务商 API key 前缀（OpenAI sk-、 Anthropic sk-ant-、火山/阿里等）。
        Regex("""\bsk-[A-Za-z0-9_-]{16,}"""),
        Regex("""\bsk-ant-[A-Za-z0-9_-]{16,}"""),
        // AK/SK 形态（阿里云/华为云等 AccessKey）。
        Regex("""\b(?:AK|AS|LTAI)[A-Za-z0-9]{14,}"""),
        // Bearer/Authorization 头形态。
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._-]{16,}"""),
        // PEM 私钥块。
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        // 长十六进制/_base64_ 形态 token（≥32 连续字符，排除普通中文/单词）。
        Regex("""\b[A-Fa-f0-9]{32,}\b"""),
        Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b"""),
        // 显式赋值形态（password=xx / apikey:xx / token: xx）。
        Regex("""(?i)\b(?:password|passwd|apikey|api_key|secret|token)\b[=:]\s*\S{6,}"""),
    )

    /** 校验结果：文本可直接入队，或携带面向 UI 的拒绝原因。 */
    sealed interface Verdict {
        data class Accepted(val text: String) : Verdict

        data class Rejected(val reason: String) : Verdict
    }

    /** 校验并规整一条用户消息（trim；不改写内容本身）。 */
    fun sanitize(raw: String): Verdict {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Verdict.Rejected("消息为空")
        }
        if (text.length > MAX_LENGTH) {
            return Verdict.Rejected("消息过长（${text.length} > $MAX_LENGTH 字符）")
        }
        for (pattern in CREDENTIAL_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return Verdict.Rejected("消息包含疑似凭据内容（${
                    pattern.pattern.take(24)
                }…），已拒绝入队")
            }
        }
        return Verdict.Accepted(text)
    }

    /** 日志摘要（脱敏纪律：日志只记长度与 sha256 前 16 hex）。 */
    fun logDigest(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
