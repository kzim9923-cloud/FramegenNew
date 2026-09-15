package com.firstt175.deepdrop.ui

import com.firstt175.deepdrop.ui.screens.AppDisclosureScreen
import com.firstt175.deepdrop.ui.screens.AppearanceSettingsScreen
import com.firstt175.deepdrop.ui.screens.CreditsScreen
import com.firstt175.deepdrop.ui.screens.DeviceProfileScreen
import com.firstt175.deepdrop.ui.screens.DllPickerScreen
import com.firstt175.deepdrop.ui.screens.FrameGenScreen
import com.firstt175.deepdrop.ui.screens.GameLauncherScreen
import com.firstt175.deepdrop.ui.screens.ImageEnhancementScreen
import com.firstt175.deepdrop.ui.screens.LegalScreen
import com.firstt175.deepdrop.ui.screens.OverlayDisplayScreen
import com.firstt175.deepdrop.ui.screens.SettingsHubScreen
import com.firstt175.deepdrop.ui.screens.SetupScreen
import com.firstt175.deepdrop.ui.screens.WarpLoadingScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.firstt175.deepdrop.prefs.FirstRunPrefs

object Routes {
    const val DISCLOSURE = "disclosure"
    const val LOADING = "loading"
    const val HOME = "home"
    const val FRAMEGEN = "framegen"
    const val LEGAL = "legal"
    const val DLL = "dll"
    const val OVERLAY_DISPLAY = "overlay_display"
    const val IMAGE_ENHANCEMENT = "image_enhancement"
    const val SETUP = "setup"
    const val CREDITS = "credits"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val APPEARANCE = "appearance"
}

@Composable
fun LsfgNavHost(navController: NavHostController) {
    val context = LocalContext.current
    // Decided once when the NavHost is created (i.e. once per process start),
    // which is exactly what we want: the disclosure only needs to appear
    // ahead of the very first loading screen a user ever sees.
    val startDestination = remember {
        if (FirstRunPrefs.isDisclosureAcknowledged(context)) Routes.LOADING else Routes.DISCLOSURE
    }

    // Keep navigation instant. Page-transition animations are visually nice,
    // but they force both the outgoing and incoming Compose trees to be drawn
    // at the same time. On mobile this creates avoidable GPU work and temporary
    // allocations, especially when opening large settings pages.
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.DISCLOSURE) { AppDisclosureScreen(navController) }
        composable(Routes.LOADING) { WarpLoadingScreen(navController) }
        composable(Routes.HOME) { GameLauncherScreen(navController) }
        composable(Routes.FRAMEGEN) { FrameGenScreen(navController) }
        composable(Routes.LEGAL) { LegalScreen(navController) }
        composable(Routes.DLL) { DllPickerScreen(navController) }
        composable(Routes.OVERLAY_DISPLAY) { OverlayDisplayScreen(navController) }
        composable(Routes.IMAGE_ENHANCEMENT) { ImageEnhancementScreen(navController) }
        composable(Routes.SETUP) { SetupScreen(navController) }
        composable(Routes.CREDITS) { CreditsScreen(navController) }
        composable(Routes.PROFILE) { DeviceProfileScreen(navController) }
        composable(Routes.SETTINGS) { SettingsHubScreen(navController) }
        composable(Routes.APPEARANCE) { AppearanceSettingsScreen(navController) }
    }
}
