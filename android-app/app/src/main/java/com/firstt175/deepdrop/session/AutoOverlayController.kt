package com.firstt175.deepdrop.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.ui.ProjectionRequestActivity

/**
 * Process-wide coordinator for the "Automatic Overlay" feature.
 *
 * Watches foreground app changes (fed by [LsfgAccessibilityService]) and toggles
 * the orange edge-handle [LauncherDotOverlay] when one of the user-selected
 * target apps comes to the front. The handle is purely a UI affordance — it
 * never starts capture on its own; tapping it explicitly activates the full
 * LSFG capture session (long-press disables Automatic Overlay for that app).
 *
 * Coordinates with [LsfgForegroundService]: when a session is active the handle
 * is hidden (the in-game settings drawer takes over); when the session stops,
 * the handle reappears if the target app is still in foreground.
 */
object AutoOverlayController {

    private const val TAG = "AutoOverlayCtrl"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabledApps: Set<String> = emptySet()

    @Volatile
    private var lastForegroundPkg: String? = null

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var dot: LauncherDotOverlay? = null

    @Volatile
    private var settingsDrawer: SettingsDrawerOverlay? = null

    @Synchronized
    fun init(ctx: Context) {
        enabledApps = LsfgPreferences(ctx).getAutoEnabledApps()
        Log.i(TAG, "init — ${enabledApps.size} app(s) enabled")
    }

    @Synchronized
    fun onAutoEnabledAppsChanged(ctx: Context, newSet: Set<String>) {
        enabledApps = newSet
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        } else if (newSet.isEmpty()) {
            hideDot()
        }
    }

    /**
     * Called by the options screen after the user flips the overlay entry mode
     * between drawer / icon button. If the launcher dot is currently visible,
     * tear it down and re-show so it picks up the new entry-affordance.
     * Re-reading the pref on every show() also means a hidden dot will pick up
     * the change next time it appears.
     */
    @Synchronized
    fun onOverlayModeChanged(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        if (dot == null) return
        Log.i(TAG, "overlay mode changed — recreating launcher dot")
        val appCtx = ctx.applicationContext
        mainHandler.post {
            dot?.hide()
            dot = null
            evaluate(appCtx, pkg)
        }
    }

    fun onForegroundPackage(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) return

        // A launcher session owns a temporary global display override. If the
        // target leaves foreground (including a crash returning to Home/another
        // app), stop the session immediately so LsfgForegroundService restores
        // the original resolution, density and background-process policy.
        val previous = lastForegroundPkg
        if (sessionActive && previous != null && pkg != previous) {
            Log.i(TAG, "target left foreground: $previous -> $pkg; stopping session")
            LsfgForegroundService.stop(ctx.applicationContext)
        } else if (!sessionActive && previous != null && pkg != previous) {
            // No LSFG session is running, but the launcher may still have
            // applied a pre-launch resolution/DPI override for `previous`
            // when the user tapped its card. If it's still the current
            // override owner, restore the original display now instead of
            // leaving the scaled-down resolution/DPI applied system-wide
            // after the app is closed.
            if (DisplayOverrideState.currentOwner(ctx.applicationContext) == previous) {
                Log.i(TAG, "target left foreground: $previous -> $pkg; restoring display override")
                val appCtx = ctx.applicationContext
                Thread {
                    runCatching { AdbDisplayController.reset(appCtx) }
                        .onFailure { Log.w(TAG, "failed to restore display override on app exit", it) }
                    DisplayOverrideState.clearIfOwner(appCtx, previous)
                }.start()
            }
        }

        lastForegroundPkg = pkg
        evaluate(ctx, pkg)
    }

    fun onSessionStarted(pkg: String?) {
        sessionActive = true
        Log.i(TAG, "session started for $pkg — hiding handle/drawer")
        hideDot()
        mainHandler.post {
            settingsDrawer?.hide()
            settingsDrawer = null
        }
    }

    fun onSessionStopped(ctx: Context) {
        sessionActive = false
        Log.i(TAG, "session stopped — re-evaluating handle for $lastForegroundPkg")
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        }
    }

    private fun onActivateRequested(ctx: Context) {
        // The floating launcher icon is ONLY an entry point to the settings drawer.
        // It must never start MediaProjection/FrameGen directly. The session starts
        // exclusively from the START SESSION button inside SettingsDrawerOverlay.
        val target = lastForegroundPkg ?: return
        hideDot()

        val appCtx = ctx.applicationContext
        mainHandler.post {
            settingsDrawer?.hide()
            val drawer = SettingsDrawerOverlay(
                appCtx,
                LsfgPreferences(appCtx).load().overlayMode,
            )
            drawer.setRestartSessionListener {
                // START SESSION is the explicit user action that is allowed to
                // request capture permission / start the foreground service.
                val currentTarget = lastForegroundPkg ?: target
                drawer.hide()
                settingsDrawer = null
                val intent = ProjectionRequestActivity.buildIntent(appCtx, currentTarget)
                appCtx.startActivity(intent)
            }
            drawer.setStopOverlayListener { drawer.hide() }
            drawer.show()
            settingsDrawer = drawer
            drawer.openPanel()
        }
    }

    private fun onDisableForApp(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        val updated = enabledApps - pkg
        enabledApps = updated
        LsfgPreferences(ctx).setAutoEnabledApps(updated)
        hideDot()
    }

    private fun evaluate(ctx: Context, pkg: String) {
        if (sessionActive) {
            hideDot()
            return
        }
        if (pkg in enabledApps) {
            showDot(ctx)
        } else {
            hideDot()
        }
    }

    private fun showDot(ctx: Context) {
        val appCtx = ctx.applicationContext
        mainHandler.post {
            if (dot != null) return@post
            // Mirror the in-game settings drawer's entry mode so the user sees
            // the same affordance everywhere. Reading the pref each time the
            // dot appears means switching the option in the app picks up
            // automatically the next time the launcher is shown.
            val mode = LsfgPreferences(appCtx).load().overlayMode
            val d = LauncherDotOverlay(
                ctx = appCtx,
                targetPackageProvider = { lastForegroundPkg },
                onActivate = { onActivateRequested(appCtx) },
                onDisableForApp = { onDisableForApp(appCtx) },
                entryMode = mode,
            )
            d.show()
            dot = d
        }
    }

    private fun hideDot() {
        mainHandler.post {
            dot?.hide()
            dot = null
        }
    }
}
