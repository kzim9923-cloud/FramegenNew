package com.firstt175.deepdrop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.session.AdbDisplayController
import com.firstt175.deepdrop.session.PermissionsHelper
import com.firstt175.deepdrop.session.ShizukuDisplayPermission
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgSecondaryButton
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.StatusPill
import com.firstt175.deepdrop.ui.components.StatusTone
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import kotlinx.coroutines.launch

/**
 * One screen that walks through every permission/service the app can need,
 * instead of scattering the same requests across the session card, the
 * automatic-overlay screen and each per-app settings dialog. Each row
 * re-checks its own status after returning from whichever system screen it
 * opened, via the ActivityResult callback (no manual refresh needed).
 */
@Composable
fun SetupScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var overlayGranted by remember { mutableStateOf(PermissionsHelper.canDrawOverlays(ctx)) }
    var a11yEnabled by remember { mutableStateOf(PermissionsHelper.isAccessibilityServiceEnabled(ctx)) }
    var batteryExempt by remember { mutableStateOf(PermissionsHelper.isIgnoringBatteryOptimizations(ctx)) }
    var secureSettingsGranted by remember { mutableStateOf(ShizukuDisplayPermission.hasWriteSecureSettings(ctx)) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = PermissionsHelper.canDrawOverlays(ctx) }

    val a11yLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { a11yEnabled = PermissionsHelper.isAccessibilityServiceEnabled(ctx) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryExempt = PermissionsHelper.isIgnoringBatteryOptimizations(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        LsfgTopBar(title = "Setup", onBack = { nav.popBackStack() })
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Everything DeepDrop needs, in one place. Grant each item below once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        SetupItem(
            icon = Icons.Filled.Layers,
            title = "Display over other apps",
            description = "Needed to draw the frame-gen overlay on top of the game/app.",
            done = overlayGranted,
            actionLabel = "Grant",
            onAction = {
                val uri = Uri.parse("package:${ctx.packageName}")
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
            },
        )

        Spacer(Modifier.height(12.dp))

        SetupItem(
            icon = Icons.Filled.Accessibility,
            title = "Accessibility service",
            description = "Lets DeepDrop detect when an auto-overlay-enabled app is opened.",
            done = a11yEnabled,
            actionLabel = "Enable",
            onAction = {
                a11yLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )

        Spacer(Modifier.height(12.dp))

        SetupItem(
            icon = Icons.Filled.BatteryFull,
            title = "Ignore battery optimizations",
            description = "Keeps the capture/frame-gen session running at full priority in the background.",
            done = batteryExempt,
            actionLabel = "Exempt",
            onAction = {
                batteryLauncher.launch(PermissionsHelper.buildIgnoreBatteryOptimizationsIntent(ctx))
            },
        )

        Spacer(Modifier.height(12.dp))

        SetupItem(
            icon = Icons.Filled.Security,
            title = "WRITE_SECURE_SETTINGS",
            description = "Needed to change per-app resolution/DPI. Grant via Shizuku, or run the ADB command on a PC.",
            done = secureSettingsGranted,
            actionLabel = if (ShizukuDisplayPermission.isShizukuAvailable()) "Grant via Shizuku" else "Connect Shizuku",
            onAction = onAction@{
                if (!ShizukuDisplayPermission.isShizukuAvailable()) {
                    if (ShizukuDisplayPermission.needsShizukuAppPermission()) {
                        ShizukuDisplayPermission.requestShizukuPermission()
                        Toast.makeText(ctx, "Allow the Shizuku permission prompt, then tap this again.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "Shizuku isn't running.", Toast.LENGTH_SHORT).show()
                    }
                    return@onAction
                }
                scope.launch {
                    val ok = ShizukuDisplayPermission.grantWriteSecureSettings(ctx)
                    secureSettingsGranted = ShizukuDisplayPermission.hasWriteSecureSettings(ctx)
                    if (!ok) {
                        Toast.makeText(ctx, "Grant failed — see the ADB command below instead.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            extraContent = if (!secureSettingsGranted) {
                {
                    Spacer(Modifier.height(8.dp))
                    Text(AdbDisplayController.grantCommand(), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LsfgSecondaryButton(
                        text = "Copy ADB command",
                        leadingIcon = Icons.Filled.ContentCopy,
                        onClick = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", AdbDisplayController.grantCommand()))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else null,
        )
    }
}

@Composable
private fun SetupItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
) {
    LsfgCard(accent = !done) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(
                icon = if (done) Icons.Filled.CheckCircle else icon,
                tint = if (done) com.firstt175.deepdrop.ui.theme.LsfgStatusGood else LsfgPrimary,
                size = 36.dp,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        label = if (done) "Ready" else "Needed",
                        tone = if (done) StatusTone.Good else StatusTone.Warn,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!done) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onAction) { Text(actionLabel) }
                    extraContent?.invoke()
                }
            }
        }
    }
}
