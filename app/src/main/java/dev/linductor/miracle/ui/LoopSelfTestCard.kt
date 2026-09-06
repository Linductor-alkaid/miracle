package dev.linductor.miracle.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.DisplayGeometry
import dev.linductor.miracle.runtime.AgentRuntime
import org.json.JSONObject

/**
 * P3 自检卡：模型连通性（真实端点）+ 闭环干跑（脚本化决策，真实 observe/act）。
 *
 * 干跑前提：P1 投影已授权（observe 需要）+ P2 无障碍已开启（act 需要）。
 * 靶点为自身 UI（tap 副作用计数断言），与 P2 自检同构；R3 场景目标含"发送"
 * 触发策略表从严路径（确认弹窗经 ConfirmationHost 呈现）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoopSelfTestCard(
    viewModel: LoopSelfTestViewModel,
    onRequestProjection: () -> Unit,
) {
    val context = LocalContext.current
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val dryRun by viewModel.dryRun.collectAsStateWithLifecycle()
    val tapCount by viewModel.tapCount.collectAsStateWithLifecycle()
    val composeView = LocalView.current
    val screenAnchor = androidx.compose.runtime.remember { IntArray(2) }
    val hostState by AgentForegroundService.state.collectAsStateWithLifecycle()
    val projectionBound = hostState == AgentForegroundService.HostState.Bound

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "P3 · 闭环自检（AgentLoop：observe → decide → act → verify）",
                style = MaterialTheme.typography.titleSmall,
            )

            // 干跑靶点（点击计数＝副作用断言；坐标供脚本决策使用）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .onGloballyPositioned { coords ->
                        val center = coords.boundsInWindow().center
                        composeView.getLocationOnScreen(screenAnchor)
                        val screen = DisplayGeometry.screenBounds(context)
                        if (screen.width() > 0 && screen.height() > 0) {
                            viewModel.tapTarget =
                                (((center.x + screenAnchor[0]) / screen.width())
                                    .coerceIn(0.05f, 0.95f)).toDouble() to
                                    (((center.y + screenAnchor[1]) / screen.height())
                                        .coerceIn(0.05f, 0.95f)).toDouble()
                        }
                    }
                    .combinedClickable(
                        onClick = viewModel::recordTap,
                        onLongClick = viewModel::recordTap,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "闭环靶点 · 点击 $tapCount",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (val state = connectivity) {
                is LoopSelfTestViewModel.ConnectivityState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Text("正在连接模型服务…")
                }

                is LoopSelfTestViewModel.ConnectivityState.Done -> {
                    val parsed = JSONObject(state.json)
                    StatusLine(
                        ok = parsed.optBoolean("ok"),
                        title = if (parsed.optBoolean("ok")) "模型连通性通过" else "模型连通性未通过",
                        detail = state.json,
                    )
                }

                LoopSelfTestViewModel.ConnectivityState.Idle -> Text(
                    "连通性自检：向所配置端点发送一条文本-only 决策请求（图像路径见台账 MIR-20260906-007）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (val state = dryRun) {
                is LoopSelfTestViewModel.DryRunState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Text("干跑进行中（${state.scenario}）…")
                }

                is LoopSelfTestViewModel.DryRunState.Done -> StatusLine(
                    ok = state.ok,
                    title = "干跑 · ${state.scenario} ${if (state.ok) "通过" else "未通过"}",
                    detail = state.detail,
                )

                LoopSelfTestViewModel.DryRunState.Idle -> Text(
                    "干跑：脚本化决策经真实截屏/输入链路驱动 mira AgentLoop" +
                        "（需 P1 投影 + P2 无障碍就绪）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runConnectivity(context) },
                    enabled = connectivity !is LoopSelfTestViewModel.ConnectivityState.Running,
                ) {
                    Text("连通性自检")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runDryRun(context, "complete") },
                    enabled = dryRun !is LoopSelfTestViewModel.DryRunState.Running && projectionBound,
                ) {
                    Text("① 完整闭环")
                }
                OutlinedButton(
                    onClick = { viewModel.runDryRun(context, "max_steps") },
                    enabled = dryRun !is LoopSelfTestViewModel.DryRunState.Running && projectionBound,
                ) {
                    Text("② 步数上限")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runDryRun(context, "cancel") },
                    enabled = dryRun !is LoopSelfTestViewModel.DryRunState.Running && projectionBound,
                ) {
                    Text("③ 中途取消")
                }
                OutlinedButton(
                    onClick = { viewModel.runDryRun(context, "r3") },
                    enabled = dryRun !is LoopSelfTestViewModel.DryRunState.Running && projectionBound,
                ) {
                    Text("④ R3 确认")
                }
            }
            if (!projectionBound) {
                OutlinedButton(onClick = onRequestProjection) {
                    Text("干跑需先授权屏幕采集（点击授权）")
                }
            }
        }
    }
}
