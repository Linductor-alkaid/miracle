package dev.linductor.miracle.runtime

/**
 * native loop 事件/结果 JSON 的 Kotlin 投影（纯解析，单测覆盖）。
 *
 * 来源：libmiracle_host 的 onLoopEvent（{"kind":..,"payload":{..}}）与
 * loopClose 的汇总。UI/悬浮球只消费该投影（架构 §5）。
 */
object LoopEventParser {

    /** 粗相位（宿主信号驱动：observing/reasoning/acting；决策 4）。 */
    enum class Phase { Observing, Reasoning, Acting, Unknown }

    sealed interface LoopEvent {
        data class PhaseEvent(val phase: Phase) : LoopEvent

        data class SessionEvent(val state: String) : LoopEvent

        data class LoopResultEvent(val result: LoopResult) : LoopEvent

        data class ConfirmationRequestEvent(val request: ConfirmationData) : LoopEvent

        data class ConfirmationSettledEvent(val challenge: String, val outcome: String) : LoopEvent
    }

    data class LoopStep(
        val step: Int,
        val phase: String,
        val summary: String,
        val verified: Boolean,
        val note: String,
    )

    /** 干跑传输断言投影（scripted transport 记录的请求标记；P3h-05 取证）。 */
    data class TransportStats(
        val requests: Long,
        val sawFollowup: Boolean,
        val sawToolResult: Boolean,
    )

    data class LoopResult(
        val outcome: String,
        val summary: String,
        val stepsCount: Int,
        val recoveries: Int,
        val repairs: Int,
        val events: Long,
        val steps: List<LoopStep>,
        val miraVersion: String,
        val transport: TransportStats?,
        val userMessagesInjected: Long,
        val toolEvents: Long,
    ) {
        val completed: Boolean get() = outcome == "Completed"
        val cancelled: Boolean get() = outcome == "Cancelled"
    }

    /** DEC-016 会话对话条目（build_conversation_view 投影；P3h-04）。 */
    data class ConversationEntry(
        val kind: String, // "user_message" | "loop_outcome"
        val text: String,
        val atMs: Long,
    )

    /** DEC-004 确认挑战（digest/nonce 绑定、单次有效、60s 到期）。 */
    data class ConfirmationData(
        val challenge: String,
        val nonce: String,
        val digest: String,
        val summary: String,
        val risk: String,
        val expiresAt: Long,
        val correlation: Long,
        val lifetimeMs: Long,
    )

    fun parsePhase(raw: String): Phase = when (raw) {
        "observing" -> Phase.Observing
        "reasoning" -> Phase.Reasoning
        "acting" -> Phase.Acting
        else -> Phase.Unknown
    }

    /** 解析 onLoopEvent 的包装 JSON；无法识别时返回 null（调用方丢弃，不崩溃）。 */
    fun parse(wrappedJson: String): LoopEvent? {
        return try {
            val root = org.json.JSONObject(wrappedJson)
            val kind = root.optString("kind")
            val payload = root.optJSONObject("payload") ?: org.json.JSONObject()
            when (kind) {
                "phase" -> LoopEvent.PhaseEvent(parsePhase(payload.optString("phase")))
                "session" -> LoopEvent.SessionEvent(payload.optString("state"))
                "result" -> LoopEvent.LoopResultEvent(parseResult(payload))
                "confirmation_request" ->
                    LoopEvent.ConfirmationRequestEvent(parseConfirmation(payload))
                "confirmation_settled" -> LoopEvent.ConfirmationSettledEvent(
                    payload.optString("challenge"),
                    payload.optString("outcome"),
                )
                else -> null
            }
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun parseResult(payload: org.json.JSONObject): LoopResult {
        val steps = ArrayList<LoopStep>()
        val array = payload.optJSONArray("steps") ?: org.json.JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            steps.add(
                LoopStep(
                    step = item.optInt("step"),
                    phase = item.optString("phase"),
                    summary = item.optString("summary"),
                    verified = item.optBoolean("verified"),
                    note = item.optString("note"),
                ),
            )
        }
        val transportJson = payload.optJSONObject("transport")
        val transport = if (transportJson != null) {
            TransportStats(
                requests = transportJson.optLong("requests"),
                sawFollowup = transportJson.optBoolean("saw_followup"),
                sawToolResult = transportJson.optBoolean("saw_tool_result"),
            )
        } else {
            null
        }
        return LoopResult(
            outcome = payload.optString("outcome", "Failed"),
            summary = payload.optString("summary"),
            stepsCount = payload.optInt("steps_count", steps.size),
            recoveries = payload.optInt("recoveries"),
            repairs = payload.optInt("repairs"),
            events = payload.optLong("events"),
            steps = steps,
            miraVersion = payload.optString("mira_version"),
            transport = transport,
            userMessagesInjected = payload.optLong("user_messages_injected"),
            toolEvents = payload.optLong("tool_events"),
        )
    }

    /**
     * 解析 loopConversation 的投影 JSON（P3h-04）。失败返回空表（会话未开启等
     * 非错误态由调用方经 ok 字段区分；此处对解析失败按空表降级）。
     */
    fun parseConversation(conversationJson: String): List<ConversationEntry> {
        return try {
            val root = org.json.JSONObject(conversationJson)
            if (!root.optBoolean("ok")) {
                return emptyList()
            }
            val entries = ArrayList<ConversationEntry>()
            val array = root.optJSONArray("entries") ?: org.json.JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                entries.add(
                    ConversationEntry(
                        kind = item.optString("kind"),
                        text = item.optString("text"),
                        atMs = item.optLong("at_ms"),
                    ),
                )
            }
            entries
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun parseConfirmation(payload: org.json.JSONObject): ConfirmationData {
        return ConfirmationData(
            challenge = payload.optString("challenge"),
            nonce = payload.optString("nonce"),
            digest = payload.optString("digest"),
            summary = payload.optString("summary"),
            risk = payload.optString("risk"),
            expiresAt = payload.optLong("expires_at"),
            correlation = payload.optLong("correlation"),
            lifetimeMs = payload.optLong("lifetime_ms", 60_000),
        )
    }

    /** loopClose 汇总解析（自检结果呈现）。 */
    fun parseCloseSummary(closeJson: String): Triple<Boolean, String, String?> {
        return try {
            val root = org.json.JSONObject(closeJson)
            Triple(
                root.optBoolean("ok"),
                root.optString("shutdown", "Unknown"),
                if (root.isNull("last_result")) null else root.optJSONObject("last_result")?.toString(),
            )
        } catch (_: org.json.JSONException) {
            Triple(false, "ParseError", null)
        }
    }
}
