package com.example.giffer2.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gifvision.LogEntry
import com.example.gifvision.LogSeverity

@Composable
fun FfmpegLogPanel(
    logEntries: List<LogEntry>,
    isExpanded: Boolean,
    showWarningBadge: Boolean,
    showErrorBadge: Boolean,
    onExpandedChanged: (Boolean) -> Unit
) {
    if (logEntries.isEmpty() && !isExpanded) {
        return
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FFmpeg Logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (showWarningBadge) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("!")
                    }
                }
                if (showErrorBadge) {
                    Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text("✕")
                    }
                }
                TextButton(onClick = { onExpandedChanged(!isExpanded) }) {
                    Text(if (isExpanded) "Hide" else "Show")
                }
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                LogList(logEntries = logEntries)
            }
        }
    }
}

@Composable
private fun LogList(logEntries: List<LogEntry>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(logEntries.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logEntries.isEmpty()) {
                Text("Logs will appear here once FFmpeg starts processing.")
            }
            logEntries.forEach { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.severity) {
        LogSeverity.INFO -> MaterialTheme.colorScheme.onSurface
        LogSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        LogSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
    Text(
        text = entry.message,
        color = color,
        style = MaterialTheme.typography.bodySmall
    )
}
