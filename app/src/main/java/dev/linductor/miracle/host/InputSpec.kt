package dev.linductor.miracle.host

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Host ABI v1 输入契约的 Kotlin 侧常量与事件投影（镜像 mira
 * `adapters/android/host_abi.h`，仅本仓库宿主层使用）。
 *
 * 本文件不依赖 Android 框架类，便于 JVM 单测覆盖解析与校验。
 */
object HostAbiInput {

    // MiraHostStatus（值冻结，见 host_abi.h）
    const val OK = 0
    const val ERR_UNAVAILABLE = 3
    const val ERR_PERMISSION_DENIED = 4
    const val ERR_CANCELLED = 5
    const val ERR_DEADLINE_EXCEEDED = 6
    const val ERR_EXECUTION_UNCERTAIN = 10
    const val ERR_CAPACITY = 11

    // MiraHostInputKind
    const val KIND_TAP = 1
    const val KIND_LONG_PRESS = 2
    const val KIND_SWIPE = 3
    const val KIND_TYPE = 4
    const val KIND_BACK = 5
    const val KIND_HOME = 6
    const val KIND_RELEASE_ALL = 7

    // MiraHostInputReceipt
    const val RECEIPT_DISPATCHED = 1
    const val RECEIPT_COMPLETED = 2
    const val RECEIPT_REJECTED = 3
    const val RECEIPT_UNKNOWN = 4

    const val MAX_TEXT_BYTES = 4096

    /** 单个输入事件（native 组装紧凑 JSON 的投影）。坐标为 [0,1] 规范值。 */
    data class InputEventSpec(
        val kind: Int,
        val x: Double,
        val y: Double,
        val x2: Double,
        val y2: Double,
        val durationMs: Long,
        val text: String,
    )

    /**
     * 解析 native 事件 JSON：`[{"k":1,"x":..,"y":..,"x2":..,"y2":..,"d":..,"t":..}]`。
     * native 已按 ABI 校验过参数；此处防御式再校验，任何非法输入返回 null（fail-closed）。
     */
    fun parseEvents(json: String): List<InputEventSpec>? {
        val events: MutableList<InputEventSpec> = ArrayList()
        try {
            val array = JSONArray(json)
            if (array.length() == 0) {
                return null
            }
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return null
                val event = InputEventSpec(
                    kind = item.optInt("k", 0),
                    x = item.optDouble("x", Double.NaN),
                    y = item.optDouble("y", Double.NaN),
                    x2 = item.optDouble("x2", Double.NaN),
                    y2 = item.optDouble("y2", Double.NaN),
                    durationMs = item.optLong("d", 0),
                    text = item.optString("t", ""),
                )
                if (!isEventValid(event)) {
                    return null
                }
                events.add(event)
            }
        } catch (_: Exception) {
            return null
        }
        return events
    }

    /** ABI 侧参数校验的 Kotlin 镜像（native 为权威，此处为防御层）。 */
    fun isEventValid(event: InputEventSpec): Boolean {
        if (event.durationMs < 0 || event.durationMs > 60_000) {
            return false
        }
        return when (event.kind) {
            KIND_TAP, KIND_LONG_PRESS -> inUnit(event.x) && inUnit(event.y)
            KIND_SWIPE -> inUnit(event.x) && inUnit(event.y) &&
                inUnit(event.x2) && inUnit(event.y2)
            KIND_TYPE -> event.text.isNotEmpty() &&
                event.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES
            KIND_BACK, KIND_HOME, KIND_RELEASE_ALL -> true
            else -> false
        }
    }

    private fun inUnit(value: Double): Boolean =
        !value.isNaN() && !value.isInfinite() && value >= 0.0 && value <= 1.0

    /**
     * [0,1] 规范坐标 → 屏幕像素（四舍五入，clamp 屏内）。P2 决策 4 的映射函数，
     * 供 [InputDispatcher] 手势合成使用。
     */
    fun toPixel(value: Double, extent: Int): Int =
        (value * extent).roundToInt().coerceIn(0, extent - 1)

    /** 完成回执（status 为 MiraHostStatus，receipt 为 MiraHostInputReceipt）。 */
    data class InputCompletion(
        val status: Int,
        val receipt: Int,
        val sideEffect: Boolean,
    )

    fun completionToJson(completion: InputCompletion): String = JSONObject().apply {
        put("status", completion.status)
        put("receipt", completion.receipt)
        put("side", if (completion.sideEffect) 1 else 0)
    }.toString()
}
