package com.firstt175.deepdrop.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.AiEngine
import com.firstt175.deepdrop.prefs.CaptureSource
import com.firstt175.deepdrop.prefs.FramegenBackend
import com.firstt175.deepdrop.prefs.LsfgConfig
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.PacingDefaults
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs for the lifetime of an LSFG session. Owns the MediaProjection token, the
 * CaptureEngine, and the OverlayManager.
 *
 * Startup sequence (important for stable overlay behaviour):
 *   1. Post foreground notification (required by FGS + mediaProjection rules).
 *   2. Acquire MediaProjection from the consent intent.
 *   3. Show overlay (adds window via WindowManager — synchronous).
 *   4. **Wait for surfaceCreated before starting capture.** On-create is async.
 *   5. Never launch the target app from this service. The Launcher opens the target
 *      directly; this service only owns the independent LSFG overlay/session.
 */
class LsfgForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private var shizukuCapture: ShizukuCaptureEngine? = null
    private var rootCapture: RootCaptureEngine? = null
    private var overlay: OverlayManager? = null
    private var drawer: SettingsDrawerOverlay? = null
    private var targetPkgPending: String? = null
    // The target package for the current session, kept for the lifetime of the
    // session (unlike targetPkgPending, which is consumed/nulled once the target
    // app is launched). Reinit paths (geometry changes, etc.) need this after the
    // initial launch has already happened.
    private var activeTargetPackage: String? = null
    // Per-app display/background session state. The physical baseline is captured
    // once and is restored when the session ends, including normal app exits.
    private var sessionDisplayProfile: AppDisplayProfile? = null
    private var sessionDisplayApplied: Boolean = false
    // Set from the drawer's END SESSION confirmation (see requestStop() /
    // StopOverlayListener). Defaults to false so a service killed by the
    // system or stopped via any path other than that dialog still restores
    // the original screen size, as before — only an explicit "keep
    // resolution" choice suppresses the restore in onDestroy().
    @Volatile private var keepDisplayOnStop: Boolean = false
    private var sessionOriginalActivityManagerConstants: String? = null
    private var sessionBackgroundPolicyApplied: Boolean = false
    private var sessionAnimationsDisabled: Boolean = false
    private var sessionOriginalStayOnValue: Int? = null
    private var sessionStayAwakeApplied: Boolean = false
    // Performance extras — same apply-on-start/restore-on-stop pattern as the
    // display/background/animation/stay-awake flags above.
    private var sessionPerfModeApplied: Boolean = false
    private var sessionDozeWhitelisted: Boolean = false
    private var sessionRefreshRateApplied: Boolean = false
    private var sessionWifiLockApplied: Boolean = false
    private var initialCaptureStarted: Boolean = false
    private var lsfgContextActive: Boolean = false
    private var lastSurface: Surface? = null
    private var lastSurfaceW: Int = 0
    private var lastSurfaceH: Int = 0
    @Volatile
    private var activeRenderW: Int = 0
    @Volatile
    private var activeRenderH: Int = 0
    // Capture/context input size (post renderResolutionScale) and the backend
    // label, kept for the HUD "backend · in → out" line — see pushStreamInfo().
    @Volatile
    private var activeInputW: Int = 0
    @Volatile
    private var activeInputH: Int = 0
    @Volatile
    private var activeBackendLabel: String = ""
    @Volatile
    private var currentPostGpuEnabled: Boolean = false
    @Volatile
    private var reinitInFlight: Boolean = false
    // Signalled when the in-flight reinit thread finishes. Lets onDestroy() wait
    // for completion without busy-polling the Main thread (the previous code
    // burned ~75 cycles of Thread.sleep(20) before timeout, blocking the looper
    // and starving system input — causing visible freeze on swipe-out).
    @Volatile
    private var reinitDoneLatch: CountDownLatch? = null
    // When the user changes a parameter while a previous reinit is still in
    // flight, we can't start a second one concurrently (it would race on the
    // native context). Instead we mark a pending request and the in-flight
    // reinit re-runs itself once it finishes, picking up the freshest prefs.
    // Without this, mid-reinit changes were silently dropped — that's why
    // toggling "Bypass" appeared to "make settings apply": users were
    // accidentally triggering a second reinit by changing something else.
    @Volatile
    private var reinitRequested: Boolean = false
    @Volatile
    private var pendingReinitW: Int = 0
    @Volatile
    private var pendingReinitH: Int = 0
    // Set in onDestroy. While true, new reinit requests are dropped on the
    // floor — we're tearing down the service and any allocation we'd do here
    // would just have to be undone (and would race the shutdown).
    @Volatile
    private var shuttingDown: Boolean = false
    @Volatile
    private var pendingFpsCounter: Boolean = false
    private var pendingPrivilegedVideoStart: ShizukuVideoStart? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // HUD CPU/GPU/RAM readout — independent per-metric toggles (see
    // SettingsDrawerOverlay's "System stats" switches), sampled on a simple
    // timer rather than tied to capture/frame-gen listeners since these three
    // numbers change on their own schedule, not per rendered frame.
    @Volatile private var cpuStatEnabled: Boolean = false
    @Volatile private var gpuStatEnabled: Boolean = false
    @Volatile private var ramStatEnabled: Boolean = false
    private var statsPollRunnable: Runnable? = null
    private fun statsAnyEnabled() = cpuStatEnabled || gpuStatEnabled || ramStatEnabled

    private fun startStatsPollingIfNeeded() {
        if (statsPollRunnable != null) return
        if (!statsAnyEnabled()) return
        SystemStatsSampler.resetTrackers()
        val r = object : Runnable {
            override fun run() {
                val ov = overlay
                if (ov != null && statsAnyEnabled()) {
                    val stats = SystemStatsSampler.sample(applicationContext)
                    ov.updateStats(
                        cpuPercent = if (cpuStatEnabled) stats.cpuPercent else null,
                        gpuPercent = if (gpuStatEnabled) stats.gpuPercent else null,
                        ramUsedMb = if (ramStatEnabled) stats.ramUsedMb else null,
                        ramTotalMb = if (ramStatEnabled) stats.ramTotalMb else null,
                    )
                }
                if (statsPollRunnable === this) {
                    mainHandler.postDelayed(this, STATS_POLL_INTERVAL_MS)
                }
            }
        }
        statsPollRunnable = r
        mainHandler.post(r)
    }

    private fun stopStatsPollingIfIdle() {
        if (statsAnyEnabled()) return
        statsPollRunnable?.let { mainHandler.removeCallbacks(it) }
        statsPollRunnable = null
        overlay?.setStatsVisible(false)
    }

    private fun stopStatsPolling() {
        statsPollRunnable?.let { mainHandler.removeCallbacks(it) }
        statsPollRunnable = null
    }
    // Display rotation listener — Service.onConfigurationChanged only fires
    // for configChanges declared in the manifest, but services can't declare
    // them. DisplayManager.DisplayListener fires on every rotation regardless.
    private var displayListener: DisplayManager.DisplayListener? = null
    // Held for the lifetime of an active session so Doze/App Standby can't
    // deprioritize this process's CPU scheduling once the screen dims or the
    // device decides it's been idle — see the PARTIAL_WAKE_LOCK acquire in
    // onCreate for why FLAG_KEEP_SCREEN_ON-style approaches aren't enough on
    // their own (this app doesn't own the foreground Activity/Window, so it
    // has no window flag to set; a wake lock is the only lever available
    // from a background Service).
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The service only exists for the lifetime of a session (created on
        // ACTION_START, torn down in onDestroy), so "service alive" == "session
        // running". Flip this here rather than after MediaProjection/overlay
        // setup so the UI reflects "starting" immediately instead of lagging
        // behind the async startup sequence.
        _isRunning.value = true
        ensureChannel()
        registerDisplayListener()
        // PARTIAL_WAKE_LOCK keeps the CPU awake (screen/keyboard state
        // untouched) for as long as this session runs, regardless of Doze,
        // App Standby, or the screen dimming — all of which can otherwise
        // throttle this process's scheduling priority the moment the system
        // decides it's been idle. Explicitly requested as part of "pull full
        // performance, ignore the power/thermal tradeoffs" — this does cost
        // battery for the session's duration. acquire() with no timeout is
        // intentional; release() happens in onDestroy() alongside every
        // other teardown step, so it can never outlive the session.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:lsfg-session")
                .apply { setReferenceCounted(false) }
        }.onSuccess { lock ->
            wakeLock = lock
            runCatching { lock.acquire() }
                .onFailure { LsfgLog.w(TAG, "wakeLock.acquire() failed", it) }
        }.onFailure {
            LsfgLog.w(TAG, "newWakeLock() failed — continuing without it", it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Belt-and-braces: in addition to DisplayListener we also handle the
        // platform configuration callback so very quick rotations don't slip
        // through (some OEMs deliver one but not the other).
        propagateDisplayChange()
    }

    // Logged so field logs can confirm/deny the low-memory-killer hypothesis
    // when a session's log simply stops mid-frame with no shutdown message
    // (the process was SIGKILLed, not stopped through our own teardown path).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LsfgLog.w(TAG, "onTrimMemory level=$level — system is reclaiming memory, session may be killed soon")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LsfgLog.w(TAG, "onLowMemory — system-wide low memory, session may be killed soon")
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                propagateDisplayChange()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { LsfgLog.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = getSystemService(DisplayManager::class.java) ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun propagateDisplayChange() {
        // Always run on the main thread — WindowManager rejects updateViewLayout
        // calls from binder threads on some OEMs, and OverlayManager /
        // SettingsDrawerOverlay both touch the WM internally.
        mainHandler.post {
            runCatching { overlay?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "overlay.onDisplayConfigurationChanged failed", it) }
            runCatching { drawer?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "drawer.onDisplayConfigurationChanged failed", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LsfgLog.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                LsfgLog.i(TAG, "ACTION_STOP received — stopSelf()")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LsfgLog.i(TAG, "onDestroy — tearing down service (caller triggered stopSelf or system killed us)")
        // Flip first: teardown below can take up to ~1.5s (reinit latch wait),
        // and the Home screen's Start/Stop button should reflect "not running"
        // the moment shutdown begins, not after every native resource is freed.
        _isRunning.value = false
        super.onDestroy()
        unregisterDisplayListener()
        stopStatsPolling()
        // Block any further reinit requests and wait for one already in flight
        // to finish. Without this, a parameter change happening concurrently
        // with stopSelf() races destroyContext() on the C++ side: the reinit
        // worker is in the middle of initRenderLoop(), allocating AHB images
        // and starting the worker thread, while onDestroy calls
        // shutdownRenderLoop() which joins that same worker and frees the
        // images — SIGSEGV on the next access. This was the "stop overlay
        // crashes when settings stop applying" symptom.
        shuttingDown = true
        // Wait on the latch instead of polling — frees the Main thread looper
        // to deliver pending input events instead of sleeping in 20 ms ticks.
        val latch = reinitDoneLatch
        if (latch != null && reinitInFlight) {
            val finished = latch.await(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                LsfgLog.w(TAG, "onDestroy: reinit still in flight after 1.5s — proceeding anyway")
            }
        }
        capture?.stop()
        capture = null
        shizukuCapture?.stop()
        shizukuCapture = null
        rootCapture?.stop()
        rootCapture = null
        runCatching { NativeBridge.setShizukuTimingEnabled(false) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled(false) failed during teardown", it) }
        if (lsfgContextActive) {
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            runCatching { NativeBridge.destroyContext() }
            lsfgContextActive = false
        }
        drawer?.hide()
        drawer = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure { LsfgLog.w(TAG, "wakeLock.release() failed", it) }
        wakeLock = null

        // Restore global display/background state last, after all capture/overlay
        // resources have stopped. This prevents a session's temporary resolution
        // or cached-process limit from leaking into the rest of Android.
        if (sessionDisplayApplied) {
            if (!keepDisplayOnStop) {
                runCatching { AdbDisplayController.reset(applicationContext) }
                    .onFailure { LsfgLog.w(TAG, "Failed to restore original display size/density", it) }
            } else {
                LsfgLog.i(TAG, "keepDisplayOnStop=true — leaving resolution/DPI override in place")
            }
            activeTargetPackage?.let { DisplayOverrideState.clearIfOwner(applicationContext, it) }
        }
        if (sessionBackgroundPolicyApplied) {
            runCatching { AdbDisplayController.restoreActivityManagerConstants(applicationContext, sessionOriginalActivityManagerConstants) }
                .onFailure { LsfgLog.w(TAG, "Failed to restore activity manager constants", it) }
        }
        if (sessionAnimationsDisabled) {
            runCatching { AdbDisplayController.setAnimationsEnabled(applicationContext, true) }
                .onFailure { LsfgLog.w(TAG, "Failed to restore system animation scale", it) }
        }
        if (sessionStayAwakeApplied) {
            runCatching {
                AdbDisplayController.setStayOnWhilePluggedIn(applicationContext, sessionOriginalStayOnValue ?: 0)
            }.onFailure { LsfgLog.w(TAG, "Failed to restore stay-awake setting", it) }
        }
        if (sessionPerfModeApplied) {
            runCatching { AdbDisplayController.setFixedPerformanceMode(false) }
                .onFailure { LsfgLog.w(TAG, "Failed to disable fixed performance mode", it) }
        }
        if (sessionDozeWhitelisted) {
            activeTargetPackage?.let { pkg ->
                runCatching { AdbDisplayController.setDozeWhitelist(pkg, false) }
                    .onFailure { LsfgLog.w(TAG, "Failed to remove doze whitelist entry", it) }
            }
        }
        if (sessionRefreshRateApplied) {
            runCatching { AdbDisplayController.setPeakRefreshRate(applicationContext, 0) }
                .onFailure { LsfgLog.w(TAG, "Failed to clear refresh rate override", it) }
        }
        if (sessionWifiLockApplied) {
            runCatching { WifiPerfLock.release() }
                .onFailure { LsfgLog.w(TAG, "Failed to release wifi high-perf lock", it) }
        }
        sessionDisplayApplied = false
        sessionBackgroundPolicyApplied = false
        sessionAnimationsDisabled = false
        sessionStayAwakeApplied = false
        sessionOriginalStayOnValue = null
        sessionDisplayProfile = null
        sessionOriginalActivityManagerConstants = null
        sessionPerfModeApplied = false
        sessionDozeWhitelisted = false
        sessionRefreshRateApplied = false
        sessionWifiLockApplied = false

        // The session is gone — the launcher dot may want to re-appear if the
        // target app is still in the foreground.
        AutoOverlayController.onSessionStopped(applicationContext)
    }

    private fun handleStart(intent: Intent) {
        val captureSource = CaptureSource.fromPref(intent.getStringExtra(EXTRA_CAPTURE_SOURCE))
        val isPrivilegedCapture = captureSource == CaptureSource.SHIZUKU || captureSource == CaptureSource.ROOT
        runCatching { NativeBridge.setShizukuTimingEnabled(isPrivilegedCapture) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled($isPrivilegedCapture) failed", it) }
        val usesMediaProjectionVideo = captureSource == CaptureSource.MEDIA_PROJECTION
        // Pick the FGS type that matches what we'll actually do this session.
        // - MediaProjection capture → FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        //   requires a live projection token (handled below) and the matching
        //   uses-permission in the manifest.
        // - Shizuku capture → FOREGROUND_SERVICE_TYPE_SPECIAL_USE. Without
        //   this, Android 14+/15+ rejects startForeground with
        //   "Starting FGS with type mediaProjection ... requires CAPTURE_VIDEO_OUTPUT
        //   or android:project_media" because the manifest declares
        //   foregroundServiceType="mediaProjection|specialUse" and the system
        //   defaults to the first declared type when none is passed.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (usesMediaProjectionVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val initialFpsCounter = intent.getBooleanExtra(EXTRA_FPS_COUNTER, false)
        if (usesMediaProjectionVideo && (data == null || resultCode == 0)) {
            LsfgLog.e(TAG, "Missing MediaProjection result intent; stopping")
            stopSelf()
            return
        }

        val proj = if (usesMediaProjectionVideo) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(resultCode, data!!).also {
                projection = it
                // Android 14 (API 34) requires a non-null Handler for registerCallback on
                // some OEMs (MediaTek/PowerVR devices observed revoking the projection
                // token instantly when callback handler is null). Register BEFORE any
                // VirtualDisplay is created so we also hear about system-initiated stops.
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        LsfgLog.i(TAG, "MediaProjection.onStop")
                        stopSelf()
                    }
                }, mainHandler)
            }
        } else {
            null
        }

        val ov = OverlayManager(this)
        overlay = ov
        targetPkgPending = targetPkg
        activeTargetPackage = targetPkg
        initialCaptureStarted = false

        // Apply the launcher card's per-app display policy before the overlay
        // surface is created. We always keep the stored physical dimensions as
        // the output/present size; only capture + frame-gen input are reduced.
        if (targetPkg != null && AdbDisplayController.isReady(applicationContext)) {
            runCatching {
                val current = AdbDisplayController.readDisplay(applicationContext)
                if (current != null) {
                    val stored = AppDisplayProfileStore.captureOriginalIfMissing(this, targetPkg, current)
                    sessionDisplayProfile = stored
                    val needsBackgroundPolicy =
                        stored.originalWidth > 0 && (stored.dynamicClean || stored.maxBackgroundApps > 1 || stored.enabled)
                    if (needsBackgroundPolicy) {
                        sessionOriginalActivityManagerConstants =
                            AdbDisplayController.getActivityManagerConstants(applicationContext)
                        AdbDisplayController.setMaxBackgroundApps(applicationContext, stored.maxBackgroundApps)
                        sessionBackgroundPolicyApplied = true
                        if (stored.dynamicClean) AdbDisplayController.killCachedProcesses()
                    }
                    if (stored.originalWidth > 0 && stored.originalHeight > 0) {
                        // Mark the session as owning display state even at 100%, so
                        // a stale override from a previous interrupted run is reset.
                        sessionDisplayApplied = true
                        if (stored.enabled && stored.percent < 100) {
                            val applied = AdbDisplayController.apply(applicationContext, stored)
                            if (applied) {
                                DisplayOverrideState.markApplied(applicationContext, targetPkg)
                                LsfgLog.i(
                                    TAG,
                                    "Per-app display ${stored.percent}% -> " +
                                        "${stored.calculatedWidth}x${stored.calculatedHeight} @ ${stored.calculatedDpi}dpi; " +
                                        "present stays ${stored.originalWidth}x${stored.originalHeight}"
                                )
                            } else {
                                LsfgLog.w(TAG, "Per-app display override failed; continuing at native resolution")
                            }
                        } else {
                            AdbDisplayController.reset(applicationContext)
                            DisplayOverrideState.clearIfOwner(applicationContext, targetPkg)
                        }
                    }
                    if (stored.disableAnimations) {
                        sessionAnimationsDisabled = AdbDisplayController.setAnimationsEnabled(applicationContext, false)
                    }
                    if (stored.keepAwake) {
                        sessionOriginalStayOnValue = AdbDisplayController.getStayOnWhilePluggedIn(applicationContext)
                        sessionStayAwakeApplied = AdbDisplayController.enableStayAwake(applicationContext)
                    }
                    // Performance extras — best-effort; each setter returns false
                    // (and logs) instead of throwing when Shizuku isn't available,
                    // so a missing Shizuku grant never blocks session start.
                    if (stored.forceStopBackground) {
                        AdbDisplayController.forceStopOtherApps(applicationContext, targetPkg)
                    }
                    if (stored.fixedPerformanceMode) {
                        sessionPerfModeApplied = AdbDisplayController.setFixedPerformanceMode(true)
                    }
                    if (stored.dozeWhitelist) {
                        sessionDozeWhitelisted = AdbDisplayController.setDozeWhitelist(targetPkg, true)
                    }
                    if (stored.lockRefreshRateHz > 0) {
                        sessionRefreshRateApplied =
                            AdbDisplayController.setPeakRefreshRate(applicationContext, stored.lockRefreshRateHz)
                    }
                    if (stored.wifiHighPerfLock) {
                        WifiPerfLock.acquire(applicationContext)
                        sessionWifiLockApplied = true
                    }
                }
            }.onFailure { LsfgLog.w(TAG, "Per-app display policy failed", it) }
        }

        // Tell the Automatic Overlay controller that the full session has started:
        // it hides the floating dot until we tear down again.
        AutoOverlayController.onSessionStarted(targetPkg)

        // Tell the Automatic Overlay controller that the full session has started:
        // it hides the floating dot until we tear down again.
        AutoOverlayController.onSessionStarted(targetPkg)

        val cap = if (proj != null) CaptureEngine(this, proj) else null
        capture = cap
        val shizukuCap = if (captureSource == CaptureSource.SHIZUKU) {
            ShizukuCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        shizukuCapture = shizukuCap
        val rootCap = if (captureSource == CaptureSource.ROOT) {
            RootCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        rootCapture = rootCap
        cap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        cap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        shizukuCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        shizukuCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        rootCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        rootCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        // Apply persisted LSFG on/off preference before the first frame is captured. The drawer
        // toggle persists `lsfgEnabled`; we mirror that into the bypass path (lsfgEnabled=false ⇒
        // bypass=true ⇒ raw passthrough).
        val persistedLsfgEnabled = com.firstt175.deepdrop.prefs.LsfgPreferences(this).load().lsfgEnabled
        if (!persistedLsfgEnabled) {
            cap?.frameGenBypass = true
            runCatching { NativeBridge.setBypass(true) }
                .onFailure { LsfgLog.w(TAG, "initial setBypass failed", it) }
            ov.updateStatus("LSFG-Android+: bypass (raw capture)")
        }
        // NOTE: deferring the initial FPS-counter wiring until AFTER ov.show() —
        // setFpsVisible() is a no-op when ov.fpsView hasn't been created yet,
        // and the view is only created inside show().

        ov.onSurfaceReady { surface, w, h ->
            // Android delivers surfaceCreated immediately followed by surfaceChanged
            // on the same Surface instance. Retargeting the VirtualDisplay onto the
            // identical Surface a second time stops frame delivery on PowerVR drivers
            // (the overlay freezes on the first frame). Coalesce duplicate ready
            // events when nothing actually changed.
            val sameAsLast = surface === lastSurface && w == lastSurfaceW && h == lastSurfaceH
            LsfgLog.i(TAG, "onSurfaceReady ${w}x${h} initial=$initialCaptureStarted sameAsLast=$sameAsLast")
            if (sameAsLast && initialCaptureStarted) {
                LsfgLog.i(TAG, "onSurfaceReady coalesced — identical Surface, skipping retarget")
                return@onSurfaceReady
            }
            lastSurface = surface
            lastSurfaceW = w
            lastSurfaceH = h
            // IMPORTANT: `w`/`h` must never be treated as the presentation
            // resolution. Android reports the CURRENT display/surface geometry,
            // which changes after `wm size` is applied. The per-app profile has
            // already captured the real panel size before that override, and
            // that saved size is the presentation/output size for the whole
            // session.
            //
            // Example: physical panel 1080x2408, display override 480x1068:
            //   input/capture  = 480x1068 (or the selected render scale)
            //   presentation   = 1080x2408 (saved original size)
            //
            // Keeping these two domains separate prevents the generated frame
            // from being silently re-targeted to the temporary low resolution.
            val presentationW = sessionDisplayProfile
                ?.originalWidth
                ?.takeIf { it > 0 } ?: w
            val presentationH = sessionDisplayProfile
                ?.originalHeight
                ?.takeIf { it > 0 } ?: h

            LsfgLog.i(
                TAG,
                "Output geometry: surface=${w}x${h}, presentation=${presentationW}x${presentationH}" +
                    " (saved original; display override must not change output)",
            )

            // Always tell native about the PRESENTATION surface dimensions, not
            // the current forced display dimensions.
            runCatching { NativeBridge.setOutputSurface(surface, presentationW, presentationH) }
                .onFailure { LsfgLog.w(TAG, "setOutputSurface failed", it) }

            if (!initialCaptureStarted) {
                initialCaptureStarted = true
                // CRITICAL ORDER (Android 14+ MediaTek/PowerVR):
                // getMediaProjection() opens a short window (~200 ms on some OEMs)
                // during which we MUST call createVirtualDisplay, or the system
                // revokes the projection with MediaProjection.onStop. We used to
                // run Vulkan init (100-200 ms) before the first createVirtualDisplay
                // and occasionally blew past that deadline. In MediaProjection
                // mode start capture first on the LSFG ImageReader so the token
                // is consumed immediately without ever mirroring the visible
                // overlay surface back into MediaProjection. On Orange Pi /
                // RK3588 Android builds, that mirror bootstrap can make the
                // framegen path capture its own overlay frames.
                val cfg = LsfgPreferences(this).load()
                // Render resolution scale shrinks the actual capture/context buffers
                // (not just a post-process pass), so both the ImageReader/VirtualDisplay
                // size below and the native context width/height must use the same
                // scaled dimensions — activeRenderW/H and the output surface stay at
                // the full display size (see the geometry-change check further down).
                val (scaledW, scaledH) = scaledRenderSize(presentationW, presentationH, cfg.renderResolutionScale)

                if (cap != null) {
                    LsfgLog.i(TAG, "Starting ImageReader capture first to consume MediaProjection token")
                    cap.setLsfgNativeInputEnabled(false)
                    cap.lowLatencyCapture = cfg.lowLatencyCapture
                    cap.setLsfgMode(scaledW, scaledH)
                }
                ov.updateStatus("LSFG-Android+: starting ${scaledW}×${scaledH} (input) → ${presentationW}×${presentationH} output…")

                val cacheDir = File(filesDir, "spirv").absolutePath
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio),
                )
                val ai = aiBackendArgs(cfg)
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = scaledW,
                        height = scaledH,
                        multiplier = effectiveMultiplier(cfg),
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        framegenFp16 = cfg.framegenFp16,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        aiBackend = ai.enabled,
                        aiModelDir = ai.modelDir,
                        aiEngine = ai.engine,
                    )
                }.getOrElse { e ->
                    LsfgLog.w(TAG, "initContext threw", e)
                    -1
                }
                when {
                    rc == 0 -> {
                        // Framegen active: the ImageReader path is already running;
                        // now allow frames into the native render loop.
                        lsfgContextActive = true
                        activeRenderW = presentationW
                        activeRenderH = presentationH
                        activeInputW = scaledW
                        activeInputH = scaledH
                        activeBackendLabel = backendLabel(cfg, ai)
                        pushStreamInfo()
                        cap?.setLsfgNativeInputEnabled(true)
                        if (isPrivilegedCapture) {
                            // Frame-gen is active and its native context was sized to
                            // scaledW/scaledH (see initContext above) — the privileged
                            // video capture must deliver frames at that same size, not
                            // the full display resolution.
                            pendingPrivilegedVideoStart = ShizukuVideoStart(scaledW, scaledH, cfg)
                        }
                        ov.updateStatus("LSFG-Android+: frame-gen active ${scaledW}×${scaledH} → ${presentationW}×${presentationH} ×${cfg.multiplier}")
                    }
                    rc > 0 -> {
                        LsfgLog.w(TAG, "initContext rc=$rc — framegen disabled, staying in mirror mode")
                        lsfgContextActive = true
                        if (isPrivilegedCapture) {
                            activeRenderW = 0
                            activeRenderH = 0
                            pendingPrivilegedVideoStart = ShizukuVideoStart(presentationW, presentationH, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG-Android+: $label mirror ${presentationW}×${presentationH} (GPU lacks required Vulkan ext)")
                        } else if (cap != null) {
                            cap.setSurface(surface, presentationW, presentationH)
                            activeRenderW = 0
                            activeRenderH = 0
                            // Retarget the bootstrap ImageReader capture to mirror
                            // mode because framegen is unavailable.
                            ov.updateStatus("LSFG-Android+: mirror ${presentationW}×${presentationH} (GPU lacks required Vulkan ext)")
                        } else {
                            activeRenderW = 0
                            activeRenderH = 0
                            ov.updateStatus("LSFG-Android+: frame-gen unavailable (init rc=$rc)")
                        }
                    }
                    else -> {
                        LsfgLog.w(TAG, "initContext failed rc=$rc — staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (isPrivilegedCapture && rc > 0) {
                            pendingPrivilegedVideoStart = ShizukuVideoStart(presentationW, presentationH, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG-Android+: $label mirror active ${presentationW}×${presentationH} (init rc=$rc)")
                        } else if (cap != null) {
                            ov.updateStatus("LSFG-Android+: mirror active ${presentationW}×${presentationH} (init rc=$rc)")
                        } else {
                            ov.updateStatus("LSFG-Android+: init failed (rc=$rc)")
                        }
                    }
                }
                // IMPORTANT: the Frame Generation service must never launch the target app.
                // The Launcher owns app launching and opens the selected package directly.
                // This service is started independently by the overlay/session flow after
                // the target app is already in the foreground. Keeping launchTarget() here
                // caused START_SESSION to intercept the launcher flow and block/delay entry
                // into the target app.
                val pkg = activeTargetPackage ?: targetPkgPending
                LsfgLog.i(TAG, "Session ready; target launch is owned by Launcher: pkg=$pkg")

                if (pkg != null) {
                    pendingPrivilegedVideoStart?.let { start ->
                        pendingPrivilegedVideoStart = null
                        mainHandler.postDelayed({
                            startShizukuVideo(shizukuCap, pkg, start.width, start.height, start.cfg)
                            startRootVideo(rootCap, pkg, start.width, start.height, start.cfg)
                        }, 500L)
                    }

                    // The target app is already open. Only keep the LSFG overlay above it.
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 150)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 600)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 1500)
                } else {
                    LsfgLog.w(TAG, "No target package set — session starts without target metadata")
                }
                // FPS counter no longer creates a VirtualDisplay; it piggybacks on
                // the main LSFG-mode ImageReader. Safe to start immediately.
                if (pendingFpsCounter) {
                    pendingFpsCounter = false
                    runCatching {
                        when {
                            captureSource == CaptureSource.SHIZUKU -> shizukuCap?.startFpsCounter()
                            captureSource == CaptureSource.ROOT -> rootCap?.startFpsCounter()
                            else -> cap?.startFpsCounter()
                        }
                    }.onFailure { LsfgLog.w(TAG, "startFpsCounter failed", it) }
                }
            } else if (!lsfgContextActive || activeRenderW == 0) {
                // Mirror mode (framegen disabled or context not yet active): retarget
                // the existing VirtualDisplay onto the new Surface. activeRenderW==0
                // is our "running in mirror" sentinel — don't try to reinit the
                // render loop, just keep the capture alive.
                cap?.setSurface(surface, presentationW, presentationH)
            } else {
                // We already passed the sameAsLast coalescing check above, so a
                // genuinely new Surface or size reached us here (rotation, a
                // fresh SurfaceView instance, etc.). Previously this branch only
                // reinitialized when presentationW/H (the FIXED saved physical
                // size — see the comment above) differed from activeRenderW/H,
                // which never happens on a pure orientation change: the WM
                // overlay's live Surface gets swapped dimensions on rotation,
                // but the saved "original" presentation size does not track
                // that swap, so the old check silently no-opped and left the
                // native swapchain built against stale/mismatched geometry
                // (symptom: overlay goes blank after rotating until the user
                // manually taps "Apply changes", which calls reinitLsfgContext()
                // unconditionally). Just always reinit here — it's the same
                // repair "Apply changes" performs, so rotation now self-heals
                // the same way.
                LsfgLog.i(
                    TAG,
                    "New surface/geometry ${lastSurfaceW}x${lastSurfaceH} " +
                        "(presentation ${presentationW}x${presentationH}); reinitializing LSFG context",
                )
                reinitLsfgContext(presentationW, presentationH)
            }
        }
        ov.onSurfaceLost {
            LsfgLog.i(TAG, "onSurfaceLost — detaching output until a new Surface arrives")
            lastSurface = null
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            if (!lsfgContextActive) {
                capture?.clearSurface()
            }
        }
        val presentProfile = sessionDisplayProfile
        if (sessionDisplayApplied && presentProfile != null) {
            ov.show(presentProfile.originalWidth, presentProfile.originalHeight)
        } else {
            ov.show()
        }
        ov.showLoading("Loading…")

        // Now that ov.show() has actually created the FPS TextView, we can safely
        // make the UI visible. The actual counter (second VirtualDisplay) is
        // started AFTER the main capture is running — on Android 14 MediaTek/
        // PowerVR the system revokes MediaProjection if a second VirtualDisplay
        // is created while the first token is still unconsumed.
        //
        // Keep the FPS counter visible when the user requested it so the
        // overlay stays useful without the old benchmark-only path.
        val effectiveFpsCounter = initialFpsCounter
        if (effectiveFpsCounter) {
            ov.setFpsVisible(true)
        }
        pendingFpsCounter = effectiveFpsCounter

        // Frame pacing graph is purely a diagnostic overlay — no intent extra needed,
        // read the persisted pref directly so it restores across service restarts.
        val initialFrameGraph = LsfgPreferences(this).load().frameGraphEnabled
        if (initialFrameGraph) {
            ov.setFrameGraphVisible(true)
            when {
                captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                else -> capture?.startFrameGraph()
            }
        }

        // HUD CPU/GPU/RAM readout — same persisted-pref restore pattern as the
        // frame pacing graph above, but driven by its own poll timer instead of
        // a capture-engine listener.
        val statsPrefs = LsfgPreferences(this).load()
        val initialCpuStat = statsPrefs.cpuStatEnabled
        val initialGpuStat = statsPrefs.gpuStatEnabled
        val initialRamStat = statsPrefs.ramStatEnabled
        cpuStatEnabled = initialCpuStat
        gpuStatEnabled = initialGpuStat
        ramStatEnabled = initialRamStat
        if (statsAnyEnabled()) {
            ov.setStatsVisible(true)
            startStatsPollingIfNeeded()
        }

        // The main overlay and drawer both stay in TYPE_APPLICATION_OVERLAY so
        // the drawer/icon remains visible above the full-screen output surface.
        val overlayMode = LsfgPreferences(this).load().overlayMode
        val dr = SettingsDrawerOverlay(this, overlayMode)
        dr.setBypassListener { bypass ->
            LsfgLog.i(TAG, "frameGenBypass=$bypass")
            capture?.frameGenBypass = bypass
            runCatching { NativeBridge.setBypass(bypass) }
                .onFailure { LsfgLog.w(TAG, "setBypass failed", it) }
            ov.updateStatus(if (bypass) "LSFG-Android+: bypass (raw capture)" else "LSFG-Android+: frame-gen active")
        }
        dr.setStopOverlayListener { resetDisplay ->
            LsfgLog.i(TAG, "Stop overlay requested from drawer (resetDisplay=$resetDisplay)")
            keepDisplayOnStop = !resetDisplay
            stopSelf()
        }
        dr.setRestartSessionListener {
            val target = activeTargetPackage
            LsfgLog.i(TAG, "Restart session requested from drawer (target=$target)")
            if (target != null) {
                // Re-request MediaProjection consent (Android requires a fresh token
                // per session; the service can't reuse the current one) and relaunch
                // for the same target app once the user grants it. Keep the current
                // display override in place — this is a restart, not an end.
                keepDisplayOnStop = true
                val appCtx = applicationContext
                val intent = com.firstt175.deepdrop.ui.ProjectionRequestActivity.buildIntent(appCtx, target)
                appCtx.startActivity(intent)
            }
            stopSelf()
        }
        dr.setDisplayProfileActive(sessionDisplayApplied)
        dr.setFpsCounterListener { enabled ->
            LsfgLog.i(TAG, "fpsCounter=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFpsCounter()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFpsCounter()
                    else -> capture?.startFpsCounter()
                }
                overlay?.setFpsVisible(true)
            } else {
                capture?.stopFpsCounter()
                shizukuCapture?.stopFpsCounter()
                rootCapture?.stopFpsCounter()
                overlay?.setFpsVisible(false)
            }
        }
        dr.setFrameGraphListener { enabled ->
            LsfgLog.i(TAG, "frameGraph=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                    else -> capture?.startFrameGraph()
                }
                overlay?.setFrameGraphVisible(true)
            } else {
                capture?.stopFrameGraph()
                shizukuCapture?.stopFrameGraph()
                rootCapture?.stopFrameGraph()
                overlay?.setFrameGraphVisible(false)
            }
        }
        dr.setCpuStatListener { enabled ->
            LsfgLog.i(TAG, "cpuStat=$enabled")
            cpuStatEnabled = enabled
            if (enabled) {
                overlay?.setStatsVisible(true)
                startStatsPollingIfNeeded()
            } else {
                stopStatsPollingIfIdle()
                overlay?.setStatsVisible(statsAnyEnabled())
            }
        }
        dr.setGpuStatListener { enabled ->
            LsfgLog.i(TAG, "gpuStat=$enabled")
            gpuStatEnabled = enabled
            if (enabled) {
                overlay?.setStatsVisible(true)
                startStatsPollingIfNeeded()
            } else {
                stopStatsPollingIfIdle()
                overlay?.setStatsVisible(statsAnyEnabled())
            }
        }
        dr.setRamStatListener { enabled ->
            LsfgLog.i(TAG, "ramStat=$enabled")
            ramStatEnabled = enabled
            if (enabled) {
                overlay?.setStatsVisible(true)
                startStatsPollingIfNeeded()
            } else {
                stopStatsPollingIfIdle()
                overlay?.setStatsVisible(statsAnyEnabled())
            }
        }
        dr.setInitialFpsCounterState(effectiveFpsCounter)
        dr.setInitialFrameGraphState(initialFrameGraph)
        dr.setInitialCpuStatState(initialCpuStat)
        dr.setInitialGpuStatState(initialGpuStat)
        dr.setInitialRamStatState(initialRamStat)
        dr.setLiveParamsListener {
            reinitLsfgContext()
        }
        dr.show()
        drawer = dr
    }

    /**
     * Tear down and re-create the native LSFG context so a parameter change from
     * the live drawer (multiplier, flow scale, performance/HDR switch) actually
     * takes effect. The shaders and pipeline state are baked at initContext time;
     * there's no in-place update path.
     *
     * Runs on a worker thread because destroyContext blocks on vkDeviceWaitIdle
     * and initContext can take 100-300ms while it recompiles the shader chain
     * (observed up to ~1-3s in the field, since it also tears down/recreates the
     * whole Vulkan session and swapchain — see SettingsDrawerOverlay's frame
     * profile logs). Because each pass is this expensive, the drawer batches
     * control changes locally (SettingsDrawerOverlay.markParamsDirty()) and only
     * calls this once, when the user taps "Apply changes" — NOT on every slider
     * tick. This function always re-reads the latest saved prefs regardless of
     * who triggered it, so a rotation-driven call (see onSurfaceReady's geometry-
     * change branch) picks up whatever was last applied from the drawer too.
     */
    /**
     * Resolves the initContext() AI-backend arguments from [cfg]. AI mode is
     * requested whenever the user has selected [FramegenBackend.NCNN_AI] —
     * which engine actually runs is [LsfgConfig.aiEngine] (RIFE or IFRNet,
     * see [com.firstt175.deepdrop.prefs.AiEngine]). Both engines' models ship as
     * bundled APK assets ([BundledRifeModel]/[BundledIfrnetModel]), not
     * something the user has to import first, so there's no separate
     * "ready" gate to check beyond the extraction itself succeeding. If
     * extraction fails (corrupt install, out of disk space, ...) this falls
     * back to the LSFG shader path instead of failing initContext outright,
     * matching how a missing/corrupt Lossless.dll already degrades to mirror
     * mode elsewhere in this file.
     * The model directory is engine-specific — `ctx.filesDir/ai_model` for
     * RIFE ([BundledRifeModel.ensureExtracted]) or
     * `ctx.filesDir/ai_model_ifrnet` for IFRNet
     * ([BundledIfrnetModel.ensureExtracted]) — see each interpolator's
     * load() doc comment for the expected file names within.
     */
    /**
     * The multiplier actually sent to the native render loop. The AI (ncnn) backend is
     * hard-locked to ×2 here regardless of what's stored in prefs — RIFE/IFRNet are only
     * validated for the single-midpoint case, and ParamsScreen already disables/forces the
     * multiplier slider back to 2 while this backend is selected, but this clamp keeps the
     * session honest even if a stale/out-of-range value ever reaches [LsfgConfig.multiplier]
     * (e.g. a value saved before this lock existed, or set outside the UI).
     */
    private fun effectiveMultiplier(cfg: LsfgConfig): Int =
        if (cfg.framegenBackend == FramegenBackend.NCNN_AI) 2 else cfg.multiplier

    private fun aiBackendArgs(cfg: LsfgConfig): AiBackendArgs {
        val wantsAi = cfg.framegenBackend == FramegenBackend.NCNN_AI
        val customDir = cfg.activeModelDir?.let(::File)
        val customEngine = cfg.activeModelEngine

        // HARD RULE: an explicitly selected model is authoritative. Do not
        // silently switch to a bundled asset if the selected MY MODEL/ASSET MODEL
        // is missing, corrupt, or has the wrong engine. The native side receives
        // only this selected directory; failure is reported instead of fallback.
        if (wantsAi && customDir != null) {
            if (customEngine == null) {
                LsfgLog.e(TAG, "Selected AI model has no engine; refusing bundled fallback")
                return AiBackendArgs(true, customDir.absolutePath, cfg.aiEngine.nativeValue)
            }
            val param = if (customEngine == 1) File(customDir, "ifrnet.param") else File(customDir, "flownet.param")
            val bin = if (customEngine == 1) File(customDir, "ifrnet.bin") else File(customDir, "flownet.bin")
            if (!customDir.isDirectory || param.length() <= 0 || bin.length() <= 0) {
                LsfgLog.e(TAG, "Selected AI model is invalid; refusing bundled fallback: ${customDir.absolutePath}")
                return AiBackendArgs(true, customDir.absolutePath, customEngine)
            }
            return AiBackendArgs(true, customDir.absolutePath, customEngine)
        }
        val requested = wantsAi && when (cfg.aiEngine) {
            AiEngine.IFRNET -> BundledIfrnetModel.ensureExtracted(this, cfg.ifrnetModel)
            AiEngine.RIFE -> BundledRifeModel.ensureExtracted(this, cfg.rifeModel)
        }
        val modelDir = when (cfg.aiEngine) {
            AiEngine.IFRNET -> BundledIfrnetModel.modelDir(this, cfg.ifrnetModel)
            AiEngine.RIFE -> BundledRifeModel.modelDir(this, cfg.rifeModel)
        }
        return AiBackendArgs(requested, if (requested) modelDir.absolutePath else "", cfg.aiEngine.nativeValue)
    }

    private data class AiBackendArgs(
        val enabled: Boolean,
        val modelDir: String,
        val engine: Int,
    )

    /**
     * Human-readable label for the HUD's "backend" line. Reflects what actually
     * ended up running — not just what's selected in prefs — so a silent
     * AI→DLL fallback (model missing, engine unavailable, etc.) shows up in
     * the overlay instead of lying to the user about which path is active.
     */
    private fun backendLabel(cfg: LsfgConfig, ai: AiBackendArgs): String {
        val wantsAi = cfg.framegenBackend == FramegenBackend.NCNN_AI
        return when {
            wantsAi && ai.enabled -> "AI (${cfg.aiEngine.name})"
            wantsAi -> "AI requested, DLL fallback"
            else -> "LSFG-DLL"
        }
    }

    /**
     * Pushes the current backend + input→output resolution to the HUD. Call
     * this any time activeInputW/H, activeRenderW/H, or the backend changes —
     * i.e. right after a successful (or fallback) initContext, both on first
     * start and on reinit. Cheap and idempotent; the overlay only redraws
     * this line, not the whole fps text, so it's safe to call often.
     */
    private fun pushStreamInfo() {
        if (activeInputW <= 0 || activeInputH <= 0 || activeRenderW <= 0 || activeRenderH <= 0) return

        // HUD deliberately reports only the frame pipeline input/output sizes.
        // Do NOT expose the post-processing/upscale resolution here: the
        // upscale stage is an internal post-process detail, not the frame-gen
        // output resolution shown to the user.
        val postLabel = if (currentPostGpuEnabled) " · POST" else ""
        val line = "$activeBackendLabel$postLabel · Input: ${activeInputW}×${activeInputH} → Output: ${activeRenderW}×${activeRenderH}"
        overlay?.setStreamInfo(line)
    }

    /**
     * Applies [LsfgPreferences.renderResolutionScale] to a display/surface size,
     * clamped to [LsfgPreferences.MIN_RENDER_RESOLUTION_SCALE] so capture/context
     * buffers never collapse to 0 pixels. Both dimensions are floored at 1px.
     */
    private fun scaledRenderSize(w: Int, h: Int, scale: Float): Pair<Int, Int> {
        val s = scale.coerceIn(LsfgPreferences.MIN_RENDER_RESOLUTION_SCALE, 1.0f)
        val sw = (w * s).toInt().coerceAtLeast(1)
        val sh = (h * s).toInt().coerceAtLeast(1)
        return sw to sh
    }

    private fun reinitLsfgContext(width: Int = lastSurfaceW, height: Int = lastSurfaceH) {
        if (shuttingDown) {
            LsfgLog.i(TAG, "reinitLsfgContext skipped — shutting down")
            return
        }
        val cap = capture
        val ov = overlay ?: return
        if (width == 0 || height == 0) {
            LsfgLog.w(TAG, "reinitLsfgContext skipped — no surface yet")
            return
        }
        // Coalesce concurrent requests: if a reinit is already running, just
        // mark that another one is wanted. The running worker will pick up the
        // newest prefs in a follow-up pass before clearing the in-flight flag.
        if (reinitInFlight) {
            pendingReinitW = width
            pendingReinitH = height
            LsfgLog.i(TAG, "reinitLsfgContext queued ${width}x${height} while another pass is running")
            reinitRequested = true
            return
        }
        reinitInFlight = true
        reinitRequested = false
        pendingReinitW = width
        pendingReinitH = height
        val doneLatch = CountDownLatch(1)
        reinitDoneLatch = doneLatch
        Thread {
            try {
            val cacheDir = File(filesDir, "spirv").absolutePath
            // Drain any pending requests that arrived while we were running.
            // Each pass re-reads prefs so the final native state matches the
            // most recent UI value, even if the user spammed slider releases.
            var pass = 0
            do {
                reinitRequested = false
                pass++
                // Keep the two resolution domains separate:
                // - input/capture follows the CURRENT surface (after wm size).
                // - output/presentation ALWAYS follows the physical size saved
                //   before the per-app display override was applied.
                // Never use the requested reinit geometry as the output size.
                pendingReinitW = 0
                pendingReinitH = 0
                val inputBaseW = lastSurfaceW.takeIf { it > 0 }
                    ?: width.coerceAtLeast(1)
                val inputBaseH = lastSurfaceH.takeIf { it > 0 }
                    ?: height.coerceAtLeast(1)
                val presentationW = sessionDisplayProfile
                    ?.originalWidth
                    ?.takeIf { it > 0 }
                    ?: inputBaseW
                val presentationH = sessionDisplayProfile
                    ?.originalHeight
                    ?.takeIf { it > 0 }
                    ?: inputBaseH
                val cfg = LsfgPreferences(this).load()
                LsfgLog.i(
                    TAG,
                    "Re-init LSFG context pass=$pass input=${inputBaseW}x${inputBaseH} " +
                        "presentation=${presentationW}x${presentationH} " +
                        "multiplier=${cfg.multiplier} flowScale=${cfg.flowScale} " +
                        "perf=${cfg.performanceMode} hdr=${cfg.hdrMode}"
                )

                if (lsfgContextActive) {
                    // Stop pushing new captures BEFORE we tear down the native
                    // context. shutdownRenderLoop() joins the C++ worker which
                    // can sit inside vkDeviceWaitIdle for tens of ms; if a new
                    // pushFrame arrives concurrently it can leave the framegen
                    // device with in-flight commands and the next waitIdle
                    // hangs forever (multi-second). Symptom from logs: re-init
                    // started but "Render loop shut down" never came.
                    runCatching { cap?.pauseLsfgInput() }
                        .onFailure { LsfgLog.w(TAG, "pauseLsfgInput failed", it) }
                    runCatching { shizukuCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Shizuku capture failed", it) }
                    runCatching { rootCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Root capture failed", it) }
                    runCatching { NativeBridge.destroyContext() }
                    lsfgContextActive = false
                }
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio),
                )
                // Capture/context buffers follow the CURRENT display geometry.
                // The generated frame is presented at the saved physical size.
                val (scaledW, scaledH) =
                    scaledRenderSize(inputBaseW, inputBaseH, cfg.renderResolutionScale)
                val ai = aiBackendArgs(cfg)
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = scaledW,
                        height = scaledH,
                        multiplier = effectiveMultiplier(cfg),
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        framegenFp16 = cfg.framegenFp16,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        aiBackend = ai.enabled,
                        aiModelDir = ai.modelDir,
                        aiEngine = ai.engine,
                    )
                }.getOrElse { -1 }
                if (rc == 0 || rc > 0) {
                    lsfgContextActive = true
                    // CRITICAL: destroyContext() above released the native ANativeWindow
                    // handle, so initContext() came up with no output surface attached.
                    // Without this re-attach, blitOutputToWindow() short-circuits and
                    // the overlay freezes on whatever was last posted.
                    val surface = lastSurface
                    if (surface != null) {
                        runCatching { NativeBridge.setOutputSurface(surface, presentationW, presentationH) }
                            .onFailure { LsfgLog.w(TAG, "setOutputSurface (re-init) failed", it) }
                    } else {
                        LsfgLog.w(TAG, "reinit: no cached surface to re-attach")
                    }
                    if (rc == 0) {
                        activeRenderW = presentationW
                        activeRenderH = presentationH
                        activeInputW = scaledW
                        activeInputH = scaledH
                        activeBackendLabel = backendLabel(cfg, ai)
                        cap?.lowLatencyCapture = cfg.lowLatencyCapture
                        cap?.setLsfgMode(scaledW, scaledH)
                        cap?.setLsfgNativeInputEnabled(true)
                        val reinitTarget = targetPkgPending ?: activeTargetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, scaledW, scaledH, cfg)
                        startRootVideo(rootCapture, reinitTarget, scaledW, scaledH, cfg)
                        mainHandler.post {
                            ov.updateStatus("LSFG-Android+: ${lastSurfaceW}×${lastSurfaceH} ×${cfg.multiplier} flow=${"%.2f".format(cfg.flowScale)}")
                            pushStreamInfo()
                        }
                        // Reveal the overlay again now that setOutputSurface has
                        // re-attached at the new size. A short delay gives the
                        // native worker thread time to actually blit the first
                        // frame at the new dimensions rather than fading in onto
                        // whatever stale content is still sitting in the buffer.
                        mainHandler.postDelayed({ ov.endGeometryTransition() }, 120L)
                    } else {
                        LsfgLog.w(TAG, "reinit rc=$rc — framegen disabled, staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (surface != null) cap?.setSurface(surface, inputBaseW, inputBaseH)
                        val reinitTarget = targetPkgPending ?: activeTargetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, inputBaseW, inputBaseH, cfg)
                        startRootVideo(rootCapture, reinitTarget, inputBaseW, inputBaseH, cfg)
                        mainHandler.post {
                            if ((shizukuCapture != null || rootCapture != null) && cap != null) {
                                ov.updateStatus("LSFG-Android+: privileged capture unavailable for mirror fallback (frame-gen unavailable)")
                            } else if (cap != null) {
                                ov.updateStatus("LSFG-Android+: mirror ${width}×${height} (GPU lacks required Vulkan ext)")
                            } else {
                                ov.updateStatus("LSFG-Android+: frame-gen unavailable (init rc=$rc)")
                            }
                        }
                        // Mirror fallback path also re-attaches a surface at the
                        // new size above (cap?.setSurface) — reveal once that's done.
                        mainHandler.postDelayed({ ov.endGeometryTransition() }, 120L)
                    }
                } else {
                    LsfgLog.w(TAG, "reinit failed rc=$rc")
                    activeRenderW = 0
                    activeRenderH = 0
                }
            } while (reinitRequested)
            } finally {
                reinitInFlight = false
                doneLatch.countDown()
            }
        }.start()
    }

    private fun startShizukuVideo(
        engine: ShizukuCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.firstt175.deepdrop.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startCapture(targetPackage, width, height, UNCAPPED_FPS)
    }

    private fun startRootVideo(
        engine: RootCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.firstt175.deepdrop.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startCapture(targetPackage, width, height, UNCAPPED_FPS)
    }

    private data class ShizukuVideoStart(
        val width: Int,
        val height: Int,
        val cfg: com.firstt175.deepdrop.prefs.LsfgConfig,
    )

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_session),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LsfgForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(stopPending)
            .build()
    }

    companion object {
        private const val TAG = "LsfgFGS"
        // Capture engines take an int fps ceiling over IPC (Root/Shizuku AIDL
        // interface); passing Int.MAX_VALUE means "no cap" now that the
        // frame-rate limiter has been removed. The receiving services no
        // longer throttle on this value (see RootCaptureService /
        // ShizukuCaptureUserService).
        private const val UNCAPPED_FPS = Int.MAX_VALUE
        private const val CHANNEL_ID = "lsfg_session"
        private const val NOTIF_ID = 1001
        // HUD CPU/GPU/RAM readout poll cadence — cheap reads, but no need to
        // sample faster than a human can read the numbers anyway.
        private const val STATS_POLL_INTERVAL_MS = 1000L

        private val _isRunning = MutableStateFlow(false)

        /** True whenever a session's foreground service is alive (from onCreate
         *  until onDestroy flips it back). Home screen collects this to show
         *  "Start session" vs "Stop session" instead of a button that always
         *  reads STOP SESSION. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        const val ACTION_START = "com.firstt175.deepdrop.action.START_SESSION"
        const val ACTION_STOP = "com.firstt175.deepdrop.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_FPS_COUNTER = "fps_counter"
        const val EXTRA_CAPTURE_SOURCE = "capture_source"

        fun buildStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            fpsCounter: Boolean,
            captureSource: CaptureSource = CaptureSource.MEDIA_PROJECTION,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, captureSource.prefValue)

        fun buildShizukuStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.SHIZUKU.prefValue)

        fun buildRootStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.ROOT.prefValue)

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, LsfgForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

    }
}
