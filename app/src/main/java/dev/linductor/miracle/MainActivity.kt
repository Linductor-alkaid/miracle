package dev.linductor.miracle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.linductor.miracle.host.AgentForegroundService
import dev.linductor.miracle.host.HostBridge
import dev.linductor.miracle.ui.CaptureState
import dev.linductor.miracle.ui.CaptureViewModel
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
                        onRequestProjection = { requestProjectionWithNotification() },
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
            "P0 骨架自检 · P1 截屏链路自检",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmokeCard(state = smokeState, onRun = smokeViewModel::runSelfTest)
        CaptureCard(state = captureState, frames = frames, onStart = onRequestProjection)
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

@Composable
private fun PlaceholderCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "触控/输入链路：P2 交付\n悬浮球：P3 交付",
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
