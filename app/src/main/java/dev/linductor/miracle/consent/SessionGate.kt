package dev.linductor.miracle.consent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.MiracleAccessibilityService

/**
 * 会话准入门面（P3 计划决策 8；架构 §7 降级矩阵）。
 *
 * 会话启动前置：披露确认（一次性）→ 四项授权（无障碍/投影/悬浮窗/通知）→
 * 模型配置完整性。缺项 fail-closed 并逐项引导，不绕过（RULE-04）。
 */
object SessionGate {

    enum class Requirement {
        DISCLOSURE,
        ACCESSIBILITY,
        PROJECTION,
        OVERLAY,
        NOTIFICATION,
        MODEL_CONFIG,
    }

    data class GateStatus(
        val disclosureAccepted: Boolean,
        val accessibilityEnabled: Boolean,
        val projectionBound: Boolean,
        val overlayGranted: Boolean,
        val notificationGranted: Boolean,
        val modelConfigured: Boolean,
    ) {
        val satisfied: Boolean
            get() = disclosureAccepted && accessibilityEnabled && projectionBound &&
                overlayGranted && notificationGranted && modelConfigured

        /** 缺失项（按引导顺序）。 */
        fun missing(): List<Requirement> {
            val items = ArrayList<Requirement>()
            if (!disclosureAccepted) {
                items.add(Requirement.DISCLOSURE)
            }
            if (!accessibilityEnabled) {
                items.add(Requirement.ACCESSIBILITY)
            }
            if (!projectionBound) {
                items.add(Requirement.PROJECTION)
            }
            if (!overlayGranted) {
                items.add(Requirement.OVERLAY)
            }
            if (!notificationGranted) {
                items.add(Requirement.NOTIFICATION)
            }
            if (!modelConfigured) {
                items.add(Requirement.MODEL_CONFIG)
            }
            return items
        }
    }

    fun check(context: Context, modelConfigured: Boolean): GateStatus {
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return GateStatus(
            disclosureAccepted = disclosureAcceptedProvider(context),
            accessibilityEnabled = MiracleAccessibilityService.isConnected(),
            projectionBound = AgentForegroundService.state.value == AgentForegroundService.HostState.Bound,
            overlayGranted = Settings.canDrawOverlays(context),
            notificationGranted = notificationsGranted,
            modelConfigured = modelConfigured,
        )
    }

    /** 披露状态由调用方注入存储实现（测试可替换；默认走 ModelConfigStore）。 */
    @Volatile
    var disclosureAcceptedProvider: (Context) -> Boolean = { context ->
        dev.linductor.miracle.settings.ModelConfigStore(context).disclosureAccepted()
    }
}
