package com.firstt175.deepdrop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import com.firstt175.deepdrop.session.CrashReporter
import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar

/**
 * In-app-only diagnostics viewer. Reads the crash report + rolling
 * `lsfg.log` straight from app-private storage and renders them as plain
 * text on screen. No share/export intent, no network access — everything
 * shown here stays on the device unless the user manually copies it
 * themselves via the OS clipboard.
 */
@Composable
fun LogViewerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var crashText by remember { mutableStateOf(readCrash(ctx)) }
    var logText by remember { mutableStateOf(readLog(ctx)) }
    var loggingEnabled by remember { mutableStateOf(LsfgLog.isEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        LsfgTopBar(
            title = "Diagnostic Log",
            onBack = { nav.popBackStack() },
        )

        LsfgCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Logging", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Off skips writing detail/warning lines to save background work — crash-level errors are always kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = loggingEnabled,
                    onCheckedChange = {
                        loggingEnabled = it
                        LsfgLog.setEnabled(it)
                    },
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = {
                crashText = readCrash(ctx)
                logText = readLog(ctx)
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Refresh")
            }
            TextButton(onClick = {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LSFG log", crashText + "\n\n" + logText))
                Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy")
            }
            TextButton(onClick = {
                CrashReporter.clearPendingCrash(ctx)
                crashText = readCrash(ctx)
            }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Clear crash")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (crashText.isNotBlank()) {
                LsfgCard {
                    Text(
                        text = "Last crash",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = crashText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LsfgCard {
                Text(
                    text = "Log",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = logText.ifBlank { "No log entries yet." },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun readCrash(ctx: Context): String =
    runCatching { CrashReporter.readCrashSummary(ctx) }.getOrDefault("")

private fun readLog(ctx: Context, maxBytes: Int = 128 * 1024): String = runCatching {
    val f = CrashReporter.logFile(ctx)
    if (!f.exists()) return@runCatching ""
    val bytes = f.readBytes()
    val start = (bytes.size - maxBytes).coerceAtLeast(0)
    String(bytes, start, bytes.size - start, Charsets.UTF_8)
}.getOrDefault("")
