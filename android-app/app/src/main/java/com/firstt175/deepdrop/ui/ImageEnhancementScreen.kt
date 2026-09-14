package com.firstt175.deepdrop.ui

import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.prefs.ImageEnhancementBackend
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.session.NativeBridge
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader

@Composable
fun ImageEnhancementScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val initial = remember { prefs.load() }
    var enabled by remember { mutableStateOf(initial.imageEnhancementEnabled) }
    var backend by remember { mutableStateOf(initial.imageEnhancementBackend) }
    var strength by remember { mutableFloatStateOf(initial.imageEnhancementStrength) }
    var contrast by remember { mutableFloatStateOf(initial.imageEnhancementContrast) }
    var saturation by remember { mutableFloatStateOf(initial.imageEnhancementSaturation) }

    fun applyLive() {
        prefs.setImageEnhancementEnabled(enabled)
        prefs.setImageEnhancementBackend(backend)
        prefs.setImageEnhancementStrength(strength)
        prefs.setImageEnhancementContrast(contrast)
        prefs.setImageEnhancementSaturation(saturation)
        runCatching {
            NativeBridge.setImageEnhancement(
                enabled, backend.ordinal, strength, contrast, saturation
            )
        }
    }

    LaunchedEffect(Unit) { applyLive() }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LsfgTopBar(title = "Image Enhancement", onBack = { nav.popBackStack() })

        LsfgCard {
            Row {
                IconBadge(icon = Icons.Filled.AutoAwesome, size = 36.dp)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("LIVE POST PROCESS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The preview below changes while you drag. During a game, the same values are applied at the final presentation boundary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val preview = remember {
                BitmapFactory.decodeStream(ctx.assets.open("image/1.png"))
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(230.dp)
                    .background(Color.Black),
                factory = { ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(preview)
                }},
                update = { view ->
                    view.setImageBitmap(preview)
                    if (!enabled) {
                        view.colorFilter = null
                    } else {
                        val m = ColorMatrix()
                        val sat = saturation
                        m.setSaturation(sat)
                        val c = contrast
                        val t = (1f - c) * 128f
                        val contrastMatrix = ColorMatrix(floatArrayOf(
                            c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f
                        ))
                        m.postConcat(contrastMatrix)
                        view.colorFilter = ColorMatrixColorFilter(m)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) "LIVE • ${backend.name}" else "LIVE PREVIEW OFF",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LsfgCard {
            SectionHeader(eyebrow = "POST PROCESS", title = null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = enabled,
                    onClick = { enabled = !enabled; applyLive() },
                    label = { Text(if (enabled) "Enabled" else "Disabled") },
                    leadingIcon = { IconBadge(icon = Icons.Filled.AutoAwesome, size = 20.dp) }
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("Backend", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = backend == ImageEnhancementBackend.CPU,
                    onClick = { backend = ImageEnhancementBackend.CPU; applyLive() },
                    label = { Text("CPU") }, modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = backend == ImageEnhancementBackend.GPU,
                    onClick = { backend = ImageEnhancementBackend.GPU; applyLive() },
                    label = { Text("GPU / Vulkan") }, modifier = Modifier.weight(1f)
                )
            }

            Text("Sharpness  ${(strength * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = strength, onValueChange = { strength = it; applyLive() }, valueRange = 0f..1f)

            Text("Contrast  ${"%.2f".format(contrast)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = contrast, onValueChange = { contrast = it; applyLive() }, valueRange = 0.5f..1.5f)

            Text("Saturation  ${"%.2f".format(saturation)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = saturation, onValueChange = { saturation = it; applyLive() }, valueRange = 0f..2f)

            Text(
                "CPU mode performs real per-pixel sharpening/contrast/saturation on the displayed frame. GPU/Vulkan mode keeps the zero-copy GPU presentation path and never introduces a CPU readback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
