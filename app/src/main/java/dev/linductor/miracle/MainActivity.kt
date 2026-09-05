package dev.linductor.miracle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.DisplayGeometry
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.host.MiracleAccessibilityService
import dev.linductor.miracle.ui.CaptureState
import dev.linductor.miracle.ui.CaptureViewModel
import dev.linductor.miracle.ui.InputState
import dev.linductor.miracle.ui.InputViewModel
import dev.linductor.miracle.ui.SmokeUiState
import dev.linductor.miracle.ui.SmokeViewModel
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MiracleApp(
                        modifier = Modifier.padding(innerPadding),
                        onRequestProjection = {
                            captureViewModel.begin()
                            requestProjectionWithNotification()
                        },
                    )
                }
            }
        }
    }

    /** 先确保通知权限（FGS 常驻通知依赖），再发起系统投影授权。 */
    private fun requestProjectionWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            launchProjectionConsent()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // consent 一次性：结果到达即消费（直接启动宿主服务），不经 ViewModel 等待。
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                AgentForegroundService.start(this, result.resultCode, data)
                captureViewModel.markConsumed()
            } else {
                captureViewModel.markDenied()
            }
        }

    private val captureViewModel: CaptureViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[CaptureViewModel::class.java]
    }
}

@Composable
fun MiracleApp(
    modifier: Modifier = Modifier,
    smokeViewModel: SmokeViewModel = viewModel(),
    captureViewModel: CaptureViewModel = viewModel(),
    inputViewModel: InputViewModel = viewModel(),
    onRequestProjection: () -> Unit = {},
) {
    val smokeState by smokeViewModel.state.collectAsStateWithLifecycle()
    val captureState by captureViewModel.state.collectAsStateWithLifecycle()
    val frames by captureViewModel.frames.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        smokeViewModel.runSelfTest()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Miracle", style = MaterialTheme.typography.headlineMedium)
        Text(
            "P0 骨架自检 · P1 截屏链路自检 · P2 输入链路自检",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmokeCard(state = smokeState, onRun = smokeViewModel::runSelfTest)
        CaptureCard(state = captureState, frames = frames, onStart = onRequestProjection)
        InputCard(viewModel = inputViewModel)
        PlaceholderCard()
    }
}

@Composable
private fun SmokeCard(state: SmokeUiState, onRun: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("P0 · RuntimeBaseline 自检", style = MaterialTheme.typography.titleSmall)
            when (val s = state) {
                is SmokeUiState.Idle, is SmokeUiState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(24.dp)
                            .height(24.dp),
                    )
                    Text("正在执行自检…")
                }

                is SmokeUiState.NativeUnavailable -> StatusLine(
                    ok = false,
                    title = "native 库不可用",
                    detail = s.message,
                )

                is SmokeUiState.Done -> {
                    StatusLine(
                        ok = s.result.ok,
                        title = if (s.result.ok) "自检通过" else "自检失败（${s.result.stage}）",
                        detail = s.result.detail,
                    )
                    KeyValue("mira 版本", s.result.miraVersion)
                    KeyValue("终态", s.result.finalState)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onRun, enabled = state !is SmokeUiState.Running) {
                Text("重新自检")
            }
        }
    }
}

@Composable
private fun CaptureCard(
    state: CaptureState,
    frames: List<HostBridge.CapturedFrame>,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("P1 · 截屏链路自检（MediaProjection → Host ABI → mira）",
                style = MaterialTheme.typography.titleSmall)
            when (val s = state) {
                is CaptureState.Idle -> Text(
                    "将请求屏幕采集授权：授权后前台服务启动，经 Host ABI 完成两次 observe。",
                    style = MaterialTheme.typography.bodySmall,
                )

                is CaptureState.Requesting, is CaptureState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(24.dp)
                            .height(24.dp),
                    )
                    Text(if (state is CaptureState.Requesting) "等待投影授权…" else "正在执行环境自检…")
                }

                is CaptureState.PermissionDenied -> StatusLine(
                    ok = false,
                    title = "投影授权被拒绝",
                    detail = "未获得屏幕采集授权（PermissionDenied，fail-closed）",
                )

                is CaptureState.Failed -> StatusLine(
                    ok = false,
                    title = "自检失败（${s.stage}）",
                    detail = s.message,
                )

                is CaptureState.Done -> {
                    StatusLine(ok = true, title = "环境自检通过", detail = "")
                    s.result.frames.forEachIndexed { index, frame ->
                        KeyValue(
                            "帧 ${index + 1}",
                            "${frame.width}×${frame.height} ${frame.format} epoch=${frame.epoch} %.1fms".format(
                                frame.elapsedMs,
                            ),
                        )
                    }
                    s.result.bridge?.let {
                        KeyValue(
                            "bridge",
                            "提交 ${it.submitted} 结算 ${it.settled} lease释放 ${it.leasesReleased}",
                        )
                        KeyValue(
                            "违规计数",
                            "重复 ${it.duplicates} 未知 ${it.unknowns} 迟到 ${it.late} 违规 ${it.violations}",
                        )
                    }
                    s.result.host?.let {
                        KeyValue("宿主", "未知完成 ${it.unknownCompletions} 迟到 ${it.lateCompletions} 未决lease ${it.outstandingLeases}")
                    }
                    KeyValue("shutdown", s.result.shutdown)
                    KeyValue("mira 版本", s.result.miraVersion)
                }
            }
            if (frames.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    frames.takeLast(2).forEach { frame ->
                        Image(
                            bitmap = frame.toBitmap().asImageBitmap(),
                            contentDescription = "captured frame preview",
                            contentScale = ContentScale.FillHeight,
                            modifier = Modifier.height(140.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onStart,
                enabled = state !is CaptureState.Requesting && state !is CaptureState.Running,
            ) {
                Text(if (state is CaptureState.Done) "再次自检" else "授权屏幕采集并自检")
            }
        }
    }
}

private fun HostBridge.CapturedFrame.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return bitmap
}

@Composable
private fun StatusLine(ok: Boolean, title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (ok) "✅ " else "⛔ ") + title,
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputCard(viewModel: InputViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()
    val tapCount by viewModel.tapCount.collectAsStateWithLifecycle()
    val longPressCount by viewModel.longPressCount.collectAsStateWithLifecycle()
    val backCount by viewModel.backCount.collectAsStateWithLifecycle()
    val typedValue by viewModel.typedValue.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 自检期间消费返回键（back 步骤的副作用断言）；空闲时不拦截导航。
    BackHandler(enabled = state is InputState.Running) {
        viewModel.recordBack()
    }

    // Compose boundsInWindow 是 DecorView 内容坐标，与屏幕原点存在厂商/边到边
    // 差异（真机实测约 +77px）；经 LocalView 屏幕锚定换算为真实屏幕坐标。
    val composeView = androidx.compose.ui.platform.LocalView.current
    val screenAnchor = remember { IntArray(2) }

    fun toNormalized(x: Float, y: Float): Pair<Double, Double> {
        composeView.getLocationOnScreen(screenAnchor)
        val screen = DisplayGeometry.screenBounds(context)
        if (screen.width() <= 0 || screen.height() <= 0) {
            return 0.5 to 0.5
        }
        val nx = ((x + screenAnchor[0]) / screen.width()).coerceIn(0.02f, 0.98f)
        val ny = ((y + screenAnchor[1]) / screen.height()).coerceIn(0.02f, 0.98f)
        return nx.toDouble() to ny.toDouble()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("P2 · 输入链路自检（无障碍 → Host ABI dispatch_input → mira）",
                style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Text(
                if (connected) "无障碍服务：已连接" else "无障碍服务：未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
                if (!connected) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text("去开启")
                    }
                }
            }

            // 靶点：tap/long_press 的落点与副作用计数。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .onGloballyPositioned { coords ->
                        val center = coords.boundsInWindow().center
                        val (x, y) = toNormalized(center.x, center.y)
                        viewModel.updateTapCenter(x, y)
                    }
                    .combinedClickable(
                        onClick = viewModel::recordTap,
                        onLongClick = viewModel::recordLongPress,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "靶点 · 点击 $tapCount · 长按 $longPressCount",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 滑动测试区：swipe 步骤的路径（区内竖直滑动，不滚动页面）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .onGloballyPositioned { coords ->
                        val bounds = coords.boundsInWindow()
                        val x = bounds.center.x
                        val (nx, y1) = toNormalized(x, bounds.bottom - bounds.height * 0.25f)
                        val (_, y2) = toNormalized(x, bounds.top + bounds.height * 0.25f)
                        viewModel.updateSwipeArea(nx, y1, y2)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "滑动测试区（返回键计数 $backCount）",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = typedValue,
                onValueChange = viewModel::onTyped,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val center = coords.boundsInWindow().center
                        val (x, y) = toNormalized(center.x, center.y)
                        viewModel.updateTypeCenter(x, y)
                    },
                singleLine = true,
                label = { Text("文本注入验证（type 步骤写入 miracle）") },
            )

            when (val s = state) {
                is InputState.Idle -> Text(
                    "自检将：契约探针（非法参数/过期 deadline/RELEASE_ALL/中途取消）→ 经 mira 派发 tap/长按/滑动/back/type/home（home 后回到本页查看结果）。",
                    style = MaterialTheme.typography.bodySmall,
                )

                is InputState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(24.dp)
                            .height(24.dp),
                    )
                    Text("正在执行输入自检…")
                }

                is InputState.Failed -> StatusLine(ok = false, title = "自检失败（${s.stage}）", detail = s.message)

                is InputState.Done -> {
                    StatusLine(
                        ok = s.ok,
                        title = if (s.ok) "输入自检通过" else "输入自检未通过（见分项）",
                        detail = if (s.launcherSeen) "" else "home 后未见 launcher 前台",
                    )
                    s.probe.probeSteps.forEach { step ->
                        KeyValue(
                            "探针 · ${step.name}",
                            "${if (step.ok) "✓" else "✗"} ${step.detail}".trim(),
                        )
                    }
                    s.adapterSteps.forEach { step ->
                        val suffix = (if (step.sideEffect) " side=1" else "") +
                            " %.0fms".format(step.elapsedMs)
                        KeyValue(
                            "会话 · ${step.kind}",
                            (if (step.ok) "✓" else "✗") + " " + step.receipt + suffix,
                        )
                    }
                    KeyValue(
                        "UI 断言",
                        "tap=${if (s.uiChecks.tapCounted) "✓" else "✗"} " +
                            "长按=${if (s.uiChecks.longPressCounted) "✓" else "✗"} " +
                            "back=${if (s.uiChecks.backCounted) "✓" else "✗"} " +
                            "type=${if (s.uiChecks.textMatched) "✓" else "✗"} " +
                            "探针tap=${if (s.uiChecks.probeTapCounted) "✓" else "✗"}",
                    )
                    s.close?.let {
                        KeyValue(
                            "bridge",
                            "提交 ${it.submitted} 结算 ${it.settled} 违规 ${it.violations}",
                        )
                        KeyValue(
                            "违规计数",
                            "重复 ${it.duplicates} 未知 ${it.unknowns} 迟到 ${it.late} " +
                                "宿主未知 ${it.hostUnknownCompletions} 宿主迟到 ${it.hostLateCompletions}",
                        )
                        KeyValue("shutdown", it.shutdown)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = viewModel::runSelfTest,
                enabled = connected && state !is InputState.Running,
            ) {
                Text(if (state is InputState.Done) "再次自检" else "运行输入自检")
            }
        }
    }
}

@Composable
private fun PlaceholderCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "闭环 AgentLoop 与模型调用：P3 交付\n悬浮球：P3 交付",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MiracleAppPreview() {
    MaterialTheme {
        MiracleApp(modifier = Modifier.padding(20.dp))
    }
}
