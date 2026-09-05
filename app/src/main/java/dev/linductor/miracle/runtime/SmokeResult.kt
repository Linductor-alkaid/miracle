package dev.linductor.miracle.runtime

import org.json.JSONObject

/**
 * native 自检结果（runtime_glue.cpp 组装的 JSON 的 Kotlin 投影）。
 * 字段语义与 bridge 侧一一对应；解析失败转为 [ok]=false 的显式错误，不静默。
 */
data class SmokeResult(
    val ok: Boolean,
    val stage: String,
    val detail: String,
    val initMs: Long,
    val waitMs: Long,
    val resultCode: String,
    val taskTerminal: Boolean,
    val finalState: String,
    val miraVersion: String,
) {
    companion object {
        fun parse(json: String): SmokeResult = try {
            val root = JSONObject(json)
            SmokeResult(
                ok = root.getBoolean("ok"),
                stage = root.optString("stage", ""),
                detail = root.optString("detail", ""),
                initMs = root.optLong("init_ms", -1),
                waitMs = root.optLong("wait_ms", -1),
                resultCode = root.optString("result_code", ""),
                taskTerminal = root.optBoolean("task_terminal", false),
                finalState = root.optString("final_state", ""),
                miraVersion = root.optString("mira_version", ""),
            )
        } catch (_: Exception) {
            SmokeResult(
                ok = false,
                stage = "parse",
                detail = "自检结果解析失败",
                initMs = -1,
                waitMs = -1,
                resultCode = "",
                taskTerminal = false,
                finalState = "",
                miraVersion = "",
            )
        }
    }
}
