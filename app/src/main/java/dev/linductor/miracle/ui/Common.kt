package dev.linductor.miracle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 共享状态行/键值行（自检卡通用）。 */
@Composable
fun StatusLine(ok: Boolean, title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (ok) "✅ " else "⛔ ") + title,
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        if (detail.isNotEmpty() && detail.length <= 480) {
            Text(detail, style = MaterialTheme.typography.bodySmall)
        } else if (detail.length > 480) {
            Text(detail.take(480) + "…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
