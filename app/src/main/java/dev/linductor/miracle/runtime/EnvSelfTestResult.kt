package dev.linductor.miracle.runtime

import org.json.JSONObject

/**
 * P1 环境自检结果（runtime_glue.cpp 组装 JSON 的 Kotlin 投影）。
 * 解析失败显式降级为 ok=false 的错误结果，不静默。
 */
data class EnvSelfTestResult(
    val ok: Boolean,
    val stage: String,
    val error: String,
    val frames: List<FrameResult>,
    val bridge: BridgeStats?,
    val host: HostStats?,
    val shutdown: String,
    val miraVersion: String,
) {
    data class FrameResult(
        val ok: Boolean,
        val width: Int,
        val height: Int,
        val format: String,
        val epoch: Long,
        val artifact: String,
        val elapsedMs: Double,
        val error: String,
    )

    data class BridgeStats(
        val submitted: Long,
        val settled: Long,
        val leasesReleased: Long,
        val duplicates: Long,
        val unknowns: Long,
        val late: Long,
        val rejections: Long,
        val violations: Long,
    )

    data class HostStats(
        val unknownCompletions: Long,
        val lateCompletions: Long,
        val cancelledOps: Long,
        val outstandingLeases: Long,
    )

    companion object {
        fun parse(json: String): EnvSelfTestResult = try {
            val root = JSONObject(json)
            val frames = root.optJSONArray("frames")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val frame = array.optJSONObject(index) ?: return@mapNotNull null
                    FrameResult(
                        ok = frame.optBoolean("ok", false),
                        width = frame.optInt("w", 0),
                        height = frame.optInt("h", 0),
                        format = frame.optString("format", ""),
                        epoch = frame.optLong("epoch", 0),
                        artifact = frame.optString("artifact", ""),
                        elapsedMs = frame.optDouble("ms", 0.0),
                        error = frame.optString("error", ""),
                    )
                }
            } ?: emptyList()
            val bridge = root.optJSONObject("bridge")?.let {
                BridgeStats(
                    submitted = it.optLong("submitted", 0),
                    settled = it.optLong("settled", 0),
                    leasesReleased = it.optLong("leases_released", 0),
                    duplicates = it.optLong("duplicates", 0),
                    unknowns = it.optLong("unknowns", 0),
                    late = it.optLong("late", 0),
                    rejections = it.optLong("rejections", 0),
                    violations = it.optLong("violations", 0),
                )
            }
            val host = root.optJSONObject("host")?.let {
                HostStats(
                    unknownCompletions = it.optLong("unknown_completions", 0),
                    lateCompletions = it.optLong("late_completions", 0),
                    cancelledOps = it.optLong("cancelled_ops", 0),
                    outstandingLeases = it.optLong("outstanding_leases", 0),
                )
            }
            EnvSelfTestResult(
                ok = root.optBoolean("ok", false),
                stage = root.optString("stage", ""),
                error = root.optString("error", ""),
                frames = frames,
                bridge = bridge,
                host = host,
                shutdown = root.optString("shutdown", ""),
                miraVersion = root.optString("mira_version", ""),
            )
        } catch (_: Exception) {
            EnvSelfTestResult(
                ok = false, stage = "parse", error = "自检结果解析失败", frames = emptyList(),
                bridge = null, host = null, shutdown = "", miraVersion = "",
            )
        }
    }
}
