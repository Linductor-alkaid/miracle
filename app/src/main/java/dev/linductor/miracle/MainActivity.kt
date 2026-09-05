package dev.linductor.miracle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.linductor.miracle.ui.SmokeUiState
import dev.linductor.miracle.ui.SmokeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MiracleApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MiracleApp(modifier: Modifier = Modifier, viewModel: SmokeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.runSelfTest()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Miracle", style = MaterialTheme.typography.headlineMedium)
        Text(
            "P0 骨架：mira RuntimeBaseline 自检",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmokeCard(state = state, onRun = viewModel::runSelfTest)
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
            when (val s = state) {
                SmokeUiState.Idle, SmokeUiState.Running -> Row(
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
                    KeyValue("initialize 耗时", "${s.result.initMs} ms")
                    KeyValue("submit+wait 耗时", "${s.result.waitMs} ms")
                    KeyValue("结果码", s.result.resultCode)
                    KeyValue("终态", s.result.finalState)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onRun, enabled = state != SmokeUiState.Running) {
                Text("重新自检")
            }
        }
    }
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
            "悬浮球：P3 交付（占位）\n截屏 / 触控链路：P1–P2 交付",
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
