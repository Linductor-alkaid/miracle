package dev.linductor.miracle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.linductor.miracle.consent.RiskPolicy
import dev.linductor.miracle.consent.SessionGate
import dev.linductor.miracle.runtime.AgentRuntime

/**
 * R3 确认对话框宿主（前端设计 §4 ConfirmationHost；DEC-004：宿主是确认权威）。
 *
 * 挑战绑定动作摘要与 nonce，单次有效，60s 到期（native ConfirmationAuthority
 * consume 校验；此处仅呈现与回流，不自行判定授权）。倒计时由 expiresAt 驱动。
 */
@Composable
fun ConfirmationHost(viewModel: SessionViewModel) {
    val request by viewModel.confirmation.collectAsStateWithLifecycle()
    val current = request ?: return
    val remainSeconds = ((current.expiresAt * 1000 - System.currentTimeMillis()) / 1000)
        .coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = { viewModel.resolveConfirmation(false) },
        title = { Text("动作确认（R3）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "即将执行的输入动作：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    current.summary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(current.risk, style = MaterialTheme.typography.bodySmall)
                Text(
                    "确认仅授权该单次动作（摘要 ${current.digest.take(8)}…），" +
                        "${remainSeconds}s 后自动失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.resolveConfirmation(true) }) {
                Text("允许一次")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { viewModel.resolveConfirmation(false) }) {
                Text("拒绝")
            }
        },
    )
}

/** 会话准入引导卡（缺项逐项引导，fail-closed；架构 §7 降级矩阵）。 */
@Composable
fun OnboardingCard(
    gate: SessionGate.GateStatus,
    onRequestProjection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("会话准入（同意与告知）", style = MaterialTheme.typography.titleSmall)
            Text(
                "闭环会话将把屏幕截图发送至你配置的模型服务，并模拟触控执行动作。" +
                    "高风险动作（支付/删除/发送/凭据类）会逐一弹窗确认；悬浮球长按或" +
                    "通知按钮可随时接管。",
                style = MaterialTheme.typography.bodySmall,
            )
            val items = gate.missing()
            if (items.isEmpty()) {
                Text("✅ 全部就绪", style = MaterialTheme.typography.bodyMedium)
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            describe(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        when (item) {
                            SessionGate.Requirement.DISCLOSURE,
                            SessionGate.Requirement.MODEL_CONFIG,
                            ->
                                TextButton(onClick = onOpenSettings) { Text("去设置") }

                            SessionGate.Requirement.ACCESSIBILITY -> Unit // 系统设置页跳转由宿主提供
                            SessionGate.Requirement.PROJECTION -> TextButton(
                                onClick = onRequestProjection,
                            ) { Text("授权投影") }

                            else -> TextButton(onClick = onOpenSettings) { Text("去设置") }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(item: SessionGate.Requirement): String = when (item) {
    SessionGate.Requirement.DISCLOSURE -> "未确认首次披露"
    SessionGate.Requirement.ACCESSIBILITY -> "无障碍服务未开启"
    SessionGate.Requirement.PROJECTION -> "屏幕采集未授权"
    SessionGate.Requirement.OVERLAY -> "悬浮窗未授权"
    SessionGate.Requirement.NOTIFICATION -> "通知权限未授予"
    SessionGate.Requirement.MODEL_CONFIG -> "模型配置不完整"
}
