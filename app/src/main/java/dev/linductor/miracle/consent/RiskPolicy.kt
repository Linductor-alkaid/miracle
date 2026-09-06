package dev.linductor.miracle.consent

/**
 * R3 风险策略表（P3 计划"关键实现决策"6；默认从严，总计划 §6 冻结）。
 *
 * 判定输入：会话目标文本 + 输入动作序列 + 前台应用包名。纯函数，便于单测；
 * 变更走决策记录（工具集设计 §4）。会话级同意不覆盖 R3；release_all 永不确认
 * （安全原语，native 侧同样豁免，双保险）。
 */
object RiskPolicy {

    enum class Decision { Allow, RequireConfirmation }

    /** 输入动作（kind 与 Host ABI MIRA_HOST_INPUT_* 对齐：1..7）。 */
    data class InputAction(val kind: Int, val text: String? = null) {
        val isType: Boolean get() = kind == KIND_TYPE
        val isReleaseAll: Boolean get() = kind == KIND_RELEASE_ALL
    }

    const val KIND_TAP = 1
    const val KIND_LONG_PRESS = 2
    const val KIND_SWIPE = 3
    const val KIND_TYPE = 4
    const val KIND_BACK = 5
    const val KIND_HOME = 6
    const val KIND_RELEASE_ALL = 7

    /** 目标命中即全会话 R3（支付/删除/发送/凭据类，中英从严）。 */
    val GOAL_KEYWORDS: List<String> = listOf(
        "支付", "付款", "转账", "汇款", "打款", "退款", "下单", "订单", "购买", "买",
        "删除", "清空", "卸载", "发送", "发消息", "回复", "转发", "分享",
        "密码", "凭据", "口令", "验证码", "登录", "登陆", "sign in", "login",
        "pay", "payment", "transfer", "purchase", "buy", "delete", "remove",
        "uninstall", "send", "reply", "forward", "share", "password",
        "credential", "passcode", "otp",
    )

    /** 敏感前台应用（type 动作升级 R3 的默认名单；设置页可查看，v1 不可编辑）。 */
    val SENSITIVE_APP_MATCHERS: List<String> = listOf(
        "wallet", "bank", "alipay", "wechat", "weixin", "tenpay", "unionpay",
        "paypal", "venmo", "cashapp", "1password", "password", "authenticator",
        "otp", "sms", "mms", "mail", "im", "message",
    )

    /**
     * 策略判定：
     * (a) 目标命中关键词 → 全部非 release_all 动作 R3；
     * (b) type 动作 + 前台应用命中敏感名单 → R3；
     * (c) 其余（tap/long_press/swipe/back/home/type 一般场景）＝R1/R2，会话级同意覆盖。
     */
    fun decide(goal: String, actions: List<InputAction>, foregroundPackage: String?): Decision {
        val goalSensitive = GOAL_KEYWORDS.any { keyword ->
            goal.contains(keyword, ignoreCase = true)
        }
        if (goalSensitive) {
            if (actions.any { !it.isReleaseAll }) {
                return Decision.RequireConfirmation
            }
        }
        val appSensitive = foregroundPackage != null && SENSITIVE_APP_MATCHERS.any { matcher ->
            foregroundPackage.contains(matcher, ignoreCase = true)
        }
        if (appSensitive && actions.any { it.isType }) {
            return Decision.RequireConfirmation
        }
        return Decision.Allow
    }

    /** 事件 JSON（[{"k":1,"x":..}]，HostBridge.dispatchInput 同构）→ 动作列表；非法输入返回 null。 */
    fun parseActions(eventsJson: String): List<InputAction>? {
        return try {
            val array = org.json.JSONArray(eventsJson)
            if (array.length() == 0 || array.length() > 64) {
                return null
            }
            val actions = ArrayList<InputAction>(array.length())
            for (index in 0 until array.length()) {
                val event = array.optJSONObject(index) ?: return null
                val kind = event.optInt("k", 0)
                if (kind < KIND_TAP || kind > KIND_RELEASE_ALL) {
                    return null
                }
                val text = if (event.has("t")) event.optString("t") else null
                actions.add(InputAction(kind, text))
            }
            actions
        } catch (_: org.json.JSONException) {
            null
        }
    }

    /** 动作的 UI 摘要（不含 type 文本内容）。 */
    fun summarize(actions: List<InputAction>): String {
        val names = mapOf(
            KIND_TAP to "tap", KIND_LONG_PRESS to "长按", KIND_SWIPE to "滑动",
            KIND_TYPE to "输入文本", KIND_BACK to "返回", KIND_HOME to "主页",
            KIND_RELEASE_ALL to "释放全部",
        )
        return actions.joinToString("、") { action ->
            names[action.kind] ?: "未知(${action.kind})"
        }
    }
}
