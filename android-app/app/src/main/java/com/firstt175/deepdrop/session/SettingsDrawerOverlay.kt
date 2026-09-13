package com.firstt175.deepdrop.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.firstt175.deepdrop.prefs.DrawerEdge
import com.firstt175.deepdrop.prefs.FramegenBackend
import com.firstt175.deepdrop.prefs.FramegenGuardPreset
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.OverlayMode
import com.firstt175.deepdrop.prefs.PacingDefaults
import com.firstt175.deepdrop.prefs.PacingPreset
import java.io.File

/**
 * Right-edge settings drawer for the in-game overlay.
 *
 * Collapsed: a thin touchable edge strip + a vertical "handle" pill visible at mid-height.
 *            The handle pulses subtly so the user can find it.
 * Drag:      swiping leftward from the strip moves the panel with the finger 1:1. Releasing
 *            past the halfway point snaps open (with a slight overshoot); otherwise snaps back.
 * Expanded:  scrim darkens the game area; tapping outside the panel closes it.
 *
 * Always hosted as TYPE_APPLICATION_OVERLAY (or legacy TYPE_SYSTEM_ALERT). The drawer is
 * always touchable (it has its own UI) so the Android 12+ 0.8-alpha clamp does not apply;
 * scrim and panel fade are driven via View.setAlpha, which is unaffected by the window
 * clamp.
 */
class SettingsDrawerOverlay(
    private val ctx: Context,
    private val entryMode: OverlayMode = OverlayMode.ICON_BUTTON,
) {

    fun interface BypassToggleListener {
        fun onBypassChanged(bypass: Boolean)
    }

    fun interface StopOverlayListener {
        /** [resetDisplay] — true to restore the original resolution/DPI, false to leave the current override in place. */
        fun onStopOverlay(resetDisplay: Boolean)
    }

    fun interface RestartSessionListener {
        /** Re-request MediaProjection consent and relaunch the session for the current target app. */
        fun onRestartSession()
    }

    fun interface FpsCounterListener {
        fun onFpsCounterChanged(enabled: Boolean)
    }

    fun interface FrameGraphListener {
        fun onFrameGraphChanged(enabled: Boolean)
    }

    fun interface CpuStatListener {
        fun onCpuStatChanged(enabled: Boolean)
    }

    fun interface GpuStatListener {
        fun onGpuStatChanged(enabled: Boolean)
    }

    fun interface RamStatListener {
        fun onRamStatChanged(enabled: Boolean)
    }

    fun interface LiveParamsListener {
        fun onParamsChanged()
    }

    private var hostWindowManager: WindowManager? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var handleView: HandleView? = null
    private var scrim: View? = null
    private var panelContainer: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var bypassListener: BypassToggleListener? = null
    private var stopListener: StopOverlayListener? = null
    private var restartListener: RestartSessionListener? = null
    private var fpsCounterListener: FpsCounterListener? = null
    private var frameGraphListener: FrameGraphListener? = null
    private var cpuStatListener: CpuStatListener? = null
    private var gpuStatListener: GpuStatListener? = null
    private var ramStatListener: RamStatListener? = null
    private var liveParamsListener: LiveParamsListener? = null
    private var initialFpsCounter: Boolean = false
    private var initialFrameGraph: Boolean = false
    private var initialCpuStat: Boolean = false
    private var initialGpuStat: Boolean = false
    private var initialRamStat: Boolean = false
    /** Set by [LsfgForegroundService] once it knows whether this session actually
     *  applied a resolution/DPI override — gates whether END SESSION asks the
     *  reset-or-keep question at all. */
    private var displayProfileActive: Boolean = false

    /**
     * Owns the global (session-0) Equalizer/BassBoost/Virtualizer/LoudnessEnhancer
     * effects for the "SOUND TUNER" section. Lives only as long as the drawer's
     * window does — attached lazily when the user enables the tuner (or immediately
     * on [buildPanel] if it was already enabled last session), released in [hide].
     */
    private var soundTuner: SoundTunerController? = null

    // "Apply" bar for frame-gen params (multiplier, flow scale, render resolution,
    // performance/HDR/FP16 mode). These all require a full native context re-init
    // (destroy + recreate Vulkan session, reload shaders — ~1-3s of stalled/frozen
    // output), so instead of firing on every slider tick we batch changes and only
    // reinit once the user explicitly confirms. See buildApplyBar().
    private var applyBar: View? = null
    private var applyButton: Button? = null
    private var paramsDirty: Boolean = false

    private fun markParamsDirty() {
        paramsDirty = true
        applyButton?.isEnabled = true
        applyBar?.alpha = 1f
    }

    private fun clearParamsDirty() {
        paramsDirty = false
        applyButton?.isEnabled = false
        applyBar?.alpha = 0.5f
    }

    /** 0 = collapsed, 1 = fully expanded. During drag this tracks the finger. */
    private var progress: Float = 0f
    private var expanded: Boolean = false
    private var dragActive: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragStartProgress: Float = 0f
    private var settleAnimator: ValueAnimator? = null
    private var handlePulseAnimator: ValueAnimator? = null

    private var edgeStripWidthPx = 0
    private var handleWidthPx = 0
    private var handleHeightPx = 0
    private var panelWidthPx = 0
    private var panelHeightPx = 0
    private var panelMarginPx = 0
    private var screenW = 0
    private var screenH = 0
    private var drawerEdge: DrawerEdge = DrawerEdge.RIGHT

    // ICON_BUTTON mode state.
    private var iconButton: View? = null
    private var iconSizePx: Int = 0
    /** Last position of the icon (TOP|START gravity). Persisted across open/close. */
    private var iconX: Int = 0
    private var iconY: Int = 0
    private var iconDragStartRawX: Float = 0f
    private var iconDragStartRawY: Float = 0f
    private var iconDragStartX: Int = 0
    private var iconDragStartY: Int = 0
    private var iconDragMoved: Boolean = false
    // Coalesce per-event updateViewLayout into one Binder RPC per vsync. Without
    // this, touch can fire 240+ Hz on flagships and each updateViewLayout is a
    // round-trip to WindowManagerService.
    private var iconDragFramePending: Boolean = false
    private val iconDragFrameCallback = Choreographer.FrameCallback {
        iconDragFramePending = false
        val lp = params
        val r = root
        val wm = hostWindowManager
        if (lp != null && r != null && wm != null && r.isAttachedToWindow &&
            lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
            lp.x = iconX
            lp.y = iconY
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "icon drag updateViewLayout failed", it) }
        }
    }

    fun setBypassListener(l: BypassToggleListener) { bypassListener = l }
    fun setStopOverlayListener(l: StopOverlayListener) { stopListener = l }
    fun setRestartSessionListener(l: RestartSessionListener) { restartListener = l }
    fun setFpsCounterListener(l: FpsCounterListener) { fpsCounterListener = l }
    fun setFrameGraphListener(l: FrameGraphListener) { frameGraphListener = l }
    fun setCpuStatListener(l: CpuStatListener) { cpuStatListener = l }
    fun setGpuStatListener(l: GpuStatListener) { gpuStatListener = l }
    fun setRamStatListener(l: RamStatListener) { ramStatListener = l }
    fun setLiveParamsListener(l: LiveParamsListener) { liveParamsListener = l }
    fun setInitialFpsCounterState(enabled: Boolean) { initialFpsCounter = enabled }
    fun setInitialFrameGraphState(enabled: Boolean) { initialFrameGraph = enabled }
    fun setInitialCpuStatState(enabled: Boolean) { initialCpuStat = enabled }
    fun setInitialGpuStatState(enabled: Boolean) { initialGpuStat = enabled }
    fun setInitialRamStatState(enabled: Boolean) { initialRamStat = enabled }
    fun setDisplayProfileActive(active: Boolean) { displayProfileActive = active }

    // --- END SESSION confirmation ------------------------------------------------------

    private var stopConfirmView: View? = null

    // --- Log viewer ----------------------------------------------------------------------

    private var logViewerView: View? = null

    /**
     * Entry point for the END SESSION button. If this session actually applied a
     * resolution/DPI override, ask the user whether to restore the original
     * screen size first — otherwise just stop (nothing to restore).
     */
    private fun requestStop() {
        if (!displayProfileActive) {
            stopListener?.onStopOverlay(true)
            return
        }
        showStopConfirm()
    }

    private fun showStopConfirm() {
        val r = root ?: return
        if (stopConfirmView != null) return

        val scrimView = View(ctx).apply {
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            setOnClickListener { dismissStopConfirm() }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), COLOR_PANEL_STROKE)
            }
            setPadding(dp(20), dp(20), dp(20), dp(16))
            isClickable = true // swallow taps so they don't fall through to the scrim
        }

        card.addView(TextView(ctx).apply {
            text = "Reset screen size?"
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = "This session changed the screen resolution/DPI. Restore the original size now, or keep it as-is?"
            setTextColor(COLOR_ON_SURFACE)
            alpha = 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(16))
        })

        val resetBtn = Button(ctx).apply {
            text = "RESET & END SESSION"
            setTextColor(COLOR_ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener {
                dismissStopConfirm()
                stopListener?.onStopOverlay(true)
            }
        }
        card.addView(resetBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val keepBtn = Button(ctx).apply {
            text = "KEEP RESOLUTION & END"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = dp(3).toFloat()
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener {
                dismissStopConfirm()
                stopListener?.onStopOverlay(false)
            }
        }
        card.addView(keepBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val cancelBtn = Button(ctx).apply {
            text = "CANCEL"
            setTextColor(COLOR_ON_SURFACE)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = null
            setPadding(0, dp(10), 0, dp(4))
            stateListAnimator = null
            setOnClickListener { dismissStopConfirm() }
        }
        card.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })

        val overlayContainer = FrameLayout(ctx)
        overlayContainer.addView(scrimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val cardWidth = (screenW * 0.8f).toInt().coerceAtMost(dp(320))
        overlayContainer.addView(card, FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        r.addView(overlayContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        stopConfirmView = overlayContainer
    }

    private fun dismissStopConfirm() {
        val v = stopConfirmView ?: return
        stopConfirmView = null
        (v.parent as? FrameLayout)?.removeView(v)
    }

    /**
     * On-device-only log viewer: reads the tail of [CrashReporter.logFile] (written by
     * [LsfgLog]) and shows it in a scrollable, monospace, read-only panel. Deliberately no
     * share/export intent — CrashReporter's own docs call that out as intentional, so this
     * stays consistent with it; a "COPY" button is the only way the text leaves the panel,
     * and that stays on-device via the clipboard.
     *
     * Follows the same scrim + centered card pattern as [showStopConfirm] rather than a
     * system AlertDialog, since this view already lives inside our own overlay window.
     */
    private fun showLogViewer() {
        val r = root ?: return
        if (logViewerView != null) return

        val logText = runCatching {
            val f = CrashReporter.logFile(ctx)
            if (!f.exists()) {
                "No log file yet."
            } else {
                // Tail-only: read at most the last ~120,000 chars so a long-running
                // session's log can't stall the UI thread or blow up the TextView.
                val maxChars = 120_000L
                val len = f.length()
                val skip = (len - maxChars).coerceAtLeast(0L)
                f.inputStream().use { ins ->
                    if (skip > 0) ins.skip(skip)
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    if (skip > 0) "…(earlier lines truncated)…\n$text" else text
                }
            }
        }.getOrElse { "Failed to read log: ${it.message}" }

        val scrimView = View(ctx).apply {
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            setOnClickListener { dismissLogViewer() }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), COLOR_PANEL_STROKE)
            }
            setPadding(dp(16), dp(16), dp(16), dp(12))
            isClickable = true // swallow taps so they don't fall through to the scrim
        }

        card.addView(TextView(ctx).apply {
            text = "Log"
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        })

        val logBody = TextView(ctx).apply {
            text = logText
            setTextColor(COLOR_ON_SURFACE)
            alpha = 0.85f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            addView(logBody, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        card.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { topMargin = dp(10); bottomMargin = dp(12) })

        val buttonRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val copyBtn = Button(ctx).apply {
            text = "COPY"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = dp(3).toFloat()
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("lsfg log", logText))
            }
        }
        buttonRow.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            setTextColor(COLOR_ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener { dismissLogViewer() }
        }
        buttonRow.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })

        card.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val overlayContainer = FrameLayout(ctx)
        overlayContainer.addView(scrimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val cardWidth = (screenW * 0.92f).toInt().coerceAtMost(dp(520))
        overlayContainer.addView(card, FrameLayout.LayoutParams(
            cardWidth,
            (screenH * 0.75f).toInt(),
            Gravity.CENTER,
        ))

        r.addView(overlayContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        logViewerView = overlayContainer
    }

    private fun dismissLogViewer() {
        val v = logViewerView ?: return
        logViewerView = null
        (v.parent as? FrameLayout)?.removeView(v)
    }

    /** Opens the settings panel without starting a capture/frame-generation session.
     *  Used by the external launcher icon; the actual session only begins when the
     *  user explicitly presses START SESSION in this drawer.
     */
    fun openPanel() {
        if (root == null) {
            show()
        }
        mainHandler.post {
            if (!expanded) {
                expandWindow()
                animateTo(1f)
            }
        }
    }

    fun show() {
        if (root != null) return

        // Match OverlayManager's host choice — they MUST live in the same
        // layer family or the drawer disappears behind the capture overlay.
        // See OverlayManager.show() for the trusted-overlay rationale.
        val prefs = LsfgPreferences(ctx).load()
        val a11y = LsfgAccessibilityService.instance
        val useTrusted = prefs.trustedOverlay && a11y != null
        val hostCtx: Context = if (useTrusted) a11y!! else ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWindowManager = wm

        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        edgeStripWidthPx = dp(16)
        handleWidthPx = dp(5)
        handleHeightPx = dp(68)
        iconSizePx = dp(48)
        panelWidthPx = minOf(dp(430), (screenW * 0.92f).toInt())
        panelHeightPx = minOf(dp(620), (screenH * 0.92f).toInt())
        panelMarginPx = dp(12)
        drawerEdge = prefs.drawerEdge
        // Initial icon position: stick it to the right edge, vertically centred.
        iconX = screenW - iconSizePx - dp(8)
        iconY = (screenH - iconSizePx) / 2

        val layoutType = when {
            useTrusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Start narrow so only the entry affordance captures touches. When the user opens
        // the panel we expand to MATCH_PARENT so scrim + panel can be laid out across the
        // whole screen.
        val lp = WindowManager.LayoutParams(
            collapsedWindowWidth(),
            collapsedWindowHeight(),
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = collapsedWindowGravity()
            x = if (entryMode == OverlayMode.ICON_BUTTON) iconX else 0
            y = if (entryMode == OverlayMode.ICON_BUTTON) iconY else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        params = lp

        val rootLayout = FrameLayout(ctx)
        root = rootLayout

        // Scrim — darkens game area when drawer is open. Alpha is bound to progress so it
        // fades in as the user drags / after the icon is tapped.
        val scrimView = View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()) // solid black, we drive alpha separately
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { animateTo(0f) }
        }
        scrim = scrimView
        rootLayout.addView(
            scrimView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val panelView = buildPanel()
        panelContainer = panelView
        panelView.visibility = View.GONE
        applyPanelProgress(panelView, 0f)
        rootLayout.addView(
            panelView,
            panelLayoutParams(),
        )

        if (entryMode == OverlayMode.DRAWER) {
            val handle = HandleView(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            handleView = handle
            rootLayout.addView(
                handle,
                handleLayoutParams(),
            )
            attachEdgeSwipeBehavior(rootLayout)
            startHandlePulse()
        } else {
            val icon = buildIconButton()
            iconButton = icon
            rootLayout.addView(icon, iconButtonLayoutParams())
            attachIconButtonBehavior(icon)
        }

        runCatching { wm.addView(rootLayout, lp) }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    fun hide() {
        val r = root ?: return
        val wm = hostWindowManager
        settleAnimator?.cancel()
        settleAnimator = null
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        soundTuner?.disable()
        soundTuner = null
        if (wm != null) {
            runCatching { wm.removeView(r) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        root = null
        handleView = null
        iconButton = null
        scrim = null
        panelContainer = null
        stopConfirmView = null
        params = null
        hostWindowManager = null
        expanded = false
        progress = 0f
    }

    // --- panel ---------------------------------------------------------------------------

    /** Fires on every progress tick, no coalescing. For sliders with no expensive commit. */
    private fun SeekBar.onProgress(onChange: (progress: Int, fromUser: Boolean) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Updates the display on every tick, but only commits (prefs write + reinit) on tap or
     * drag-release — never mid-drag — to avoid flooding SharedPreferences with ~60 writes/sec.
     */
    private fun <T> SeekBar.wireLiveDrag(
        label: String,
        toValue: (progress: Int) -> T,
        display: (T) -> Unit,
        commit: (T) -> Unit,
    ) {
        var dragging = false
        var pending: T = toValue(progress)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                val v = toValue(p)
                display(v)
                if (fromUser) {
                    pending = v
                    if (!dragging) {
                        commit(v)
                        Log.i(TAG, "live: $label tap → $v")
                        markParamsDirty()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                commit(pending)
                Log.i(TAG, "live: $label release → $pending")
                markParamsDirty()
            }
        })
    }

    private fun buildPanel(): View {
        val prefs = LsfgPreferences(ctx)
        val initial = prefs.load()

        // Outer container holds the rounded panel plus a small floating margin on the right
        // so the panel does not touch the screen edge (gives it a card/sheet feel).
        val container = FrameLayout(ctx).apply {
            setPadding(0, panelMarginPx, dp(8), panelMarginPx)
            isClickable = true // absorb taps so they don't bubble to scrim
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = buildPanelBackground()
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(86))
        }

        // Header — brand mark + large bold title + close, Quick-Settings style.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        header.addView(brandMark())
        header.addView(
            TextView(ctx).apply {
                text = "LSFG // CONTROL CONSOLE"
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { leftMargin = dp(12) }
                layoutParams = lp
            },
        )
        val closeBtn = ImageView(ctx).apply {
            setImageDrawable(crossDrawable())
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { animateTo(0f) }
        }
        header.addView(closeBtn)
        panel.addView(header)

        panel.addView(TextView(ctx).apply {
            text = "REALTIME RENDER / VULKAN / FRAME GENERATION"
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            alpha = 0.55f
            letterSpacing = 0.10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        })

        panel.addView(sectionSpacer(8))
        buildQuickControlsSection(panel)
        panel.addView(sectionSpacer(4))
        panel.addView(divider())
        panel.addView(sectionSpacer(14))

        // ---- Frame Generation (expanded by default so drawer shows content on first open) ----
        val frameGenSection = collapsibleSection(panel, "FRAME GENERATION", initiallyExpanded = true)

        frameGenSection.addView(switchRow(
            label = "LSFG-Android+ Frame Gen",
            initial = initial.lsfgEnabled,
        ) {
            Log.i(TAG, "live: lsfgEnabled=$it")
            prefs.setLsfgEnabled(it)
            // Invert for the bypass plumbing: on = frame gen active, off = bypass raw capture.
            bypassListener?.onBypassChanged(!it)
        })

        // Performance mode / HDR / FP16 only apply to the LSFG_3_1/3_1P shader
        // chain — the AI (ncnn) backend never reads them (see the matching
        // gate + comment in ParamsScreen.kt's Frame generation & pacing screen
        // and lsfg_render_loop.cpp's initRenderLoop). Hidden here too so the
        // in-game drawer never shows a control that does nothing for the
        // backend that's actually running.
        if (initial.framegenBackend == FramegenBackend.LSFG_DLL) {
            frameGenSection.addView(switchRow(
                label = "Performance mode",
                initial = initial.performanceMode,
            ) {
                Log.i(TAG, "live: performance=$it")
                prefs.setPerformance(it)
                markParamsDirty()
            })
            frameGenSection.addView(switchRow(
                label = "HDR mode",
                initial = initial.hdrMode,
            ) {
                Log.i(TAG, "live: hdr=$it")
                prefs.setHdr(it)
                markParamsDirty()
            })

            // FP16 frame-gen shaders — only show when the GPU supports shaderFloat16
            // and the FP16 SPIR-V cache has been populated (same gate the in-app
            // Params screen uses). Toggling requires a context re-init because
            // shader modules are bound at LSFG_3_X::initialize time, hence
            // markParamsDirty() — the reinit itself waits for "Apply".
            val fp16CacheDir = File(ctx.filesDir, "spirv").absolutePath
            val fp16Available = runCatching {
                NativeBridge.isFramegenFp16Supported(fp16CacheDir)
            }.getOrDefault(false)
            if (fp16Available) {
                frameGenSection.addView(switchRow(
                    label = "FP16 frame-gen shaders",
                    initial = initial.framegenFp16,
                ) {
                    Log.i(TAG, "live: framegenFp16=$it")
                    prefs.setFramegenFp16(it)
                    markParamsDirty()
                })
            }
        }

        // ---- Frame generation -----------------------------------------------
        val multiplierValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${initial.multiplier}×"
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Frame multiplier",
                valueView = multiplierValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 6
            progress = (initial.multiplier - 2).coerceIn(0, 6)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            // Tap commits immediately; drag coalesces the prefs write + reinit to
            // release, avoiding ~60×/sec SharedPreferences writes during a drag.
            wireLiveDrag(
                label = "multiplier",
                toValue = { p -> (p + 2).coerceIn(2, 8) },
                display = { m -> multiplierValue.text = "${m}×" },
                commit = { m -> prefs.setMultiplier(m) },
            )
        })

        frameGenSection.addView(sectionSpacer(10))

        // Flow scale only affects the LSFG_DLL shader chain's motion-estimation
        // pass (see resourcepool.cpp). The AI (ncnn) backend's RIFE/IFRNet nets
        // are single-pass with no separate low-res flow stage to downscale —
        // flowScale is accepted by NcnnInterpolator::interpolate()/
        // IfrnetInterpolator::interpolate() only for call-site compatibility
        // and is explicitly unused. Hidden here too, matching the perf/HDR/FP16
        // gate above, so the in-game drawer never shows a control that does
        // nothing for the backend that's actually running.
        if (initial.framegenBackend == FramegenBackend.LSFG_DLL) {
        val flowValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.2f".format(initial.flowScale)
        }
        frameGenSection.addView(
            sliderRow(
                // Keep the original 0.1.3 representation: raw 0.25..1.0 value.
                labelText = "Flow scale",
                valueView = flowValue,
            ),
        )
        val flowSeekBar = SeekBar(ctx).apply {
            max = 15
            progress = ((initial.flowScale - 0.25f) / 0.05f).toInt().coerceIn(0, 15)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "flowScale",
                toValue = { p -> (0.25f + p * 0.05f).coerceIn(0.25f, 1.0f) },
                display = { f ->
                    flowValue.text = "%.2f".format(f)
                },
                commit = { f -> prefs.setFlowScale(f) },
            )
        }
        frameGenSection.addView(flowSeekBar)

        frameGenSection.addView(sectionSpacer(6))

        } // framegenBackend == LSFG_DLL (flow scale)

        frameGenSection.addView(sectionSpacer(10))

        val renderResValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.renderResolutionScale * 100f).toInt()}%"
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Render resolution",
                valueView = renderResValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 20
            progress = (initial.renderResolutionScale * 20f).toInt().coerceIn(0, 20)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "renderResolutionScale",
                toValue = { p -> (p / 20f).coerceIn(0f, 1f) },
                display = { f -> renderResValue.text = "${(f * 100f).toInt()}%" },
                commit = { f -> prefs.setRenderResolutionScale(f) },
            )
        })

        // ---- Frame pacing: directly accessible from the in-game overlay ----
        // These parameters are hot-applied; they do not require the expensive
        // Vulkan context re-init used by shader parameters above.
        frameGenSection.addView(sectionSpacer(8))
        frameGenSection.addView(miniHeader("Frame pacing"))

        val pacingButtons = mutableListOf<Button>()
        var selectedPacing = initial.pacingPreset
        lateinit var emaValue: TextView
        lateinit var outlierValue: TextView
        lateinit var emaSeek: SeekBar
        lateinit var outlierSeek: SeekBar
        fun effectivePacing(preset: PacingPreset = selectedPacing): PacingDefaults.Params =
            PacingDefaults.forPreset(preset, PacingDefaults.Params(initial.emaAlpha, initial.outlierRatio))
        fun paintPacingButtons() {
            pacingButtons.forEachIndexed { index, button ->
                val preset = listOf(
                    PacingPreset.SMOOTH, PacingPreset.BALANCED,
                    PacingPreset.LOW_LATENCY, PacingPreset.CUSTOM,
                )[index]
                val selected = preset == selectedPacing
                button.setTextColor(if (selected) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (button.background as? GradientDrawable)?.setColor(
                    if (selected) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        listOf(
            PacingPreset.SMOOTH to "Smooth",
            PacingPreset.BALANCED to "Balanced",
            PacingPreset.LOW_LATENCY to "Low latency",
            PacingPreset.CUSTOM to "Custom",
        ).forEach { (preset, label) ->
            val button = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                    setColor(COLOR_CHIP_BG)
                }
                setPadding(dp(3), dp(4), dp(3), dp(4))
                stateListAnimator = null
                setOnClickListener {
                    selectedPacing = preset
                    val params = effectivePacing(preset)
                    prefs.setPacingPreset(preset)
                    runCatching { NativeBridge.setPacingParams(params.emaAlpha, params.outlierRatio) }
                    paintPacingButtons()
                    emaValue.text = "%.3f".format(params.emaAlpha)
                    outlierValue.text = "%.1f".format(params.outlierRatio)
                    emaSeek.progress = ((params.emaAlpha - 0.05f) / 0.01f).toInt().coerceIn(0, 45)
                    outlierSeek.progress = ((params.outlierRatio - 2f) / 0.1f).toInt().coerceIn(0, 60)
                    Log.i(TAG, "live: pacing preset=$preset ema=${params.emaAlpha} outlier=${params.outlierRatio}")
                }
            }
            presetRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (pacingButtons.isEmpty()) 0 else dp(4)
            })
            pacingButtons += button
        }
        frameGenSection.addView(presetRow)

        val initialPacing = effectivePacing()
        emaValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.3f".format(initialPacing.emaAlpha)
        }
        frameGenSection.addView(sliderRow("EMA alpha", emaValue))
        emaSeek = SeekBar(ctx).apply {
            max = 45
            progress = ((initialPacing.emaAlpha - 0.05f) / 0.01f).toInt().coerceIn(0, 45)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "emaAlpha",
                toValue = { p -> (0.05f + p * 0.01f).coerceIn(0.05f, 0.5f) },
                display = { v -> emaValue.text = "%.3f".format(v) },
                commit = { v ->
                    selectedPacing = PacingPreset.CUSTOM
                    prefs.setPacingPreset(PacingPreset.CUSTOM)
                    prefs.setEmaAlpha(v)
                    val ratio = prefs.load().outlierRatio
                    runCatching { NativeBridge.setPacingParams(v, ratio) }
                    paintPacingButtons()
                },
            )
        }
        frameGenSection.addView(emaSeek)

        outlierValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.1f".format(initialPacing.outlierRatio)
        }
        frameGenSection.addView(sliderRow("Outlier ratio", outlierValue))
        outlierSeek = SeekBar(ctx).apply {
            max = 60
            progress = ((initialPacing.outlierRatio - 2f) / 0.1f).toInt().coerceIn(0, 60)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "outlierRatio",
                toValue = { p -> (2f + p * 0.1f).coerceIn(2f, 8f) },
                display = { v -> outlierValue.text = "%.1f".format(v) },
                commit = { v ->
                    selectedPacing = PacingPreset.CUSTOM
                    prefs.setPacingPreset(PacingPreset.CUSTOM)
                    prefs.setOutlierRatio(v)
                    val alpha = prefs.load().emaAlpha
                    runCatching { NativeBridge.setPacingParams(alpha, v) }
                    paintPacingButtons()
                },
            )
        }
        frameGenSection.addView(outlierSeek)
        paintPacingButtons()

        // ---- Frame-gen admission guards: bundled into one preset ------------------
        // Auto bypass (backlog-pressure & real-frame-first) and the generation
        // preflight safety window below all gate the SAME decision: whether an
        // individual frame's background generation is even allowed to start.
        // All three are OFF by default, and leaving any subset of them
        // individually enabled is how a slow device ends up computing a
        // generated frame that then loses the race against the next real
        // capture and gets thrown away in genWorkerThread's staleness check
        // instead of never being started in the first place.
        //
        // This preset row makes OFF and STRICT both authoritative over all
        // three at once: tapping "ปิดทั้งหมด" force-sets all three to false —
        // even if one had been hand-enabled before — and tapping "เปิดทั้งหมด"
        // force-sets all three to true. Hand-toggling one of the three
        // switches individually below drops the selection to "กำหนดเอง"
        // (custom) so the row never claims OFF/STRICT while actually showing
        // a mixed state.
        frameGenSection.addView(sectionSpacer(8))
        frameGenSection.addView(miniHeader("การ์ดป้องกันเฟรมเจน (Guard preset)"))

        val guardButtons = mutableListOf<Button>()
        // Don't just trust the stored preset label blindly: if the three
        // underlying toggles are already in a state that doesn't match it
        // (e.g. one was hand-set before this preset row existed), show the
        // row honestly as "กำหนดเอง" (custom) rather than mislabeling a mixed
        // state as OFF or STRICT.
        var selectedGuard = run {
            val stored = prefs.getFramegenGuardPreset()
            val allOff = !prefs.isDynamicBypassEnabled() &&
                !prefs.isRealFrameFirstEnabled() &&
                !prefs.isPreflightSafetyWindowEnabled()
            val allOn = prefs.isDynamicBypassEnabled() &&
                prefs.isRealFrameFirstEnabled() &&
                prefs.isPreflightSafetyWindowEnabled()
            when {
                stored == FramegenGuardPreset.OFF && !allOff -> FramegenGuardPreset.CUSTOM
                stored == FramegenGuardPreset.STRICT && !allOn -> FramegenGuardPreset.CUSTOM
                else -> stored
            }
        }
        lateinit var dynBypassValue: TextView
        lateinit var dynBypassSeek: SeekBar
        lateinit var preflightRatioSeek: SeekBar
        lateinit var staleGenToleranceSeek: SeekBar
        lateinit var dynBypassSwitch: Switch
        lateinit var realFrameFirstSwitch: Switch
        lateinit var preflightSwitch: Switch
        // Guards the three switchRow onChange callbacks below against
        // demoting the selection to CUSTOM while applyGuardPreset() itself is
        // the one driving their isChecked assignment.
        var applyingGuardPreset = false

        fun paintGuardButtons() {
            guardButtons.forEachIndexed { index, button ->
                val preset = listOf(
                    FramegenGuardPreset.OFF, FramegenGuardPreset.STRICT, FramegenGuardPreset.CUSTOM,
                )[index]
                val selected = preset == selectedGuard
                button.setTextColor(if (selected) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (button.background as? GradientDrawable)?.setColor(
                    if (selected) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }

        fun applyGuardPreset(preset: FramegenGuardPreset) {
            selectedGuard = preset
            prefs.setFramegenGuardPreset(preset)
            if (preset != FramegenGuardPreset.CUSTOM) {
                val enabled = preset == FramegenGuardPreset.STRICT
                applyingGuardPreset = true
                dynBypassSwitch.isChecked = enabled
                realFrameFirstSwitch.isChecked = enabled
                preflightSwitch.isChecked = enabled
                applyingGuardPreset = false
                // Authoritative regardless of whether the isChecked assignments
                // above actually fired each switch's own listener — Android's
                // CompoundButton skips the listener when the value doesn't
                // change from what it already was. This is what guarantees OFF
                // always truly disables all three and STRICT always truly
                // enables all three, rather than depending on their prior state.
                prefs.setDynamicBypassEnabled(enabled)
                runCatching { NativeBridge.setDynamicBypassEnabled(enabled) }
                prefs.setRealFrameFirstEnabled(enabled)
                runCatching { NativeBridge.setRealFrameFirstEnabled(enabled) }
                prefs.setPreflightSafetyWindowEnabled(enabled)
                runCatching { NativeBridge.setPreflightSafetyWindowEnabled(enabled) }
                dynBypassSeek.isEnabled = enabled
                preflightRatioSeek.isEnabled = enabled
                Log.i(TAG, "live: framegenGuardPreset=$preset (all three admission guards=$enabled)")
            }
            paintGuardButtons()
        }

        val guardPresetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        listOf(
            FramegenGuardPreset.OFF to "ปิดทั้งหมด",
            FramegenGuardPreset.STRICT to "เปิดทั้งหมด",
            FramegenGuardPreset.CUSTOM to "กำหนดเอง",
        ).forEach { (preset, label) ->
            val button = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                    setColor(COLOR_CHIP_BG)
                }
                setPadding(dp(3), dp(4), dp(3), dp(4))
                stateListAnimator = null
                setOnClickListener { applyGuardPreset(preset) }
            }
            guardPresetRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (guardButtons.isEmpty()) 0 else dp(4)
            })
            guardButtons += button
        }
        frameGenSection.addView(guardPresetRow)
        paintGuardButtons()

        // ---- Auto bypass (backlog-pressure & real-frame-first) -------------------
        // Both policies are OFF by default: the render loop only starts skipping
        // frame-generation automatically for these reasons once the user turns them
        // on here. This is separate from the manual "Bypass" toggle (raw passthrough)
        // at the top of this section — these two only ever skip the OPTIONAL
        // generation step for individual frames while capture keeps flowing.
        frameGenSection.addView(switchRow(
            label = "Auto bypass: คิวค้าง (Dynamic)",
            initial = prefs.isDynamicBypassEnabled(),
            swRef = { dynBypassSwitch = it },
        ) {
            Log.i(TAG, "live: dynamicBypassEnabled=$it")
            prefs.setDynamicBypassEnabled(it)
            runCatching { NativeBridge.setDynamicBypassEnabled(it) }
            dynBypassSeek.isEnabled = it
            if (!applyingGuardPreset) {
                selectedGuard = FramegenGuardPreset.CUSTOM
                prefs.setFramegenGuardPreset(FramegenGuardPreset.CUSTOM)
                paintGuardButtons()
            }
        })

        val initialDynBudget = prefs.getDynamicBypassBudgetMultiplier()
        dynBypassValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.1f×".format(initialDynBudget)
        }
        frameGenSection.addView(sliderRow("Budget คิวค้าง (ยิ่งสูงยิ่งทน)", dynBypassValue))
        dynBypassSeek = SeekBar(ctx).apply {
            max = 40
            progress = ((initialDynBudget - 1.0) / 0.1).toInt().coerceIn(0, 40)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            isEnabled = prefs.isDynamicBypassEnabled()
            wireLiveDrag(
                label = "dynamicBypassBudget",
                toValue = { p -> (1.0 + p * 0.1).coerceIn(1.0, 5.0) },
                display = { v -> dynBypassValue.text = "%.1f×".format(v) },
                commit = { v ->
                    prefs.setDynamicBypassBudgetMultiplier(v)
                    runCatching { NativeBridge.setDynamicBypassBudgetMultiplier(v) }
                },
            )
        }
        frameGenSection.addView(dynBypassSeek)

        frameGenSection.addView(switchRow(
            label = "Auto bypass: เฟรมจริงใหม่มาก่อน",
            initial = prefs.isRealFrameFirstEnabled(),
            swRef = { realFrameFirstSwitch = it },
        ) {
            Log.i(TAG, "live: realFrameFirstEnabled=$it")
            prefs.setRealFrameFirstEnabled(it)
            runCatching { NativeBridge.setRealFrameFirstEnabled(it) }
            if (!applyingGuardPreset) {
                selectedGuard = FramegenGuardPreset.CUSTOM
                prefs.setFramegenGuardPreset(FramegenGuardPreset.CUSTOM)
                paintGuardButtons()
            }
        })

        // ---- Generation preflight & queue depth -----------------------------------
        // A second, always-active layer sitting behind the two auto-bypass
        // toggles above — these gate its "always skip if X" heuristics the
        // same way: OFF by default, tunable once enabled.
        lateinit var preflightRatioValue: TextView

        frameGenSection.addView(switchRow(
            label = "Preflight: งดเจนถ้ากินเวลาเกิน %",
            initial = prefs.isPreflightSafetyWindowEnabled(),
            swRef = { preflightSwitch = it },
        ) {
            Log.i(TAG, "live: preflightSafetyWindowEnabled=$it")
            prefs.setPreflightSafetyWindowEnabled(it)
            runCatching { NativeBridge.setPreflightSafetyWindowEnabled(it) }
            preflightRatioSeek.isEnabled = it
            if (!applyingGuardPreset) {
                selectedGuard = FramegenGuardPreset.CUSTOM
                prefs.setFramegenGuardPreset(FramegenGuardPreset.CUSTOM)
                paintGuardButtons()
            }
        })

        val initialPreflightRatio = prefs.getPreflightSafetyWindowRatio()
        preflightRatioValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initialPreflightRatio * 100).toInt()}%"
        }
        frameGenSection.addView(sliderRow("Budget preflight (% ของ capture interval)", preflightRatioValue))
        preflightRatioSeek = SeekBar(ctx).apply {
            max = 90
            progress = ((initialPreflightRatio - 0.1) / 0.01).toInt().coerceIn(0, 90)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            isEnabled = prefs.isPreflightSafetyWindowEnabled()
            wireLiveDrag(
                label = "preflightSafetyWindowRatio",
                toValue = { p -> (0.1 + p * 0.01).coerceIn(0.1, 1.0) },
                display = { v -> preflightRatioValue.text = "${(v * 100).toInt()}%" },
                commit = { v ->
                    prefs.setPreflightSafetyWindowRatio(v)
                    runCatching { NativeBridge.setPreflightSafetyWindowRatio(v) }
                },
            )
        }
        frameGenSection.addView(preflightRatioSeek)

        val initialMaxQueued = prefs.getMaxQueuedRealFrames()
        val maxQueuedValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "$initialMaxQueued"
        }
        frameGenSection.addView(sliderRow("จำนวนเฟรมจริงที่รอคิวได้ (ก่อนทิ้งของเก่า)", maxQueuedValue))
        val maxQueuedSeek = SeekBar(ctx).apply {
            max = 7
            progress = (initialMaxQueued - 1).coerceIn(0, 7)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "maxQueuedRealFrames",
                toValue = { p -> (p + 1).coerceIn(1, 8) },
                display = { v -> maxQueuedValue.text = "$v" },
                commit = { v ->
                    prefs.setMaxQueuedRealFrames(v)
                    runCatching { NativeBridge.setMaxQueuedRealFrames(v) }
                },
            )
        }
        frameGenSection.addView(maxQueuedSeek)

        frameGenSection.addView(switchRow(
            label = "ปิดเจนอัตโนมัติเมื่อ GPU driver ค้าง (device-lost)",
            initial = prefs.isAutoDisableOnDeviceLostEnabled(),
        ) {
            Log.i(TAG, "live: autoDisableOnDeviceLostEnabled=$it")
            prefs.setAutoDisableOnDeviceLostEnabled(it)
            runCatching { NativeBridge.setAutoDisableOnDeviceLostEnabled(it) }
        })

        // ---- Stale-generation publish tolerance -----------------------------------
        // Real behavior control (not just logging): OFF by default reproduces
        // the original strict jobEpoch==currentEpoch behaviour — every
        // epoch-stale generation is dropped. Enabling this lets a generation
        // still be published up to N epochs behind current, trading a bit of
        // potential visual staleness for fewer dropped generations during
        // rapid epoch churn (bypass toggles, resizes).
        lateinit var staleGenToleranceValue: TextView

        frameGenSection.addView(switchRow(
            label = "ยอมรับเฟรมเจนที่ stale เล็กน้อย (แทนที่จะทิ้งทั้งหมด)",
            initial = prefs.isStaleGenerationToleranceEnabled(),
        ) {
            Log.i(TAG, "live: staleGenerationToleranceEnabled=$it")
            prefs.setStaleGenerationToleranceEnabled(it)
            runCatching { NativeBridge.setStaleGenerationToleranceEnabled(it) }
            staleGenToleranceSeek.isEnabled = it
        })

        val initialStaleGenTolerance = prefs.getStaleGenerationToleranceEpochs()
        staleGenToleranceValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "$initialStaleGenTolerance epoch"
        }
        frameGenSection.addView(sliderRow("ทนได้กี่ epoch ก่อนทิ้ง", staleGenToleranceValue))
        staleGenToleranceSeek = SeekBar(ctx).apply {
            max = 10
            progress = initialStaleGenTolerance.coerceIn(0, 10)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            isEnabled = prefs.isStaleGenerationToleranceEnabled()
            wireLiveDrag(
                label = "staleGenerationToleranceEpochs",
                toValue = { p -> p.coerceIn(0, 10) },
                display = { v -> staleGenToleranceValue.text = "$v epoch" },
                commit = { v ->
                    prefs.setStaleGenerationToleranceEpochs(v)
                    runCatching { NativeBridge.setStaleGenerationToleranceEpochs(v) }
                },
            )
        }
        frameGenSection.addView(staleGenToleranceSeek)

        // Diagnostic log only — spams logcat once enabled (fires on every
        // epoch-invalidated generation), so it stays off unless the user
        // explicitly turns it on to debug stale-frame drops.
        frameGenSection.addView(switchRow(
            label = "Log: เฟรมเจนที่ถูกทิ้งเพราะ stale",
            initial = prefs.isStaleDropLoggingEnabled(),
        ) {
            Log.i(TAG, "live: staleDropLoggingEnabled=$it")
            prefs.setStaleDropLoggingEnabled(it)
            runCatching { NativeBridge.setStaleDropLoggingEnabled(it) }
        })

        panel.addView(divider())

        // ---- HUD & Overlay (FPS, frame graph, drawer edge) -----------------------------
        val hudSection = collapsibleSection(panel, "HUD & OVERLAY")
        hudSection.addView(switchRow(
            label = "FPS counter",
            initial = initialFpsCounter,
        ) {
            prefs.setFpsCounterEnabled(it)
            fpsCounterListener?.onFpsCounterChanged(it)
        })
        hudSection.addView(switchRow(
            label = "Frame pacing graph",
            initial = initialFrameGraph,
        ) {
            prefs.setFrameGraphEnabled(it)
            frameGraphListener?.onFrameGraphChanged(it)
        })
        hudSection.addView(miniHeader("System stats"))
        hudSection.addView(switchRow(
            label = "CPU usage",
            initial = initialCpuStat,
        ) {
            prefs.setCpuStatEnabled(it)
            cpuStatListener?.onCpuStatChanged(it)
        })
        hudSection.addView(switchRow(
            label = "GPU usage",
            initial = initialGpuStat,
        ) {
            prefs.setGpuStatEnabled(it)
            gpuStatListener?.onGpuStatChanged(it)
        })
        hudSection.addView(switchRow(
            label = "RAM usage",
            initial = initialRamStat,
        ) {
            prefs.setRamStatEnabled(it)
            ramStatListener?.onRamStatChanged(it)
        })
        hudSection.addView(miniHeader("Drawer handle"))
        hudSection.addView(drawerEdgeChipRow(initial.drawerEdge) {
            prefs.setDrawerEdge(it)
        })
        hudSection.addView(miniHeader("Diagnostics"))
        val viewLogBtn = Button(ctx).apply {
            text = "VIEW LOG"
            isAllCaps = false
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(0, dp(10), 0, dp(10))
            stateListAnimator = null
            setOnClickListener { showLogViewer() }
        }
        hudSection.addView(viewLogBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })

        buildSoundTunerSection(panel, prefs)

        panel.addView(sectionSpacer(20))

        val startBtn = Button(ctx).apply {
            text = "START SESSION"
            setTextColor(COLOR_START_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_START_BG)
                cornerRadius = dp(3).toFloat()
                setStroke(dp(1), COLOR_START_STROKE)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { restartListener?.onRestartSession() }
        }
        val startLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        panel.addView(startBtn, startLp)

        val stopBtn = Button(ctx).apply {
            text = "END SESSION"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = dp(3).toFloat()
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { requestStop() }
        }
        val stopLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        panel.addView(stopBtn, stopLp)

        scroll.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        container.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        container.addView(buildApplyBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))

        return container
    }

    /**
     * Floating "Apply" bar pinned to the bottom of the drawer, above the scrollable
     * content. Frame-gen params (multiplier, flow scale, render resolution,
     * performance/HDR/FP16) are batched into `prefs` as the user adjusts them but do
     * NOT reinit the native context immediately — see markParamsDirty(). This bar is
     * the single place that triggers the reinit, so multiple tweaks in a row cost one
     * ~1-3s stall instead of one per control.
     *
     * Disabled/dim until something changes; tapping it applies and re-dims itself.
     */
    private fun buildApplyBar(): View {
        val bar = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
            }
            alpha = 0.5f
        }
        val btn = Button(ctx).apply {
            text = "APPLY CHANGES"
            isEnabled = false
            setTextColor(COLOR_ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = dp(3).toFloat()
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener {
                if (!paramsDirty) return@setOnClickListener
                Log.i(TAG, "Apply changes tapped — running single reinit")
                clearParamsDirty()
                liveParamsListener?.onParamsChanged()
            }
        }
        bar.addView(btn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        applyBar = bar
        applyButton = btn
        return bar
    }

    // --- drawer state / animation -------------------------------------------------------

    private fun attachEdgeSwipeBehavior(strip: View) {
        val slop = dp(8)
        strip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A bare touch on the strip is not enough to expand the window — we wait
                    // until ACTION_MOVE crosses the slop threshold. Keeping the window at
                    // `edgeStripWidthPx` while only a tap is in flight avoids forcing the
                    // compositor to blend a full-screen overlay on top of the running game.
                    dragStartX = ev.rawX
                    dragStartY = ev.rawY
                    dragStartProgress = progress
                    dragActive = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaPx = dragDeltaTowardCenter(ev)
                    if (!dragActive && kotlin.math.abs(deltaPx) > slop) {
                        dragActive = true
                        settleAnimator?.cancel()
                        // Only now that the user is really dragging do we expand the window
                        // so the panel can be laid out across the screen.
                        if (!expanded) expandWindow()
                    }
                    if (dragActive) {
                        val delta = deltaPx / panelTravelPx().toFloat()
                        setProgress((dragStartProgress + delta).coerceIn(0f, 1f))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragActive) {
                        val target = if (progress > 0.45f) 1f else 0f
                        animateTo(target)
                    } else if (!expanded && progress == 0f) {
                        // Touch without drag on the collapsed strip — keep window narrow,
                        // no state change.
                        collapseWindowIfNeeded()
                    }
                    dragActive = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setProgress(p: Float) {
        val wasZero = progress == 0f
        progress = p
        val panelView = panelContainer ?: return
        panelView.visibility = View.VISIBLE
        applyPanelProgress(panelView, p)
        val scrimView = scrim
        if (scrimView != null) {
            scrimView.visibility = if (p > 0f) View.VISIBLE else View.GONE
            scrimView.alpha = p * 0.45f
        }
        val entryAlpha = (1f - p).coerceIn(0f, 1f)
        handleView?.alpha = entryAlpha
        iconButton?.alpha = entryAlpha
        expanded = p >= 0.99f
        // Stop the pulse as soon as the drawer starts opening — no point burning invalidate
        // cycles on a handle the user has already grabbed. Resume when fully collapsed.
        if (entryMode == OverlayMode.DRAWER) {
            if (p > 0f && wasZero) {
                stopHandlePulse()
            } else if (p == 0f && !wasZero) {
                startHandlePulse()
            }
        }
    }

    private fun animateTo(target: Float) {
        // Snap instantly instead of animating the slide — the panel should pop
        // straight to its resting position the moment the drag/tap resolves,
        // no ValueAnimator/interpolator easing in between.
        settleAnimator?.cancel()
        settleAnimator = null
        setProgress(target)
        if (target == 0f) {
            collapseWindowIfNeeded()
        }
    }

    private fun expandWindow() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        // In ICON_BUTTON mode the collapsed window was offset to the icon's position;
        // when expanded to fullscreen we need to reset x/y so the scrim and panel
        // cover the entire display rather than starting at the icon.
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = 0
            lp.y = 0
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "expandWindow failed", it) }
    }

    private fun collapseWindowIfNeeded() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        val collapsedW = collapsedWindowWidth()
        val collapsedH = collapsedWindowHeight()
        val needsResize = lp.width != collapsedW || lp.height != collapsedH
        val needsReposition = entryMode == OverlayMode.ICON_BUTTON && (lp.x != iconX || lp.y != iconY)
        if (!needsResize && !needsReposition) return
        lp.width = collapsedW
        lp.height = collapsedH
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = iconX
            lp.y = iconY
        }
        scrim?.visibility = View.GONE
        panelContainer?.visibility = View.GONE
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "collapseWindowIfNeeded failed", it) }
    }

    /**
     * Re-reads display metrics, recomputes panel/icon dimensions and re-applies
     * layout params after a screen rotation or display configuration change.
     *
     * Without this, after rotating the device:
     *   - in ICON_BUTTON mode the icon ends up off-screen (the cached
     *     `iconX/iconY` reference the pre-rotation extents);
     *   - in DRAWER mode the panel is sized to the wrong axis, so it appears
     *     empty / clipped — that is the "non si vede più il drawer" symptom.
     *
     * Posted to the view's main-thread looper because WindowManager rejects
     * updateViewLayout calls from a binder thread on some OEMs.
     */
    fun onDisplayConfigurationChanged() {
        val r = root ?: return
        r.post {
            relayoutForCurrentDisplay()
        }
    }

    private fun relayoutForCurrentDisplay() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return

        // Stop any in-flight slide animation — the dimensions it was animating
        // toward are about to be invalidated.
        settleAnimator?.cancel()
        settleAnimator = null

        val dm = ctx.resources.displayMetrics
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        if (newW <= 0 || newH <= 0) return
        val sizeChanged = newW != screenW || newH != screenH
        screenW = newW
        screenH = newH

        // Recompute size-dependent dimensions. dp(...) is density-relative so
        // it's stable across rotations, but the screen-percentage clamps on
        // panel size are not.
        panelWidthPx = minOf(dp(430), (screenW * 0.92f).toInt())
        panelHeightPx = minOf(dp(620), (screenH * 0.92f).toInt())

        // ICON_BUTTON: clamp the icon back inside the new screen bounds so it
        // stays visible after a rotation that shrank the relevant axis.
        if (entryMode == OverlayMode.ICON_BUTTON && sizeChanged) {
            iconX = iconX.coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
            iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
        }

        // Rebuild panel layout params (size + gravity) on the new orientation.
        panelContainer?.let { pv ->
            pv.layoutParams = panelLayoutParams()
            applyPanelProgress(pv, progress)
        }
        // Same for the drawer handle pill — its preferred size depends on
        // whether the active edge is vertical or horizontal.
        handleView?.layoutParams = handleLayoutParams()

        // Reset the WindowManager params to match the current state (collapsed
        // vs expanded, icon vs drawer).
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) {
            // Expanded: nothing to resize, the panel itself was rebuilt above.
        } else {
            lp.width = collapsedWindowWidth()
            lp.height = collapsedWindowHeight()
            lp.gravity = collapsedWindowGravity()
            if (entryMode == OverlayMode.ICON_BUTTON) {
                lp.x = iconX
                lp.y = iconY
            } else {
                lp.x = 0
                lp.y = 0
            }
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "relayoutForCurrentDisplay updateViewLayout failed", it) }
        Log.i(TAG, "Drawer relayout for ${newW}x${newH}")
    }

    private fun startHandlePulse() {
        // Was an infinite ValueAnimator "breathing" the handle glow to make it
        // findable while collapsed — decorative only, but it kept the compositor
        // re-blending an overlay layer every ~16ms for the entire time the drawer
        // sits collapsed (i.e. most of a gaming session). Set once instead of
        // animating forever: zero ongoing CPU/GPU cost, and a static minimal
        // handle reads cleaner than a pulsing one anyway.
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        handleView?.setGlow(1f)
    }


    private fun stopHandlePulse() {
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        handleView?.setGlow(1f)
    }

    // --- small helpers & drawables ------------------------------------------------------

    private fun brandMark(): View {
        val size = dp(26)
        val radius = dp(7).toFloat()
        // Wrap the bitmap icon in a RoundedBitmapDrawable-equivalent (manual clip via
        // ShapeAppearance) by drawing it into a GradientDrawable with a bitmap shader.
        val mark = ImageView(ctx).apply {
            setImageResource(com.firstt175.deepdrop.R.drawable.lsfg_app_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        return mark
    }

    private fun crossDrawable(): android.graphics.drawable.Drawable {
        // Simple X drawn via a custom drawable (no vector asset needed).
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ON_SURFACE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(5).toFloat()
                canvas.drawLine(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, paint)
                canvas.drawLine(b.right - inset, b.top + inset, b.left + inset, b.bottom - inset, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun buildPanelBackground(): android.graphics.drawable.Drawable {
        // Flat, squared-off console shell. Keep the shadow/stroke subtle so the
        // panel reads like a dedicated in-game control console rather than a
        // Material quick-settings card.
        val shadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x55000000.toInt())
            cornerRadius = dp(4).toFloat()
        }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_PANEL_BG)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), COLOR_PANEL_STROKE)
        }
        val layers = LayerDrawable(arrayOf(shadow, body))
        layers.setLayerInset(0, 0, 0, 0, 0)
        layers.setLayerInset(1, 0, 0, 0, dp(2))
        return layers
    }

    private fun buildSeekTrack(): android.graphics.drawable.Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_TRACK_BG)
            cornerRadius = dp(3).toFloat()
        }
        val bgInset = android.graphics.drawable.InsetDrawable(bg, 0, dp(10), 0, dp(10))
        val progress = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(COLOR_ACCENT_DEEP, COLOR_PRIMARY),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
        }
        val progressScale = android.graphics.drawable.ScaleDrawable(
            progress, Gravity.START or Gravity.CENTER_VERTICAL, 1f, -1f,
        )
        progressScale.level = 0
        val progressInset = android.graphics.drawable.InsetDrawable(progressScale, 0, dp(10), 0, dp(10))
        val layers = LayerDrawable(arrayOf(bgInset, progressInset))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return layers
    }

    private fun buildSeekThumb(): android.graphics.drawable.Drawable {
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_PRIMARY)
            setStroke(dp(2), 0xFFFFFFFF.toInt())
            setSize(dp(20), dp(20))
        }
        return thumb
    }

    /**
     * Builds a collapsible section with a tappable header (brand-primary eyebrow label + chevron)
     * and a body [LinearLayout] that callers populate. Returns the body so the rest of the panel
     * construction can `body.addView(...)` the section's children.
     *
     * The whole wrapper (header + body) is the view that gets added to the scrolling panel, so the
     * section dividers/spacers between sections remain the caller's responsibility — same visual
     * language as before, just with toggle affordances.
     */
    /**
     * ROG Ally Command Center–style quick panel: always-visible brightness + volume
     * sliders at the very top of the drawer, above the collapsible detail sections.
     * Brightness uses a per-window override (no WRITE_SETTINGS permission needed);
     * volume drives the media stream via AudioManager.
     */
    private fun buildQuickControlsSection(panel: LinearLayout) {
        panel.addView(miniHeader("QUICK CONTROLS"))
        panel.addView(sectionSpacer(6))

        // --- Brightness -----------------------------------------------------------
        val initialBrightness = runCatching {
            android.provider.Settings.System.getInt(
                ctx.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(128).coerceIn(0, 255)

        val brightnessValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initialBrightness * 100 / 255)}%"
        }
        panel.addView(sliderRow(labelText = "Screen brightness", valueView = brightnessValue))
        panel.addView(SeekBar(ctx).apply {
            max = 100
            progress = (initialBrightness * 100 / 255).coerceIn(1, 100)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                val pct = p.coerceIn(1, 100)
                brightnessValue.text = "$pct%"
                if (!fromUser) return@onProgress
                val lp = params
                val r = root
                val wm = hostWindowManager
                if (lp != null && r != null && wm != null && r.isAttachedToWindow) {
                    lp.screenBrightness = pct / 100f
                    runCatching { wm.updateViewLayout(r, lp) }
                        .onFailure { Log.w(TAG, "brightness updateViewLayout failed", it) }
                }
            }
        })

        panel.addView(sectionSpacer(10))

        // --- Volume -----------------------------------------------------------------
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = runCatching {
            audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        }.getOrDefault(15).coerceAtLeast(1)
        val initialVol = runCatching {
            audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        }.getOrDefault(0).coerceIn(0, maxVol)

        val volumeValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initialVol * 100 / maxVol)}%"
        }
        panel.addView(sliderRow(labelText = "Media volume", valueView = volumeValue))
        panel.addView(SeekBar(ctx).apply {
            max = maxVol
            progress = initialVol
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                volumeValue.text = "${(p * 100 / maxVol)}%"
                if (!fromUser) return@onProgress
                runCatching {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0)
                }.onFailure { Log.w(TAG, "setStreamVolume failed", it) }
            }
        })
    }

    /**
     * "SOUND TUNER" section: global (session-0) Equalizer/BassBoost/Virtualizer/
     * LoudnessEnhancer, so it colors whatever the captured game is currently playing
     * without needing that app's own audio session. See [SoundTunerController] for the
     * attach mechanism and why it degrades gracefully instead of crashing on devices
     * that refuse session-0 effects.
     *
     * Bass boost / virtualizer / loudness sliders are shown as 0-100%, mapped onto the
     * effect's native units (0-1000 strength, 0-2000 millibel gain) — same "%" language
     * as the brightness/volume quick controls above. The per-band equalizer (if the
     * device's Equalizer engine is queryable) shows each band by its actual center
     * frequency and commits directly in millibels, matching [SoundTunerController]'s units.
     */
    private fun buildSoundTunerSection(panel: LinearLayout, prefs: LsfgPreferences) {
        val section = collapsibleSection(panel, "SOUND TUNER")
        val bandInfo = SoundTunerController.queryBandInfo()
        val savedBands = prefs.getSoundTunerEqBands()

        fun applyAllToController(controller: SoundTunerController) {
            controller.setBassBoostStrength(prefs.getSoundTunerBass())
            controller.setVirtualizerStrength(prefs.getSoundTunerVirtualizer())
            controller.setLoudnessGain(prefs.getSoundTunerLoudness())
            if (bandInfo != null && savedBands.size == bandInfo.bandCount) {
                savedBands.forEachIndexed { i, lvl -> controller.setEqBandLevel(i, lvl) }
            }
        }

        // Re-attach immediately if the tuner was left on from a previous session,
        // same as how frame-gen / FPS counter restore their prior on/off state.
        if (prefs.isSoundTunerEnabled()) {
            val controller = soundTuner ?: SoundTunerController().also { soundTuner = it }
            controller.enable()
            applyAllToController(controller)
        }

        section.addView(switchRow(
            label = "Enable sound tuner",
            initial = prefs.isSoundTunerEnabled(),
        ) { enabled ->
            Log.i(TAG, "live: soundTunerEnabled=$enabled")
            prefs.setSoundTunerEnabled(enabled)
            if (enabled) {
                val controller = soundTuner ?: SoundTunerController().also { soundTuner = it }
                controller.enable()
                applyAllToController(controller)
            } else {
                soundTuner?.disable()
                soundTuner = null
            }
        })

        // ---- Presets: one-tap fill for the sliders below. Declared as lateinit
        // because the row is placed above the sliders it drives (same forward-
        // reference pattern as the frame-pacing preset row above), but every
        // reference below only actually runs from a button tap, by which point
        // buildSoundTunerSection has already finished assigning them.
        lateinit var bassSeek: SeekBar
        lateinit var virtSeek: SeekBar
        lateinit var loudSeek: SeekBar
        lateinit var bassValue: TextView
        lateinit var virtValue: TextView
        lateinit var loudValue: TextView
        val bandSeeks = mutableListOf<SeekBar>()
        val bandValues = mutableListOf<TextView>()

        fun applyPreset(preset: SoundTunerController.Preset) {
            Log.i(TAG, "live: soundTuner preset=${preset.label}")
            prefs.setSoundTunerBass(preset.bass)
            prefs.setSoundTunerVirtualizer(preset.virtualizer)
            prefs.setSoundTunerLoudness(preset.loudness)
            soundTuner?.setBassBoostStrength(preset.bass)
            soundTuner?.setVirtualizerStrength(preset.virtualizer)
            soundTuner?.setLoudnessGain(preset.loudness)

            bassSeek.progress = preset.bass / 10
            virtSeek.progress = preset.virtualizer / 10
            loudSeek.progress = preset.loudness / 20
            bassValue.text = "${preset.bass / 10}%"
            virtValue.text = "${preset.virtualizer / 10}%"
            loudValue.text = "${preset.loudness / 20}%"

            if (bandInfo != null && bandInfo.bandCount > 0 && bandSeeks.size == bandInfo.bandCount) {
                val range = bandInfo.levelRange
                val span = (range[1] - range[0]).coerceAtLeast(1)
                val newBands = bandInfo.centerFreqsHz.map { freq ->
                    SoundTunerController.presetLevelForBand(preset.eqShapeAt(freq), range)
                }
                prefs.setSoundTunerEqBands(newBands)
                newBands.forEachIndexed { i, level ->
                    soundTuner?.setEqBandLevel(i, level)
                    bandSeeks[i].progress = (level - range[0]).coerceIn(0, span)
                    bandValues[i].text = String.format("%.1f dB", level / 100f)
                }
            }
        }

        section.addView(miniHeader("Presets"))
        val soundPresetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        SoundTunerController.PRESETS.forEachIndexed { index, preset ->
            val button = Button(ctx).apply {
                text = preset.label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                    setColor(COLOR_CHIP_BG)
                }
                setPadding(dp(3), dp(4), dp(3), dp(4))
                stateListAnimator = null
                setOnClickListener { applyPreset(preset) }
            }
            soundPresetRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (index == 0) 0 else dp(4)
            })
        }
        section.addView(soundPresetRow)

        section.addView(miniHeader("Bass & stereo"))

        bassValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerBass() / 10}%"
        }
        section.addView(sliderRow(labelText = "Bass boost", valueView = bassValue))
        bassSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerBass() / 10
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                bassValue.text = "$p%"
                if (!fromUser) return@onProgress
                val strength = (p * 10).coerceIn(0, 1000)
                prefs.setSoundTunerBass(strength)
                soundTuner?.setBassBoostStrength(strength)
            }
        }
        section.addView(bassSeek)

        virtValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerVirtualizer() / 10}%"
        }
        section.addView(sliderRow(labelText = "Virtualizer (surround)", valueView = virtValue))
        virtSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerVirtualizer() / 10
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                virtValue.text = "$p%"
                if (!fromUser) return@onProgress
                val strength = (p * 10).coerceIn(0, 1000)
                prefs.setSoundTunerVirtualizer(strength)
                soundTuner?.setVirtualizerStrength(strength)
            }
        }
        section.addView(virtSeek)

        loudValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerLoudness() / 20}%"
        }
        section.addView(sliderRow(labelText = "Loudness boost", valueView = loudValue))
        loudSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerLoudness() / 20
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                loudValue.text = "$p%"
                if (!fromUser) return@onProgress
                val gain = (p * 20).coerceIn(0, 2000)
                prefs.setSoundTunerLoudness(gain)
                soundTuner?.setLoudnessGain(gain)
            }
        }
        section.addView(loudSeek)

        // Per-band EQ — only shown if this device's Equalizer engine answered the
        // metadata query; some OEMs refuse session-0 Equalizer entirely.
        if (bandInfo != null && bandInfo.bandCount > 0) {
            section.addView(miniHeader("Equalizer"))
            val range = bandInfo.levelRange
            val span = (range[1] - range[0]).coerceAtLeast(1)
            val workingBands = (savedBands.takeIf { it.size == bandInfo.bandCount }
                ?: List(bandInfo.bandCount) { 0 }).toMutableList()

            for (band in 0 until bandInfo.bandCount) {
                val freqHz = bandInfo.centerFreqsHz[band]
                val freqLabel = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                val bandValue = TextView(ctx).apply {
                    setTextColor(COLOR_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                    text = String.format("%.1f dB", workingBands[band] / 100f)
                }
                section.addView(sliderRow(labelText = freqLabel, valueView = bandValue))
                val bandSeek = SeekBar(ctx).apply {
                    max = span
                    progress = (workingBands[band] - range[0]).coerceIn(0, span)
                    progressDrawable = buildSeekTrack()
                    thumb = buildSeekThumb()
                    splitTrack = false
                    onProgress { p, fromUser ->
                        val level = p + range[0]
                        bandValue.text = String.format("%.1f dB", level / 100f)
                        if (!fromUser) return@onProgress
                        workingBands[band] = level
                        prefs.setSoundTunerEqBands(workingBands)
                        soundTuner?.setEqBandLevel(band, level)
                    }
                }
                section.addView(bandSeek)
                bandSeeks.add(bandSeek)
                bandValues.add(bandValue)
            }
        }
    }

    private fun collapsibleSection(
        parent: LinearLayout,
        title: String,
        initiallyExpanded: Boolean = false,
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chevron = ImageView(ctx).apply {
            setImageDrawable(chevronDrawable())
            val sz = dp(14)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            rotation = if (initiallyExpanded) 180f else 0f
        }

        val label = TextView(ctx).apply {
            text = title
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.15f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            isClickable = true
            isFocusable = true
            // Subtle ripple on tap to hint at interactivity; stays on-brand because the body will
            // just fade-slide via visibility toggle.
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            addView(label)
            addView(chevron)
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }

        header.setOnClickListener {
            val nowVisible = body.visibility != View.VISIBLE
            body.visibility = if (nowVisible) View.VISIBLE else View.GONE
            // Snap instantly — no rotate animation, matches the drawer's
            // instant open/close (see animateTo()).
            chevron.rotation = if (nowVisible) 180f else 0f
        }

        wrapper.addView(header)
        wrapper.addView(body)
        parent.addView(wrapper)
        return body
    }

    private fun chevronDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_PRIMARY
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(3).toFloat()
                val midX = (b.left + b.right) / 2f
                canvas.drawLine(b.left + inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
                canvas.drawLine(b.right - inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }

    private fun sectionSpacer(height: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height),
        )
    }

    /**
     * Small uppercase caption used inside a section to group related chip rows.
     * Styled to match a Quick-Settings-style group label (e.g. "OTHER",
     * "CONTROLLER") — dim grey, wide letter-spacing, extra top margin so it
     * visually separates from the group above it.
     */
    private fun miniHeader(text: String) = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(COLOR_ON_SURFACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        alpha = 0.55f
        letterSpacing = 0.14f
        typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun <T> chipRow(
        items: List<Pair<T, String>>,
        initial: T,
        onSelected: (T) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val buttons = mutableListOf<Button>()
        fun paint(selected: T) {
            buttons.forEachIndexed { i, btn ->
                val isSel = items[i].first == selected
                btn.setTextColor(if (isSel) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (btn.background as? GradientDrawable)?.setColor(
                    if (isSel) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }
        items.forEach { (value, label) ->
            val btn = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                stateListAnimator = null
                setOnClickListener {
                    onSelected(value)
                    paint(value)
                }
            }
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                leftMargin = if (buttons.isEmpty()) 0 else dp(6)
            }
            row.addView(btn, lp)
            buttons += btn
        }
        paint(initial)
        return row
    }

    private fun drawerEdgeChipRow(
        initial: DrawerEdge,
        onSelected: (DrawerEdge) -> Unit,
    ): View {
        val items = listOf(
            DrawerEdge.LEFT to "Left",
            DrawerEdge.RIGHT to "Right",
            DrawerEdge.TOP to "Top",
            DrawerEdge.BOTTOM to "Bottom",
        )
        return chipRow(items, initial, onSelected)
    }

    private fun sliderRow(labelText: String, valueView: TextView): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        row.addView(
            TextView(ctx).apply {
                text = labelText
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val chip = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = dp(2).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            addView(valueView)
        }
        row.addView(chip)
        return row
    }

    /**
     * A toggle row plus its own bottom hairline divider, so a stack of
     * switchRow()s reads as a list of separated settings lines (like the
     * reference Quick Settings panel) instead of one dense block.
     */
    private fun switchRow(
        label: String,
        initial: Boolean,
        swRef: ((Switch) -> Unit)? = null,
        onChange: (Boolean) -> Unit,
    ): View {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(6), dp(9))
        }
        val lbl = TextView(ctx).apply {
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx).apply {
            isChecked = initial
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onChange(checked) }
            // Tint switch to match brand primary when checked.
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val trackColors = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(
                    COLOR_PRIMARY,
                    COLOR_TRACK_BG,
                ),
            )
            trackTintList = trackColors
            // Toggling should snap immediately (Switch has no built-in thumb-slide
            // animation to disable — jumpDrawablesToCurrentState skips any residual
            // state-transition drawable animation on some OEM skins).
            jumpDrawablesToCurrentState()
        }
        // Lets a caller hold onto this specific Switch (e.g. so a preset row
        // elsewhere can force its isChecked state later) without every other
        // switchRow() call site needing to care.
        swRef?.invoke(sw)
        row.addView(lbl)
        row.addView(sw)
        wrapper.addView(row)
        wrapper.addView(divider())
        return wrapper
    }

    private fun dp(v: Int): Int {
        return (v * ctx.resources.displayMetrics.density).toInt()
    }

    private fun isVerticalEdge(): Boolean =
        drawerEdge == DrawerEdge.LEFT || drawerEdge == DrawerEdge.RIGHT

    private fun collapsedWindowWidth(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> iconSizePx
        OverlayMode.DRAWER ->
            if (isVerticalEdge()) edgeStripWidthPx else WindowManager.LayoutParams.MATCH_PARENT
    }

    private fun collapsedWindowHeight(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> iconSizePx
        OverlayMode.DRAWER ->
            if (isVerticalEdge()) WindowManager.LayoutParams.MATCH_PARENT else edgeStripWidthPx
    }

    private fun collapsedWindowGravity(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> Gravity.TOP or Gravity.START
        OverlayMode.DRAWER -> when (drawerEdge) {
            DrawerEdge.LEFT -> Gravity.TOP or Gravity.START
            DrawerEdge.RIGHT -> Gravity.TOP or Gravity.END
            DrawerEdge.TOP -> Gravity.TOP or Gravity.START
            DrawerEdge.BOTTOM -> Gravity.BOTTOM or Gravity.START
        }
    }

    private fun panelTravelPx(): Int =
        if (isVerticalEdge()) panelWidthPx.coerceAtLeast(1) else panelHeightPx.coerceAtLeast(1)

    private fun panelLayoutParams(): FrameLayout.LayoutParams =
        if (isVerticalEdge()) {
            FrameLayout.LayoutParams(
                panelWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (drawerEdge == DrawerEdge.LEFT) Gravity.START else Gravity.END,
            )
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                panelHeightPx,
                if (drawerEdge == DrawerEdge.TOP) Gravity.TOP else Gravity.BOTTOM,
            )
        }

    private fun handleLayoutParams(): FrameLayout.LayoutParams =
        if (isVerticalEdge()) {
            FrameLayout.LayoutParams(
                handleWidthPx + dp(8),
                handleHeightPx,
                Gravity.CENTER_VERTICAL or
                    (if (drawerEdge == DrawerEdge.LEFT) Gravity.START else Gravity.END),
            )
        } else {
            FrameLayout.LayoutParams(
                handleHeightPx,
                handleWidthPx + dp(8),
                Gravity.CENTER_HORIZONTAL or
                    (if (drawerEdge == DrawerEdge.TOP) Gravity.TOP else Gravity.BOTTOM),
            )
        }

    // --- Icon button entry mode --------------------------------------------------------

    /**
     * Floating circular icon (LSFG app icon on a translucent disc) used as the entry
     * affordance for the in-game settings panel when [entryMode] == ICON_BUTTON.
     */
    private fun buildIconButton(): View {
        val container = FrameLayout(ctx).apply {
            // The window is sized exactly to the icon, so the button just fills it.
            isClickable = true
            isFocusable = false
        }
        // Show the app launcher icon as-is (square with rounded corners),
        // matching the icon the user sees on the home screen. No disc /
        // background — the drawable already includes the rounded mask.
        val iconView = ImageView(ctx).apply {
            setImageResource(com.firstt175.deepdrop.R.drawable.lsfg_app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(
            iconView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    private fun iconButtonLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.TOP or Gravity.START)

    /**
     * Touch-handling for the floating icon: drag to reposition (the WindowManager
     * lp.x/lp.y get updated live), tap (no significant movement) to open the panel.
     * After release we snap horizontally to whichever screen edge the icon is closer
     * to so it doesn't end up floating mid-screen, and we update [drawerEdge] so the
     * panel slides in from that same side.
     */
    private fun attachIconButtonBehavior(icon: View) {
        val touchSlop = dp(8)
        icon.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iconDragStartRawX = ev.rawX
                    iconDragStartRawY = ev.rawY
                    iconDragStartX = iconX
                    iconDragStartY = iconY
                    iconDragMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - iconDragStartRawX
                    val dy = ev.rawY - iconDragStartRawY
                    if (!iconDragMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        iconDragMoved = true
                    }
                    if (iconDragMoved) {
                        iconX = (iconDragStartX + dx.toInt()).coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
                        iconY = (iconDragStartY + dy.toInt()).coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        if (!iconDragFramePending) {
                            iconDragFramePending = true
                            Choreographer.getInstance().postFrameCallback(iconDragFrameCallback)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (iconDragMoved) {
                        // Cancel any in-flight drag-frame callback so it doesn't
                        // race with the snap-to-edge updateViewLayout below.
                        if (iconDragFramePending) {
                            Choreographer.getInstance().removeFrameCallback(iconDragFrameCallback)
                            iconDragFramePending = false
                        }
                        // Snap to nearest horizontal edge and infer the slide-in side
                        // for the panel from the icon's current position.
                        val centerX = iconX + iconSizePx / 2
                        val centerY = iconY + iconSizePx / 2
                        val nearLeft = centerX < screenW / 2
                        iconX = if (nearLeft) dp(8) else (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
                        iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        // Pick the closer edge (left/right vs top/bottom) for panel slide direction.
                        val distLeft = centerX
                        val distRight = screenW - centerX
                        val distTop = centerY
                        val distBottom = screenH - centerY
                        drawerEdge = listOf(
                            DrawerEdge.LEFT to distLeft,
                            DrawerEdge.RIGHT to distRight,
                            DrawerEdge.TOP to distTop,
                            DrawerEdge.BOTTOM to distBottom,
                        ).minByOrNull { it.second }?.first ?: DrawerEdge.RIGHT
                        // Re-build the panel layout params so the panel slides in from
                        // the new edge on next open.
                        panelContainer?.let { pv ->
                            pv.layoutParams = panelLayoutParams()
                            applyPanelProgress(pv, progress)
                        }
                        val lp = params
                        val r = root
                        val wm = hostWindowManager
                        if (lp != null && r != null && wm != null && r.isAttachedToWindow &&
                            lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
                            lp.x = iconX
                            lp.y = iconY
                            runCatching { wm.updateViewLayout(r, lp) }
                                .onFailure { Log.w(TAG, "icon snap updateViewLayout failed", it) }
                        }
                    } else {
                        // Tap — open the panel.
                        if (!expanded) {
                            expandWindow()
                            animateTo(1f)
                        }
                    }
                    iconDragMoved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun dragDeltaTowardCenter(ev: MotionEvent): Float = when (drawerEdge) {
        DrawerEdge.LEFT -> ev.rawX - dragStartX
        DrawerEdge.RIGHT -> dragStartX - ev.rawX
        DrawerEdge.TOP -> ev.rawY - dragStartY
        DrawerEdge.BOTTOM -> dragStartY - ev.rawY
    }

    private fun applyPanelProgress(panelView: View, p: Float) {
        panelView.translationX = 0f
        panelView.translationY = 0f
        when (drawerEdge) {
            DrawerEdge.LEFT -> panelView.translationX = -(1f - p) * panelWidthPx
            DrawerEdge.RIGHT -> panelView.translationX = (1f - p) * panelWidthPx
            DrawerEdge.TOP -> panelView.translationY = -(1f - p) * panelHeightPx
            DrawerEdge.BOTTOM -> panelView.translationY = (1f - p) * panelHeightPx
        }
    }

    // --- handle view -------------------------------------------------------------------

    private inner class HandleView(ctx: Context) : View(ctx) {
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var glow: Float = 1f
        private var shader: LinearGradient? = null

        fun setGlow(v: Float) {
            if (glow == v) return
            glow = v
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Build the gradient shader once per size change instead of every draw.
            if (isVerticalEdge()) {
                val pillWidth = handleWidthPx.toFloat()
                val pillHeight = handleHeightPx.toFloat()
                val right = if (drawerEdge == DrawerEdge.LEFT) {
                    dp(4).toFloat() + pillWidth
                } else {
                    w.toFloat() - dp(4)
                }
                val left = right - pillWidth
                val top = (h.toFloat() - pillHeight) / 2f
                val bottom = top + pillHeight
                shader = LinearGradient(
                    left, top, left, bottom,
                    COLOR_PRIMARY, COLOR_ACCENT_DEEP,
                    Shader.TileMode.CLAMP,
                )
            } else {
                val pillWidth = handleHeightPx.toFloat()
                val pillHeight = handleWidthPx.toFloat()
                val left = (w.toFloat() - pillWidth) / 2f
                val right = left + pillWidth
                val top = if (drawerEdge == DrawerEdge.TOP) {
                    dp(4).toFloat()
                } else {
                    h.toFloat() - dp(4) - pillHeight
                }
                val bottom = top + pillHeight
                shader = LinearGradient(
                    left, top, right, top,
                    COLOR_PRIMARY, COLOR_ACCENT_DEEP,
                    Shader.TileMode.CLAMP,
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            pillPaint.shader = shader
            pillPaint.alpha = (255 * glow).toInt().coerceIn(120, 255)
            val w = width.toFloat()
            val h = height.toFloat()
            val pillWidth = if (isVerticalEdge()) handleWidthPx.toFloat() else handleHeightPx.toFloat()
            val pillHeight = if (isVerticalEdge()) handleHeightPx.toFloat() else handleWidthPx.toFloat()
            val left = when {
                isVerticalEdge() && drawerEdge == DrawerEdge.LEFT -> dp(4).toFloat()
                isVerticalEdge() -> w - dp(4) - pillWidth
                else -> (w - pillWidth) / 2f
            }
            val top = when {
                !isVerticalEdge() && drawerEdge == DrawerEdge.TOP -> dp(4).toFloat()
                !isVerticalEdge() -> h - dp(4) - pillHeight
                else -> (h - pillHeight) / 2f
            }
            val right = left + pillWidth
            val bottom = top + pillHeight
            val radius = minOf(pillWidth, pillHeight) / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, pillPaint)
        }
    }

    companion object {
        private const val TAG = "SettingsDrawer"

        // Brand palette — mirrors ui/theme/Color.kt's amber/teal retro-terminal
        // scheme so the in-game overlay and the in-app screens read as the same
        // product instead of two different color languages.
        private const val COLOR_PRIMARY = 0xFFE3A857.toInt()
        private const val COLOR_ON_PRIMARY = 0xFF35230A.toInt()
        private const val COLOR_ACCENT_DEEP = 0xFF9C6423.toInt()
        private const val COLOR_ON_SURFACE = 0xFFEDE7DE.toInt()
        private const val COLOR_PANEL_BG = 0xF01A1811.toInt()
        private const val COLOR_PANEL_STROKE = 0x55E3A857
        private const val COLOR_DIVIDER = 0x26FFFFFF
        private const val COLOR_TRACK_BG = 0xFF2A271D.toInt()
        private const val COLOR_CHIP_BG = 0x22FFFFFF
        private const val COLOR_STOP_BG = 0xFF2B1613.toInt()
        private const val COLOR_STOP_STROKE = 0x66CF7D72.toInt()
        private const val COLOR_STOP_TEXT = 0xFFCF7D72.toInt()
        private const val COLOR_START_BG = 0xFF17261A.toInt()
        private const val COLOR_START_STROKE = 0x667DCF8A.toInt()
        private const val COLOR_START_TEXT = 0xFF7DCF8A.toInt()
    }
}
