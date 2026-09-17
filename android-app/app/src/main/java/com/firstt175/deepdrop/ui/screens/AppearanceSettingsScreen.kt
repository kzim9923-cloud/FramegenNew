package com.firstt175.deepdrop.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.AppAppearancePrefs
import com.firstt175.deepdrop.prefs.AppThemeMode
import com.firstt175.deepdrop.prefs.LoadingScreenPrefs
import com.firstt175.deepdrop.prefs.LoadingStyle
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgPrimaryButton
import com.firstt175.deepdrop.ui.components.LsfgTopBar

@Composable
fun AppearanceSettingsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf(AppAppearancePrefs.get(context)) }
    var loadingScreenEnabled by remember { mutableStateOf(LoadingScreenPrefs.isEnabled(context)) }
    var loadingStyle by remember { mutableStateOf(LoadingScreenPrefs.getStyle(context)) }

    fun apply() {
        AppAppearancePrefs.set(context, state)
        (context as? Activity)?.recreate()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LsfgTopBar(title = androidx.compose.ui.res.stringResource(R.string.appearance_title), onBack = { nav.popBackStack() })

        LsfgCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleMedium)
                    Text(androidx.compose.ui.res.stringResource(R.string.appearance_theme_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppThemeMode.DARK to R.string.appearance_dark,
                    AppThemeMode.LIGHT to R.string.appearance_light,
                ).forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.theme == mode,
                        onClick = { state = state.copy(theme = mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(androidx.compose.ui.res.stringResource(label)) }
                }
            }
        }

        LsfgCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.appearance_fine_tuning), style = MaterialTheme.typography.titleMedium)
            }
            AppearanceSlider(R.string.appearance_surface_opacity, state.surfaceOpacity, 0.45f, 1f) {
                state = state.copy(surfaceOpacity = it)
            }
            AppearanceSlider(R.string.appearance_border_opacity, state.borderOpacity, 0f, 1f) {
                state = state.copy(borderOpacity = it)
            }
            AppearanceSlider(R.string.appearance_corner_radius, state.cornerRadius, 4f, 32f, suffix = " dp") {
                state = state.copy(cornerRadius = it)
            }
            AppearanceSlider(R.string.appearance_shadow, state.shadowElevation, 0f, 16f, suffix = " dp") {
                state = state.copy(shadowElevation = it)
            }
        }

        LsfgCard {
            SettingSwitch(
                title = androidx.compose.ui.res.stringResource(R.string.appearance_animations),
                desc = androidx.compose.ui.res.stringResource(R.string.appearance_animations_desc),
                checked = state.animationsEnabled,
                onCheckedChange = { state = state.copy(animationsEnabled = it) },
            )
            SettingSwitch(
                title = androidx.compose.ui.res.stringResource(R.string.appearance_compact),
                desc = androidx.compose.ui.res.stringResource(R.string.appearance_compact_desc),
                checked = state.compactMode,
                onCheckedChange = { state = state.copy(compactMode = it) },
            )
        }

        LsfgCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.appearance_startup), style = MaterialTheme.typography.titleMedium)
            }
            SettingSwitch(
                title = androidx.compose.ui.res.stringResource(R.string.appearance_loading_screen),
                desc = androidx.compose.ui.res.stringResource(R.string.appearance_loading_screen_desc),
                checked = loadingScreenEnabled,
                onCheckedChange = {
                    loadingScreenEnabled = it
                    LoadingScreenPrefs.setEnabled(context, it)
                },
            )
            AnimatedVisibility(visible = loadingScreenEnabled) {
                LoadingStylePicker(
                    selected = loadingStyle,
                    onSelected = {
                        loadingStyle = it
                        LoadingScreenPrefs.setStyle(context, it)
                    },
                )
            }
        }

        LsfgPrimaryButton(
            text = androidx.compose.ui.res.stringResource(R.string.appearance_apply),
            onClick = ::apply,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AppearanceSlider(
    labelRes: Int, value: Float, min: Float, max: Float, suffix: String = "%",
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(androidx.compose.ui.res.stringResource(labelRes))
            Text(if (suffix == "%") "${(value * 100).toInt()}%" else "${value.toInt()}$suffix",
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max)
    }
}

/**
 * Lets the user preview and pick one of the [LoadingStyle] visuals for the
 * startup gate (and the matching pre-game-launch overlay). The preview box
 * renders the real overlay composable — live and animated — clipped down to
 * a small rounded panel rather than a static thumbnail, so what's picked is
 * exactly what will be shown.
 */
@Composable
private fun LoadingStylePicker(selected: LoadingStyle, onSelected: (LoadingStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            LoadingOverlay(style = selected)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LoadingStyle.entries.forEach { style ->
                val isSelected = style == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .border(
                            width = if (isSelected) 0.dp else 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .selectable(selected = isSelected, onClick = { onSelected(style) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(style.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
