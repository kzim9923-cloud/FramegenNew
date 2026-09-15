package com.firstt175.deepdrop.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.firstt175.deepdrop.prefs.AppAppearancePrefs
import com.firstt175.deepdrop.prefs.AppThemeMode

private val LsfgDarkColorScheme = darkColorScheme(
    primary = LsfgPrimary, onPrimary = LsfgOnPrimary,
    primaryContainer = LsfgPrimaryContainer, onPrimaryContainer = LsfgOnPrimaryContainer,
    secondary = LsfgSecondary, onSecondary = LsfgOnSecondary,
    secondaryContainer = LsfgSecondaryContainer, onSecondaryContainer = LsfgOnSecondaryContainer,
    tertiary = LsfgTertiary, onTertiary = LsfgOnTertiary,
    tertiaryContainer = LsfgTertiaryContainer, onTertiaryContainer = LsfgOnTertiaryContainer,
    error = LsfgError, onError = LsfgOnError,
    errorContainer = LsfgErrorContainer, onErrorContainer = LsfgOnErrorContainer,
    background = LsfgBackground, onBackground = LsfgOnBackground,
    surface = LsfgSurface, onSurface = LsfgOnSurface, onSurfaceVariant = LsfgOnSurfaceVariant,
    surfaceDim = LsfgSurfaceDim, surfaceBright = LsfgSurfaceBright,
    surfaceContainerLowest = LsfgSurfaceContainerLowest, surfaceContainerLow = LsfgSurfaceContainerLow,
    surfaceContainer = LsfgSurfaceContainer, surfaceContainerHigh = LsfgSurfaceContainerHigh,
    surfaceContainerHighest = LsfgSurfaceContainerHighest,
    outline = LsfgOutline, outlineVariant = LsfgOutlineVariant,
)

private val LsfgLightColorScheme = lightColorScheme(
    primary = LsfgLightPrimary, onPrimary = LsfgLightOnPrimary,
    primaryContainer = LsfgLightPrimaryContainer, onPrimaryContainer = LsfgLightOnPrimaryContainer,
    secondary = LsfgLightSecondary, onSecondary = LsfgLightOnSecondary,
    secondaryContainer = LsfgLightSecondaryContainer, onSecondaryContainer = LsfgLightOnSecondaryContainer,
    tertiary = LsfgLightTertiary, onTertiary = LsfgLightOnTertiary,
    tertiaryContainer = LsfgLightTertiaryContainer, onTertiaryContainer = LsfgLightOnTertiaryContainer,
    error = LsfgLightError, onError = LsfgLightOnError,
    errorContainer = LsfgLightErrorContainer, onErrorContainer = LsfgLightOnErrorContainer,
    background = LsfgLightBackground, onBackground = LsfgLightOnBackground,
    surface = LsfgLightSurface, onSurface = LsfgLightOnSurface,
    onSurfaceVariant = LsfgLightOnSurfaceVariant, outline = LsfgLightOutline,
    outlineVariant = LsfgLightOutlineVariant,
    surfaceContainerLowest = Color(0xFFF7F7F9),
    surfaceContainerLow = Color(0xFFF1F1F4),
    surfaceContainer = Color(0xFFECECF0),
    surfaceContainerHigh = Color(0xFFE6E6EA),
    surfaceContainerHighest = Color(0xFFE0E0E5),
)

@Composable
fun LsfgTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance = AppAppearancePrefs.get(context)
    val isLight = appearance.theme != AppThemeMode.DARK
    val base = if (isLight) LsfgLightColorScheme else LsfgDarkColorScheme

    // Surfaces/cards can be made translucent via the appearance settings,
    // giving a lightweight glass-panel look without an expensive full-screen blur.
    val alpha = appearance.surfaceOpacity
    val colorScheme = base.copy(
        surface = base.surface.copy(alpha = alpha),
        surfaceContainerLowest = base.surfaceContainerLowest.copy(alpha = alpha),
        surfaceContainerLow = base.surfaceContainerLow.copy(alpha = alpha),
        surfaceContainer = base.surfaceContainer.copy(alpha = alpha),
        surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = alpha),
        surfaceContainerHighest = base.surfaceContainerHighest.copy(alpha = alpha),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LsfgTypography,
        shapes = LsfgShapes(appearance.cornerRadius),
        content = content,
    )
}

