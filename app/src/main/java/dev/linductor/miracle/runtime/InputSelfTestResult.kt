package dev.linductor.miracle.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * P2 输入自检结果（native 契约探针 + adapter 会话步骤 JSON 的 Kotlin 投影）。
 * 解析失败显式降级为 ok=false 的错误结果，不静默。
 */
data class InputSelfTestResult(
    val ok: Boolean,
    val stage: String,
    val error: String,
    val probeSteps: List<ProbeStep>,
    val adapterSteps: List<AdapterStep>,
    val close: CloseStats?,
) {
    data class ProbeStep(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val elapsedMs: Double,
    )

    data class AdapterStep(
        val kind: String,
        val ok: Boolean,
        val receipt: String,
        val sideEffect: Boolean,
        val epoch: Long,
        val elapsedMs: Double,
        val error: String,
    )

    data class CloseStats(
        val ok: Boolean,
        val submitted: Long,
        val settled: Long,
        val duplicates: Long,
        val unknowns: Long,
        val late: Long,
        val rejections: Long,
        val violations: Long,
        val hostUnknownCompletions: Long,
        val hostLateCompletions: Long,
        val hostCancelledOps: Long,
        val shutdown: String,
    )

    companion object {
        fun parseProbe(json: String): InputSelfTestResult = try {
            val root = JSONObject(json)
            InputSelfTestResult(
                ok = root.optBoolean("ok", false),
                stage = root.optString("stage", ""),
                error = root.optString("error", ""),
                probeSteps = parseProbeSteps(root.optJSONArray("steps")),
                adapterSteps = emptyList(),
                close = null,
            )
        } catch (_: Exception) {
            InputSelfTestResult(
                ok = false, stage = "parse", error = "探针结果解析失败",
                probeSteps = emptyList(), adapterSteps = emptyList(), close = null,
            )
        }

        fun parseStep(json: String): AdapterStep = try {
            val root = JSONObject(json)
            AdapterStep(
                kind = root.optString("kind", ""),
                ok = root.optBoolean("ok", false),
                receipt = root.optString("receipt", root.optString("error", "")),
                sideEffect = root.optBoolean("side_effect", false),
                epoch = root.optLong("epoch", 0),
                elapsedMs = root.optDouble("ms", 0.0),
                error = root.optString("error", ""),
            )
        } catch (_: Exception) {
            AdapterStep(
                kind = "", ok = false, receipt = "", sideEffect = false,
                epoch = 0, elapsedMs = 0.0, error = "步骤结果解析失败",
            )
        }

        fun parseClose(json: String): CloseStats = try {
            val root = JSONObject(json)
            val bridge = root.optJSONObject("bridge")
            val host = root.optJSONObject("host")
            CloseStats(
                ok = root.optBoolean("ok", false),
                submitted = bridge?.optLong("submitted", 0) ?: 0,
                settled = bridge?.optLong("settled", 0) ?: 0,
                duplicates = bridge?.optLong("duplicates", 0) ?: 0,
                unknowns = bridge?.optLong("unknowns", 0) ?: 0,
                late = bridge?.optLong("late", 0) ?: 0,
                rejections = bridge?.optLong("rejections", 0) ?: 0,
                violations = bridge?.optLong("violations", 0) ?: 0,
                hostUnknownCompletions = host?.optLong("unknown_completions", 0) ?: 0,
                hostLateCompletions = host?.optLong("late_completions", 0) ?: 0,
                hostCancelledOps = host?.optLong("cancelled_ops", 0) ?: 0,
                shutdown = root.optString("shutdown", ""),
            )
        } catch (_: Exception) {
            CloseStats(
                ok = false, submitted = 0, settled = 0, duplicates = 0, unknowns = 0,
                late = 0, rejections = 0, violations = 0, hostUnknownCompletions = 0,
                hostLateCompletions = 0, hostCancelledOps = 0, shutdown = "parse-failed",
            )
        }

        private fun parseProbeSteps(array: JSONArray?): List<ProbeStep> =
            array?.let { steps ->
                (0 until steps.length()).mapNotNull { index ->
                    steps.optJSONObject(index)?.let { step ->
                        ProbeStep(
                            name = step.optString("name", ""),
                            ok = step.optBoolean("ok", false),
                            detail = step.optString("detail", ""),
                            elapsedMs = step.optDouble("ms", 0.0),
                        )
                    }
                }
            } ?: emptyList()
    }
}
