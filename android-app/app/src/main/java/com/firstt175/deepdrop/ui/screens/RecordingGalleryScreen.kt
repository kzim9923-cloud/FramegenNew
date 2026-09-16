package com.firstt175.deepdrop.ui.screens

import android.content.ContentUris
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import java.util.Locale

private data class RecordingItem(val uri: android.net.Uri, val name: String, val size: Long, val durationMs: Long)

@Composable
fun RecordingGalleryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val recordingPrefs = remember { ctx.getSharedPreferences("recording", android.content.Context.MODE_PRIVATE) }
    var sessionRecording by remember { mutableStateOf(recordingPrefs.getBoolean("session_recording", false)) }
    var microphone by remember { mutableStateOf(recordingPrefs.getBoolean("mic", false)) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            microphone = true
            recordingPrefs.edit().putBoolean("mic", true).apply()
        } else {
            microphone = false
            recordingPrefs.edit().putBoolean("mic", false).apply()
        }
    }
    var items by remember { mutableStateOf(loadRecordings(ctx)) }
    var selected by remember { mutableStateOf<RecordingItem?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { items = loadRecordings(ctx) }) { Text("Refresh") }
        }

        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Session Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how the next game session starts. Recording is saved to this gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.FilterChip(
                        selected = !sessionRecording,
                        onClick = {
                            sessionRecording = false
                            recordingPrefs.edit().putBoolean("session_recording", false).apply()
                        },
                        label = { Text("Normal") },
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.FilterChip(
                        selected = sessionRecording,
                        onClick = {
                            sessionRecording = true
                            recordingPrefs.edit().putBoolean("session_recording", true).apply()
                        },
                        label = { Text("Record") },
                        modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.material3.FilterChip(
                    selected = microphone,
                    enabled = sessionRecording,
                    onClick = {
                        if (!microphone) {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                microphone = true
                                recordingPrefs.edit().putBoolean("mic", true).apply()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            microphone = false
                            recordingPrefs.edit().putBoolean("mic", false).apply()
                        }
                    },
                    label = { Text(if (microphone) "Microphone: On" else "Microphone: Off") },
                )
            }
        }

        if (items.isEmpty()) {
            Text("No Deepdrop recordings yet.", modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.uri.toString() }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = item }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Movie, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.name, maxLines = 1)
                            Text("${formatDuration(item.durationMs)} • ${formatBytes(item.size)}")
                        }
                        IconButton(onClick = {
                            ctx.contentResolver.delete(item.uri, null, null)
                            items = loadRecordings(ctx)
                        }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.name) },
            text = {
                Column {
                    Text("Duration: ${formatDuration(item.durationMs)}")
                    Text("Size: ${formatBytes(item.size)}")
                    Spacer(Modifier.height(12.dp))
                    AndroidVideoPlayer(item.uri)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun AndroidVideoPlayer(uri: android.net.Uri) {
    val ctx = LocalContext.current
    val view = remember(uri) { android.widget.VideoView(ctx).apply {
        setVideoURI(uri)
        setOnPreparedListener { it.isLooping = false; start() }
    }}
    androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(220.dp))
}

private fun loadRecordings(ctx: android.content.Context): List<RecordingItem> {
    val out = ArrayList<RecordingItem>()
    val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH)
    ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Deepdrop%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        while (c.moveToNext()) {
            out += RecordingItem(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)), c.getString(name) ?: "Recording.mp4", c.getLong(size), c.getLong(dur))
        }
    }
    return out
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
private fun formatBytes(v: Long): String = when {
    v >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", v / 1024f / 1024f)
    v >= 1024L -> String.format(Locale.US, "%.1f KB", v / 1024f)
    else -> "$v B"
}
