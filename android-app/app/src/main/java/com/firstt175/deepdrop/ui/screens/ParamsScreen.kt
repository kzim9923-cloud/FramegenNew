package com.firstt175.deepdrop.ui.screens
import com.firstt175.deepdrop.ui.produceConfigState
import com.firstt175.deepdrop.ui.refreshConfigState

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.CaptureSource
import com.firstt175.deepdrop.prefs.DrawerEdge
import com.firstt175.deepdrop.prefs.FrameTransferMode
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.PresentMode
import com.firstt175.deepdrop.session.NativeBridge
import com.firstt175.deepdrop.session.diagnostics.SoundTunerController
import com.firstt175.deepdrop.ui.components.CollapsibleSection
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.components.ToggleRow
import com.firstt175.deepdrop.ui.components.ValueSlider

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — consolidated screen
// ----------------------------------------------------------------------------------------

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — embedded inline in the Settings screen, not a
// separate destination. [nav] is only used to jump to the DLL picker / Legal screen.
// ----------------------------------------------------------------------------------------

// ----------------------------------------------------------------------------------------
// Overlay & Display — new screen consolidating overlay handle, HUD, capture mode
// ----------------------------------------------------------------------------------------

@Composable
fun OverlayDisplayScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_overlay_display),
            onBack = { nav.popBackStack() },
        )

        // ---- Drawer handle -----------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_handle), title = null)
            Spacer(Modifier.height(4.dp))
            DrawerEdgeSelector(
                selected = state.drawerEdge,
                onSelected = {
                    prefs.setDrawerEdge(it)
                    refreshConfigState(prefs)
                    com.firstt175.deepdrop.session.service.LsfgForegroundService.updateDrawerEdge(it)
                },
            )
        }

        // Functional preview: one composited layer, matching the real overlay:
        // game image + HUD cluster + drawer handle use the same edge/position rules.
        LsfgCard {
            SectionHeader(eyebrow = "LIVE OVERLAY PREVIEW", title = null)
            Spacer(Modifier.height(8.dp))

            val hudPreview = remember {
                runCatching { BitmapFactory.decodeStream(ctx.assets.open("hud/1.png")) }.getOrNull()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(android.graphics.Color.BLACK)
                            setImageBitmap(hudPreview)
                        }
                    },
                    update = { it.setImageBitmap(hudPreview) },
                )

                // Same single HUD cluster model as OverlayManager: one vertical unit.
                if (state.fpsCounterEnabled || state.frameGraphEnabled ||
                    state.cpuStatEnabled || state.gpuStatEnabled || state.ramStatEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .padding(
                                start = (state.hudPositionX * 100).dp,
                                top = (state.hudPositionY * 100).dp,
                            )
                    ) {
                        if (state.fpsCounterEnabled) {
                            Text(
                                "FPS 120/240   8.0ms   1.0ms",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                        if (state.frameGraphEnabled) {
                            androidx.compose.foundation.Canvas(Modifier.width(110.dp).height(40.dp).padding(4.dp)) {
                                val points = listOf(0.08f, 0.2f, 0.15f, 0.34f, 0.22f, 0.52f, 0.38f, 0.68f, 0.48f, 0.6f)
                                val step = size.width / (points.size - 1)
                                for (i in 0 until points.lastIndex) {
                                    drawLine(
                                        color = androidx.compose.ui.graphics.Color.White,
                                        start = androidx.compose.ui.geometry.Offset(i * step, size.height * (1f - points[i])),
                                        end = androidx.compose.ui.geometry.Offset((i + 1) * step, size.height * (1f - points[i + 1])),
                                        strokeWidth = 2f,
                                    )
                                }
                            }
                        }
                        if (state.cpuStatEnabled || state.gpuStatEnabled || state.ramStatEnabled) {
                            Text(
                                buildString {
                                    if (state.cpuStatEnabled) append("CPU 12%  ")
                                    if (state.gpuStatEnabled) append("GPU 45%  ")
                                    if (state.ramStatEnabled) append("RAM 2.1/8.0GB")
                                },
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }

                // Real drawer handle shape/location, not a separate icon.
                Box(
                    modifier = when (state.drawerEdge) {
                        DrawerEdge.LEFT -> Modifier.align(Alignment.CenterStart).width(9.dp).fillMaxHeight(0.34f)
                        DrawerEdge.RIGHT -> Modifier.align(Alignment.CenterEnd).width(9.dp).fillMaxHeight(0.34f)
                        DrawerEdge.TOP -> Modifier.align(Alignment.TopCenter).fillMaxWidth(0.34f).height(9.dp)
                        DrawerEdge.BOTTOM -> Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.34f).height(9.dp)
                    }.background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f), MaterialTheme.shapes.small)
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Preview ใช้ภาพเดียวกับ assets/hud/1.png และจัด HUD เป็นกลุ่มเดียวแบบ OverlayManager; เปลี่ยนตำแหน่ง HUD หรือขอบลิ้นชักแล้ว preview จะอัปเดตทันที",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- HUD --------------------------------------------------------------------
        // Optional on-screen readouts, off by default — collapsed unless the
        // user already turned one on.
        CollapsibleSection(
            title = stringResource(R.string.section_hud),
            subtitle = if (state.fpsCounterEnabled || state.frameGraphEnabled) "เปิดอยู่" else "ปิดอยู่ — แตะเพื่อตั้งค่า",
            startExpanded = state.fpsCounterEnabled || state.frameGraphEnabled,
        ) {
            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = stringResource(R.string.param_fps_counter),
                description = stringResource(R.string.param_fps_counter_desc),
                checked = state.fpsCounterEnabled,
                onCheckedChange = {
                    prefs.setFpsCounterEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.AutoMirrored.Filled.ShowChart,
                title = stringResource(R.string.param_frame_graph),
                description = stringResource(R.string.param_frame_graph_desc),
                checked = state.frameGraphEnabled,
                onCheckedChange = {
                    prefs.setFrameGraphEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.Memory,
                title = "CPU usage",
                description = "แสดง % การใช้งาน CPU บน HUD",
                checked = state.cpuStatEnabled,
                onCheckedChange = {
                    prefs.setCpuStatEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.DeveloperBoard,
                title = "GPU usage",
                description = "แสดง % การใช้งาน GPU บน HUD",
                checked = state.gpuStatEnabled,
                onCheckedChange = {
                    prefs.setGpuStatEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.Storage,
                title = "RAM usage",
                description = "แสดงหน่วยความจำที่ใช้ / ทั้งหมดบน HUD",
                checked = state.ramStatEnabled,
                onCheckedChange = {
                    prefs.setRamStatEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.OpenWith,
                title = "Unlock HUD position",
                description = "ปลดล็อกเพื่อลากตำแหน่ง HUD ระหว่างเซสชัน",
                checked = state.hudPositionUnlocked,
                onCheckedChange = {
                    prefs.setHudPositionUnlocked(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- Trusted overlay (accessibility) ---------------------------------------
        // Optional accessibility-service feature, off by default — collapsed
        // unless already enabled so the choice stays visible once made.
        CollapsibleSection(
            title = stringResource(R.string.section_trusted_overlay),
            subtitle = if (state.trustedOverlay) "เปิดอยู่" else "ปิดอยู่ — ไม่บังคับ แตะเพื่อตั้งค่า",
            startExpanded = state.trustedOverlay,
        ) {
            ToggleRow(
                icon = Icons.Filled.TouchApp,
                title = stringResource(R.string.param_trusted_overlay),
                description = stringResource(R.string.param_trusted_overlay_desc),
                checked = state.trustedOverlay,
                onCheckedChange = {
                    prefs.setTrustedOverlay(it)
                    if (!it) prefs.setGestureForwardingEnabled(false)
                    refreshConfigState(prefs)
                },
            )
            if (state.trustedOverlay) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.Gesture,
                    title = "Forward touches",
                    description = "สำรองสำหรับเครื่องที่เข้มงวด: จำลองการแตะ/ปัดผ่าน Accessibility เมื่อ trusted overlay ยังโดนบล็อกอยู่",
                    checked = state.gestureForwardingEnabled,
                    onCheckedChange = {
                        prefs.setGestureForwardingEnabled(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        // ---- Capture mode ----------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_capture_mode), title = null)
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.captureSource == CaptureSource.MEDIA_PROJECTION,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.MEDIA_PROJECTION)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_mediaprojection)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.captureSource == CaptureSource.SHIZUKU,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.SHIZUKU)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_shizuku)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.captureSource == CaptureSource.ROOT,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.ROOT)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_root)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- Present mode (from SettingsDrawerOverlay) --------------------------------
        LsfgCard {
            SectionHeader(eyebrow = "PRESENT MODE", title = null)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    PresentMode.IMMEDIATE to "Immediate",
                    PresentMode.MAILBOX to "Mailbox",
                    PresentMode.FIFO to "FIFO",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = state.presentMode == mode,
                        onClick = {
                            prefs.setPresentMode(mode)
                            runCatching { NativeBridge.setPresentMode(mode.vkValue) }
                            refreshConfigState(prefs)
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Immediate = หน่วงต่ำสุด อาจมีภาพฉีก. Mailbox = หน่วงต่ำ ไม่มีภาพฉีก (ค่าเริ่มต้น). FIFO = ซิงก์กับ vsync ไม่มีภาพฉีก แต่หน่วงมากที่สุด.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Frame transfer (from SettingsDrawerOverlay) ------------------------------
        LsfgCard {
            SectionHeader(eyebrow = "FRAME TRANSFER", title = null)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    FrameTransferMode.CPU_COPY to "CPU Copy",
                    FrameTransferMode.GPU_COPY to "GPU Copy",
                    FrameTransferMode.ZERO_COPY to "Zero Copy",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = state.frameTransferMode == mode,
                        onClick = {
                            prefs.setFrameTransferMode(mode)
                            runCatching { NativeBridge.setFrameTransferMode(mode.nativeValue) }
                            refreshConfigState(prefs)
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "วิธีที่พิกเซลจาก REAL capture เข้าสู่ช่อง input ของ LSFG: CPU Copy ใช้ memcpy, GPU Copy ใช้ Vulkan transfer, Zero Copy ส่ง buffer ตรงโดยไม่คัดลอกเลย แต่ละโหมดทำงานเดี่ยว ไม่มีการ fallback ไปโหมดอื่นถ้าล้มเหลว",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Sound tuner (from SettingsDrawerOverlay) ----------------------------------
        SoundTunerSection(prefs = prefs)

        TailNote()
    }
}

// ----------------------------------------------------------------------------------------
// Sound tuner — system-wide EQ/BassBoost/Virtualizer/LoudnessEnhancer, ported from
// SettingsDrawerOverlay.buildSoundTunerSection. [appSoundTuner] is kept at file scope
// (rather than `remember`ed) so the attached AudioEffect handles outlive this composable's
// lifecycle the same way they outlive the drawer panel while a session is running.
// ----------------------------------------------------------------------------------------

private var appSoundTuner: SoundTunerController? = null

@Composable
private fun SoundTunerSection(prefs: LsfgPreferences) {
    val bandInfo = remember { SoundTunerController.queryBandInfo() }
    var enabled by remember { mutableStateOf(prefs.isSoundTunerEnabled()) }
    var bass by remember { mutableStateOf(prefs.getSoundTunerBass()) }
    var virtualizer by remember { mutableStateOf(prefs.getSoundTunerVirtualizer()) }
    var loudness by remember { mutableStateOf(prefs.getSoundTunerLoudness()) }
    val bandLevels = remember {
        val saved = prefs.getSoundTunerEqBands()
        mutableStateListOf<Int>().apply {
            when {
                bandInfo != null && saved.size == bandInfo.bandCount -> addAll(saved)
                bandInfo != null -> addAll(List(bandInfo.bandCount) { (bandInfo.levelRange[0] + bandInfo.levelRange[1]) / 2 })
            }
        }
    }

    fun controller(): SoundTunerController = appSoundTuner ?: SoundTunerController().also { appSoundTuner = it }

    fun applyAllToController() {
        val c = controller()
        c.setBassBoostStrength(bass)
        c.setVirtualizerStrength(virtualizer)
        c.setLoudnessGain(loudness)
        if (bandInfo != null && bandLevels.size == bandInfo.bandCount) {
            bandLevels.forEachIndexed { i, lvl -> c.setEqBandLevel(i, lvl) }
        }
    }

    // Re-attach immediately if the tuner was left on from a previous session, same as
    // how the drawer's buildSoundTunerSection restores prior on/off state.
    LaunchedEffect(Unit) {
        if (enabled) {
            controller().enable()
            applyAllToController()
        }
    }

    CollapsibleSection(
        title = "SOUND TUNER",
        subtitle = if (enabled) "เปิดอยู่" else "ปิดอยู่ — แตะเพื่อตั้งค่า",
        startExpanded = enabled,
    ) {
        ToggleRow(
            icon = Icons.Filled.GraphicEq,
            title = "Enable sound tuner",
            description = "อีควอไลเซอร์ระบบ + bass boost + virtualizer + loudness ที่ปรับเสียงของทุกแอปที่กำลังเล่นเสียงอยู่",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.setSoundTunerEnabled(it)
                if (it) {
                    controller().enable()
                    applyAllToController()
                } else {
                    appSoundTuner?.disable()
                    appSoundTuner = null
                }
            },
        )

        Spacer(Modifier.height(10.dp))
        Text("Presets", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SoundTunerController.PRESETS.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = {
                        bass = preset.bass
                        virtualizer = preset.virtualizer
                        loudness = preset.loudness
                        prefs.setSoundTunerBass(preset.bass)
                        prefs.setSoundTunerVirtualizer(preset.virtualizer)
                        prefs.setSoundTunerLoudness(preset.loudness)
                        if (bandInfo != null && bandInfo.bandCount > 0 && bandLevels.size == bandInfo.bandCount) {
                            val range = bandInfo.levelRange
                            val newBands = bandInfo.centerFreqsHz.map { freq ->
                                SoundTunerController.presetLevelForBand(preset.eqShapeAt(freq), range)
                            }
                            prefs.setSoundTunerEqBands(newBands)
                            newBands.forEachIndexed { i, lvl -> bandLevels[i] = lvl }
                        }
                        if (enabled) applyAllToController()
                    },
                    label = { Text(preset.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ValueSlider(
            title = "Bass boost",
            valueDisplay = "${bass / 10}%",
            description = "เสริมเสียงเบส",
            value = bass.toFloat(),
            range = 0f..1000f,
            steps = 99,
            enabled = enabled,
            onValueChange = {
                bass = it.toInt()
                prefs.setSoundTunerBass(bass)
                appSoundTuner?.setBassBoostStrength(bass)
            },
        )
        ValueSlider(
            title = "Virtualizer",
            valueDisplay = "${virtualizer / 10}%",
            description = "จำลองเสียงรอบทิศทาง",
            value = virtualizer.toFloat(),
            range = 0f..1000f,
            steps = 99,
            enabled = enabled,
            onValueChange = {
                virtualizer = it.toInt()
                prefs.setSoundTunerVirtualizer(virtualizer)
                appSoundTuner?.setVirtualizerStrength(virtualizer)
            },
        )
        ValueSlider(
            title = "Loudness",
            valueDisplay = "${loudness / 100}%",
            description = "เพิ่มความดังเป้าหมาย",
            value = loudness.toFloat(),
            range = 0f..2000f,
            steps = 99,
            enabled = enabled,
            onValueChange = {
                loudness = it.toInt()
                prefs.setSoundTunerLoudness(loudness)
                appSoundTuner?.setLoudnessGain(loudness)
            },
        )

        if (bandInfo != null && bandInfo.bandCount > 0 && bandLevels.size == bandInfo.bandCount) {
            Spacer(Modifier.height(8.dp))
            Text("Equalizer", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            bandInfo.centerFreqsHz.forEachIndexed { i, freq ->
                val freqLabel = if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz"
                ValueSlider(
                    title = freqLabel,
                    valueDisplay = "%.1f dB".format(bandLevels[i] / 100f),
                    description = null,
                    value = bandLevels[i].toFloat(),
                    range = bandInfo.levelRange[0].toFloat()..bandInfo.levelRange[1].toFloat(),
                    steps = 0,
                    enabled = enabled,
                    onValueChange = {
                        val level = it.toInt()
                        bandLevels[i] = level
                        appSoundTuner?.setEqBandLevel(i, level)
                        prefs.setSoundTunerEqBands(bandLevels.toList())
                    },
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "อีควอไลเซอร์แบบราย band ไม่พร้อมใช้งานบนเครื่องนี้ — bass boost/virtualizer/loudness ด้านบนยังใช้งานได้ตามปกติ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TailNote() {
    Text(
        text = "HUD preview uses the real assets/hud image. Image Enhancement is live and applies on the next displayed frame; frame-generation shader parameters still require their existing session reinitialization.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DrawerEdgeSelector(
    selected: DrawerEdge,
    onSelected: (DrawerEdge) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.OpenInFull, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_drawer_edge),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        val options = listOf(
            DrawerEdge.LEFT to stringResource(R.string.drawer_edge_left),
            DrawerEdge.RIGHT to stringResource(R.string.drawer_edge_right),
            DrawerEdge.TOP to stringResource(R.string.drawer_edge_top),
            DrawerEdge.BOTTOM to stringResource(R.string.drawer_edge_bottom),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.take(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.drop(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
