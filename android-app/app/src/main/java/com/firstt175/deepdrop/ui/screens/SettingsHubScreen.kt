package com.firstt175.deepdrop.ui.screens

import com.firstt175.deepdrop.ui.Routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.firstt175.deepdrop.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader

@Composable
fun SettingsHubScreen(nav: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.settings_title),
            onBack = { nav.popBackStack() },
        )

        SectionHeader(eyebrow = stringResource(R.string.settings_general))

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.nav_framegen_pacing),
                subtitle = stringResource(R.string.nav_framegen_pacing_desc),
                onClick = { nav.navigate(Routes.FRAMEGEN) },
            )
            SettingsHubRow(
                icon = Icons.Filled.DisplaySettings,
                title = stringResource(R.string.nav_overlay_display),
                subtitle = stringResource(R.string.nav_overlay_display_desc),
                onClick = { nav.navigate(Routes.OVERLAY_DISPLAY) },
            )
            SettingsHubRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Image Enhancement",
                subtitle = "Live post-process • CPU / GPU (Vulkan)",
                onClick = { nav.navigate(Routes.IMAGE_ENHANCEMENT) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Movie,
                title = "Recording Gallery",
                subtitle = "Session Mode • microphone • watch and manage MP4 clips",
                onClick = { nav.navigate(Routes.RECORDINGS) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.appearance_title),
                subtitle = stringResource(R.string.appearance_desc),
                onClick = { nav.navigate(Routes.APPEARANCE) },
            )
        }

        SectionHeader(eyebrow = stringResource(R.string.settings_games))

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.nav_setup),
                subtitle = stringResource(R.string.nav_setup_desc),
                onClick = { nav.navigate(Routes.SETUP) },
            )
            SettingsHubRow(
                icon = Icons.Filled.BatteryFull,
                title = stringResource(R.string.profile_title),
                subtitle = stringResource(R.string.nav_profile_desc),
                onClick = { nav.navigate(Routes.PROFILE) },
            )
        }

        SectionHeader(eyebrow = stringResource(R.string.settings_system))

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Tune,
                title = "Lossless.dll",
                subtitle = stringResource(R.string.nav_dll_desc),
                onClick = { nav.navigate(Routes.DLL) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.credits_title),
                subtitle = stringResource(R.string.nav_credits_desc),
                onClick = { nav.navigate(Routes.CREDITS) },
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsHubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
