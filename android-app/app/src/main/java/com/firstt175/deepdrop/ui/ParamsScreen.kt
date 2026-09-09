package com.firstt175.deepdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.CaptureSource
import com.firstt175.deepdrop.prefs.DrawerEdge
import com.firstt175.deepdrop.prefs.FramegenBackend
import com.firstt175.deepdrop.prefs.PostProcessMode
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.OverlayMode
import com.firstt175.deepdrop.prefs.PacingDefaults
import com.firstt175.deepdrop.prefs.PacingPreset
import com.firstt175.deepdrop.session.AutoOverlayController
import com.firstt175.deepdrop.session.NativeBridge
import com.firstt175.deepdrop.ui.components.CollapsibleSection
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.components.StatusPill
import com.firstt175.deepdrop.ui.components.StatusTone
import com.firstt175.deepdrop.ui.components.ToggleRow
import com.firstt175.deepdrop.ui.components.ValueSlider
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgStatusGood

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — consolidated screen
// ----------------------------------------------------------------------------------------

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — embedded inline in the Settings screen, not a
// separate destination. [nav] is only used to jump to the DLL picker / Legal screen.
// ----------------------------------------------------------------------------------------

@Composable
fun FrameGenPacingSection(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    // The FP16 toggle is only meaningful when (a) the GPU advertises shaderFloat16
    // and (b) the FP16 SPIR-V cache from Lossless.dll has been populated. The
    // native probe ANDs both. Cached for the screen's lifetime — recomputed on
    // re-entry, which covers the post-DLL-pick / post-extract case naturally.
    val fp16Available = remember {
        val cacheDir = java.io.File(ctx.filesDir, "spirv").absolutePath
        runCatching { NativeBridge.isFramegenFp16Supported(cacheDir) }.getOrDefault(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Picking the DLL (or AI model) used to live as its own step on the
        // settings home screen. It now lives here, at the top of Frame Gen,
        // since the two are really the same setting.
        LsfgCard(onClick = {
            if (!state.legalAccepted) nav.navigate(Routes.LEGAL) else nav.navigate(Routes.DLL)
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = if (state.shadersReady) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.InsertDriveFile,
                    tint = if (state.shadersReady) LsfgStatusGood else LsfgPrimary,
                    size = 36.dp,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.nav_dll),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.dllDisplayName ?: stringResource(R.string.dll_status_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                StatusPill(
                    label = if (state.shadersReady) "Ready" else if (state.dllDisplayName != null) "Pending" else "Required",
                    tone = if (state.shadersReady) StatusTone.Good
                    else if (state.dllDisplayName != null) StatusTone.Warn
                    else StatusTone.Neutral,
                )
            }
        }

        // ---- Frame generation -------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_frame_generation), title = null)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                    "Backend: AI (ncnn). Multiplier, flow scale and render resolution below apply " +
                        "to it too — the LSFG-shader-only options (performance mode, HDR, FP16) are " +
                        "hidden since they don't do anything on this backend. Switch backend and " +
                        "pick the compute path on the Frame-gen source screen."
                } else {
                    "Backend: Lossless.dll (LSFG). Switch backend on the Frame-gen source screen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = "LSFG-Android+ Frame Gen",
                description = "Master toggle for frame generation. Off = raw capture passthrough.",
                checked = state.lsfgEnabled,
                onCheckedChange = {
                    prefs.setLsfgEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // The AI (ncnn) backend is locked to ×2. RIFE/IFRNet were only ever exported
            // and validated for the single-midpoint (k=1, multiplier=2) case — nothing
            // above ×2 has been trained/tested for these models, so the slider is
            // disabled and forced back to 2 rather than letting it silently run an
            // unvalidated higher multiplier. Only the LSFG_DLL shader backend supports
            // the full 2..8 range.
            if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                if (state.multiplier != 2) {
                    LaunchedEffect(Unit) {
                        prefs.setMultiplier(2)
                        refreshConfigState(prefs)
                    }
                }
                ValueSlider(
                    title = stringResource(R.string.param_multiplier),
                    valueDisplay = "2× (locked)",
                    description = "AI backend is locked to ×2 — RIFE/IFRNet are only " +
                        "validated at the single midpoint frame. Switch to the Lossless.dll " +
                        "backend for ×3–×8.",
                    value = 2f,
                    range = 2f..2f,
                    steps = 0,
                    leadingIcon = Icons.Filled.Timeline,
                    enabled = false,
                    onValueChange = {},
                )
            } else {
                ValueSlider(
                    title = stringResource(R.string.param_multiplier),
                    valueDisplay = "${state.multiplier}×",
                    description = stringResource(R.string.param_multiplier_desc),
                    value = state.multiplier.toFloat(),
                    range = 2f..8f,
                    steps = 5,
                    leadingIcon = Icons.Filled.Timeline,
                    onValueChange = {
                        prefs.setMultiplier(it.toInt().coerceIn(2, 8))
                        refreshConfigState(prefs)
                    },
                )
            }

            // Like the multiplier slider above, flow scale only affects the LSFG_DLL
            // shader chain's motion-estimation pass (see resourcepool.cpp). The AI
            // (ncnn) backend's RIFE/IFRNet nets are single-pass with no separate
            // low-res flow stage to downscale — flowScale is accepted by
            // NcnnInterpolator::interpolate()/IfrnetInterpolator::interpolate() only
            // for call-site compatibility and is explicitly unused (see the
            // `/*flowScale — unused*/` parameter comment in both .cpp files).
            // Leaving the slider live here made it look broken: dragging it changed
            // the stored pref but had no visible effect on AI-backend output.
            if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                ValueSlider(
                    title = stringResource(R.string.param_flow_scale),
                    valueDisplay = "N/A",
                    description = "AI backend's RIFE/IFRNet nets have no separate flow " +
                        "stage to scale — this only affects the Lossless.dll shader backend.",
                    value = 1.0f,
                    range = 1.0f..1.0f,
                    steps = 0,
                    leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                    enabled = false,
                    onValueChange = {},
                )
            } else {
                ValueSlider(
                    title = stringResource(R.string.param_flow_scale),
                    // Keep the original 0.1.3 representation: Flow scale is the
                    // raw 0.25..1.0 value, not a percentage and not a preset.
                    valueDisplay = "%.2f".format(state.flowScale),
                    description = stringResource(R.string.param_flow_scale_desc),
                    value = state.flowScale,
                    range = 0.25f..1.0f,
                    steps = 0,
                    leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                    onValueChange = {
                        prefs.setFlowScale(it)
                        refreshConfigState(prefs)
                    },
                )
            }



            Spacer(Modifier.height(4.dp))
            SectionHeader(eyebrow = "POST PROCESS", title = null)
            Text(
                text = "GPU upscale + adaptive convolution sharpening. Runs after frame generation and never changes the REAL-frame pacing path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PostProcessMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.postProcessMode == mode,
                        onClick = {
                            prefs.setPostProcessMode(mode)
                            refreshConfigState(prefs)
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (state.postProcessMode != PostProcessMode.OFF) {
                ValueSlider(
                    title = "Sharpness",
                    valueDisplay = "${(state.postProcessSharpness * 100f).toInt()}%",
                    description = if (state.postProcessMode == PostProcessMode.RCAS)
                        "RCAS-style limiter: stronger edge recovery with clipping/noise control."
                    else
                        "CAS-style adaptive convolution: softer and more natural.",
                    value = state.postProcessSharpness,
                    range = 0f..1f,
                    steps = 20,
                    leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                    onValueChange = {
                        prefs.setPostProcessSharpness(it)
                        refreshConfigState(prefs)
                    },
                )
            }

            // ---- Frame pacing ------------------------------------------------------
            // Kept directly in the existing Frame Generation screen: these are
            // user-facing pacing controls, not a separate "Advanced" page.
            Spacer(Modifier.height(4.dp))
            SectionHeader(eyebrow = "FRAME PACING", title = null)
            Text(
                text = "Controls the adaptive timing estimator used to decide when generated work is safe to admit without delaying REAL frames.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pacing preset",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.pacingPreset == PacingPreset.SMOOTH,
                    onClick = {
                        prefs.setPacingPreset(PacingPreset.SMOOTH)
                        NativeBridge.setPacingParams(0.08f, 6.0f)
                        refreshConfigState(prefs)
                    },
                    label = { Text("Smooth") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.pacingPreset == PacingPreset.BALANCED,
                    onClick = {
                        prefs.setPacingPreset(PacingPreset.BALANCED)
                        NativeBridge.setPacingParams(PacingDefaults.EMA_ALPHA, PacingDefaults.OUTLIER_RATIO)
                        refreshConfigState(prefs)
                    },
                    label = { Text("Balanced") },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.pacingPreset == PacingPreset.LOW_LATENCY,
                    onClick = {
                        prefs.setPacingPreset(PacingPreset.LOW_LATENCY)
                        NativeBridge.setPacingParams(0.2f, 3.0f)
                        refreshConfigState(prefs)
                    },
                    label = { Text("Low Latency") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.pacingPreset == PacingPreset.CUSTOM,
                    onClick = {
                        prefs.setPacingPreset(PacingPreset.CUSTOM)
                        NativeBridge.setPacingParams(state.emaAlpha, state.outlierRatio)
                        refreshConfigState(prefs)
                    },
                    label = { Text("Custom") },
                    modifier = Modifier.weight(1f),
                )
            }

            val effectivePacing = PacingDefaults.forPreset(
                state.pacingPreset,
                PacingDefaults.Params(state.emaAlpha, state.outlierRatio),
            )
            ValueSlider(
                title = "EMA Alpha",
                valueDisplay = "%.3f".format(effectivePacing.emaAlpha),
                description = if (state.pacingPreset == PacingPreset.CUSTOM)
                    "Higher values react faster to changing frame-generation cost; lower values smooth more aggressively."
                else
                    "Preset-controlled value. Select Custom to tune it manually.",
                value = effectivePacing.emaAlpha,
                range = 0.05f..0.5f,
                steps = 44,
                leadingIcon = Icons.Filled.Speed,
                enabled = state.pacingPreset == PacingPreset.CUSTOM,
                onValueChange = {
                    prefs.setPacingPreset(PacingPreset.CUSTOM)
                    prefs.setEmaAlpha(it)
                    NativeBridge.setPacingParams(it, state.outlierRatio)
                    refreshConfigState(prefs)
                },
            )
            ValueSlider(
                title = "Outlier Ratio",
                valueDisplay = "%.1f×".format(effectivePacing.outlierRatio),
                description = if (state.pacingPreset == PacingPreset.CUSTOM)
                    "Rejects generation-cost spikes above the current estimate multiplied by this ratio."
                else
                    "Preset-controlled value. Select Custom to tune it manually.",
                value = effectivePacing.outlierRatio,
                range = 2.0f..8.0f,
                steps = 29,
                leadingIcon = Icons.Filled.Tune,
                enabled = state.pacingPreset == PacingPreset.CUSTOM,
                onValueChange = {
                    prefs.setPacingPreset(PacingPreset.CUSTOM)
                    prefs.setOutlierRatio(it)
                    NativeBridge.setPacingParams(state.emaAlpha, it)
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = stringResource(R.string.param_render_resolution_scale),
                valueDisplay = "${(state.renderResolutionScale * 100f).toInt()}%",
                description = stringResource(R.string.param_render_resolution_scale_desc),
                value = state.renderResolutionScale,
                range = 0f..1f,
                steps = 19,
                leadingIcon = Icons.Filled.AspectRatio,
                onValueChange = {
                    prefs.setRenderResolutionScale(it)
                    refreshConfigState(prefs)
                },
            )

            // Input FPS cap: throttles how many incoming capture frames are
            // admitted into the pipeline at all. Frames arriving faster than
            // the cap are dropped before capture handling, blit, and
            // generation — the whole pipeline runs at the capped rate, not
            // just the AI/LSFG step, e.g. capping a 60fps game to 30 means
            // ~30fps actually gets processed and fed into frame generation.
            // Backend-agnostic, so this stays visible for both Lossless.dll
            // and the AI (ncnn) backend.
            // Everything below this point only takes effect on the LSFG_3_1/3_1P
            // shader chain — the AI (ncnn) backend never reads performanceMode,
            // hdrMode or framegenFp16 at all (see lsfg_render_loop.cpp's
            // initRenderLoop: when cfg.aiBackend loads successfully,
            // initFramegen/createFramegenContext — the only code that consults
            // these three — is skipped entirely). Showing them while the AI
            // backend is selected would suggest they do something they don't.
            if (state.framegenBackend == FramegenBackend.LSFG_DLL) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.param_performance_mode),
                    description = stringResource(R.string.param_performance_mode_desc),
                    checked = state.performanceMode,
                    onCheckedChange = {
                        prefs.setPerformance(it)
                        refreshConfigState(prefs)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.HdrOn,
                    title = stringResource(R.string.param_hdr),
                    description = stringResource(R.string.param_hdr_desc),
                    checked = state.hdrMode,
                    onCheckedChange = {
                        prefs.setHdr(it)
                        refreshConfigState(prefs)
                    },
                )
                if (fp16Available) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow(
                        icon = Icons.Filled.Memory,
                        title = stringResource(R.string.param_framegen_fp16),
                        description = stringResource(R.string.param_framegen_fp16_desc),
                        checked = state.framegenFp16,
                        onCheckedChange = {
                            prefs.setFramegenFp16(it)
                            refreshConfigState(prefs)
                        },
                    )
                }
            }
        }

        TailNote()
    }
}

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

        // ---- Overlay entry mode (icon button vs drawer) -----------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_mode), title = null)
            Spacer(Modifier.height(4.dp))
            OverlayModeSelector(
                selected = state.overlayMode,
                onSelected = {
                    prefs.setOverlayMode(it)
                    refreshConfigState(prefs)
                    // Live-update the Automatic Overlay launcher: if the dot is
                    // currently shown for a target app it is recreated with the
                    // new affordance, otherwise the change is picked up the next
                    // time it appears.
                    AutoOverlayController.onOverlayModeChanged(ctx)
                },
            )
        }

        // ---- Overlay handle ---------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_handle), title = null)
            Spacer(Modifier.height(4.dp))
            DrawerEdgeSelector(
                selected = state.drawerEdge,
                onSelected = {
                    prefs.setDrawerEdge(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- HUD --------------------------------------------------------------------
        // Optional on-screen readouts, off by default — collapsed unless the
        // user already turned one on.
        CollapsibleSection(
            title = stringResource(R.string.section_hud),
            subtitle = if (state.fpsCounterEnabled || state.frameGraphEnabled) "On" else "Off — tap to configure",
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
        }

        // ---- Trusted overlay (accessibility) ---------------------------------------
        // Optional accessibility-service feature, off by default — collapsed
        // unless already enabled so the choice stays visible once made.
        CollapsibleSection(
            title = stringResource(R.string.section_trusted_overlay),
            subtitle = if (state.trustedOverlay) "On" else "Off — optional, tap to configure",
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
            if (state.captureSource == CaptureSource.MEDIA_PROJECTION) {
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    icon = Icons.Filled.Tune,
                    title = "Low-latency capture",
                    description = "Shallower capture queue, always drops to the newest frame instead of processing every buffered one. Lowers capture-to-push latency but can add extra warping during fast motion (MediaProjection capture only).",
                    checked = state.lowLatencyCapture,
                    onCheckedChange = {
                        prefs.setLowLatencyCapture(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        TailNote()
    }
}

@Composable
private fun TailNote() {
    Text(
        text = "Changes apply on the next session start. During an active session, open the in-game drawer to tweak values live.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OverlayModeSelector(
    selected: OverlayMode,
    onSelected: (OverlayMode) -> Unit,
) {
    var pendingDrawerConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.TouchApp, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_overlay_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == OverlayMode.ICON_BUTTON,
                onClick = {
                    if (selected != OverlayMode.ICON_BUTTON) onSelected(OverlayMode.ICON_BUTTON)
                },
                label = { Text(stringResource(R.string.overlay_mode_icon_button)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selected == OverlayMode.DRAWER,
                onClick = {
                    if (selected != OverlayMode.DRAWER) {
                        pendingDrawerConfirm = true
                    }
                },
                label = { Text(stringResource(R.string.overlay_mode_drawer)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewSidebar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (pendingDrawerConfirm) {
        AlertDialog(
            onDismissRequest = { pendingDrawerConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.overlay_mode_drawer_warning_title)) },
            text = { Text(stringResource(R.string.overlay_mode_drawer_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDrawerConfirm = false
                    onSelected(OverlayMode.DRAWER)
                }) { Text(stringResource(R.string.overlay_mode_drawer_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDrawerConfirm = false }) {
                    Text(stringResource(R.string.overlay_mode_drawer_warning_cancel))
                }
            },
        )
    }
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
