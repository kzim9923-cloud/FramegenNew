package com.firstt175.deepdrop.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

object Routes {
    const val HOME = "home"
    const val FRAMEGEN = "framegen"
    const val LEGAL = "legal"
    const val DLL = "dll"
    const val OVERLAY_DISPLAY = "overlay_display"
    const val AUTOMATIC_OVERLAY = "automatic_overlay"
    const val SETUP = "setup"
    const val CREDITS = "credits"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
}

@Composable
fun LsfgNavHost(navController: NavHostController) {
    // Keep navigation instant. Page-transition animations are visually nice,
    // but they force both the outgoing and incoming Compose trees to be drawn
    // at the same time. On mobile this creates avoidable GPU work and temporary
    // allocations, especially when opening large settings pages.
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) { GameLauncherScreen(navController) }
        composable(Routes.FRAMEGEN) { FrameGenScreen(navController) }
        composable(Routes.LEGAL) { LegalScreen(navController) }
        composable(Routes.DLL) { DllPickerScreen(navController) }
        composable(Routes.OVERLAY_DISPLAY) { OverlayDisplayScreen(navController) }
        composable(Routes.AUTOMATIC_OVERLAY) { AutomaticOverlayScreen(navController) }
        composable(Routes.SETUP) { SetupScreen(navController) }
        composable(Routes.CREDITS) { CreditsScreen(navController) }
        composable(Routes.PROFILE) { DeviceProfileScreen(navController) }
        composable(Routes.SETTINGS) { SettingsHubScreen(navController) }
    }
}
