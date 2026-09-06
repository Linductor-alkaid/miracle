package dev.linductor.miracle.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.linductor.miracle.consent.SessionGate
import dev.linductor.miracle.runtime.AgentRuntime
import dev.linductor.miracle.runtime.LoopEventParser

/**
 * 任务台（前端设计 §4 Home）：新目标输入、当前会话卡（相位/步数/动作计数/
 * 停止/接管）、事件时间线。状态全部来自 AgentRuntime 投影（只读）。
 */
@Composable
fun SessionTab(
    viewModel: SessionViewModel,
    onRequestProjection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val startError by viewModel.startError.collectAsStateWithLifecycle()
    var goalInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val gateStatus = gate
        if (gateStatus != null && !gateStatus.satisfied) {
            OnboardingCard(
                gate = gateStatus,
                onRequestProjection = onRequestProjection,
                onOpenSettings = onOpenSettings,
            )
            // 无障碍跳转按钮（其余缺项在 OnboardingCard 内引导）。
            if (gateStatus.missing().contains(SessionGate.Requirement.ACCESSIBILITY)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("去开启无障碍服务")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("新任务", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = sessionState !is AgentRuntime.SessionState.Running,
                    label = { Text("目标（例如：打开设置并调高亮度）") },
                )
                startError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.startGoal(context, goalInput)
                        },
                        enabled = goalInput.isNotBlank() &&
                            sessionState !is AgentRuntime.SessionState.Running &&
                            (gateStatus?.satisfied == true),
                    ) {
                        Text("开始执行")
                    }
                    if (sessionState is AgentRuntime.SessionState.Running) {
                        OutlinedButton(onClick = viewModel::cancelSession) {
                            Text("停止")
                        }
                        OutlinedButton(onClick = viewModel::takeover) {
                            Text("接管")
                        }
                    }
                    if (AgentRuntime.sessionOpen &&
                        sessionState !is AgentRuntime.SessionState.Running
                    ) {
                        OutlinedButton(onClick = viewModel::closeSession) {
                            Text("关闭会话")
                        }
                    }
                }
            }
        }

        when (val state = sessionState) {
            is AgentRuntime.SessionState.Running -> SessionCard(
                title = "运行中 · ${
                    when (state.phase) {
                        LoopEventParser.Phase.Observing -> "观察"
                        LoopEventParser.Phase.Reasoning -> "推理"
                        LoopEventParser.Phase.Acting -> "动作"
                        LoopEventParser.Phase.Unknown -> "进行中"
                    }
                }${if (state.takeover) " · 已接管" else ""}",
                detail = "目标：${state.goal}\n已派发动作 ${state.stepEvents} 次",
                ok = null,
            )

            is AgentRuntime.SessionState.Terminal -> SessionCard(
                title = if (state.ok) "任务完成" else "任务结束（${state.outcome}）",
                detail = "目标：${state.goal}\n${state.summary}",
                ok = state.ok,
            )

            AgentRuntime.SessionState.Idle -> Unit
        }

        if (timeline.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("事件时间线", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        items(timeline.asReversed()) { entry ->
                            Text(
                                "· ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(title: String, detail: String, ok: Boolean?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                (ok?.let { if (it) "✅ " else "⛔ " } ?: "") + title,
                style = MaterialTheme.typography.titleMedium,
                color = when (ok) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.primary
                },
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 设置页（前端设计 §4 Settings）：模型端点/凭据（Keystore）/方言/步数/悬浮窗/披露。 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config = state.config

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("模型服务（OpenAI 兼容）", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = config.endpoint,
                    onValueChange = { value -> viewModel.update { it.copy(endpoint = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("端点（https://…）") },
                )
                OutlinedTextField(
                    value = config.apiPrefix,
                    onValueChange = { value -> viewModel.update { it.copy(apiPrefix = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API 前缀（默认 /v1）") },
                )
                OutlinedTextField(
                    value = config.model,
                    onValueChange = { value -> viewModel.update { it.copy(model = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("模型名") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.update { it.copy(dialect = "responses") }
                    }) {
                        Text(
                            if (config.dialect == "responses") "● Responses v1" else "Responses v1",
                        )
                    }
                    OutlinedButton(onClick = {
                        viewModel.update { it.copy(dialect = "chat") }
                    }) {
                        Text(
                            if (config.dialect == "chat") "● Chat Completions" else "Chat Completions",
                        )
                    }
                }
                OutlinedTextField(
                    value = state.apiKeyInput,
                    onValueChange = viewModel::onApiKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            if (config.hasApiKey) "API key（留空＝保留现有）" else "API key",
                        )
                    },
                )
                OutlinedTextField(
                    value = config.maxSteps.toString(),
                    onValueChange = { value ->
                        val steps = value.toIntOrNull() ?: config.maxSteps
                        viewModel.update { it.copy(maxSteps = steps.coerceIn(1, 128)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("步数上限（1-128）") },
                )
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.saved) {
                    Text("已保存（密钥经 Keystore 加密存储）", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = viewModel::save) { Text("保存配置") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("告知与授权", style = MaterialTheme.typography.titleSmall)
                Text(
                    "首次披露：${if (state.disclosureAccepted) "已确认" else "未确认"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.disclosureAccepted) {
                    Button(onClick = viewModel::acceptDisclosure) {
                        Text("我已知晓并同意（截图出设备 + 触控模拟）")
                    }
                } else {
                    TextButton(onClick = viewModel::resetDisclosure) { Text("重置披露（复验引导）") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("悬浮窗权限", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("去授权") }
                }
                Text(
                    "R3 敏感名单（默认从严）：支付/删除/发送/凭据类目标全量确认；" +
                        "敏感应用内 type 动作确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
