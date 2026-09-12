package com.firstt175.deepdrop.ui

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
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInFull
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
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.OverlayMode
import com.firstt175.deepdrop.session.AutoOverlayController
import com.firstt175.deepdrop.ui.components.CollapsibleSection
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.components.ToggleRow

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
