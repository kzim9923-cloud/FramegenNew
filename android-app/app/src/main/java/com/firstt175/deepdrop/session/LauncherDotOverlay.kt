package com.firstt175.deepdrop.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.firstt175.deepdrop.prefs.DrawerEdge
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.prefs.OverlayMode

/**
 * "Automatic Overlay" entry point. A small edge handle (DRAWER mode) or floating
 * icon (ICON_BUTTON mode) shown over a user-selected target app.
 *
 * There is no intermediate panel/sheet — tapping the affordance calls [onActivate]
 * directly (starts the LSFG capture session for the current foreground app);
 * long-pressing calls [onDisableForApp] (turns Automatic Overlay off for that app).
 * This mirrors the two actions the old sheet panel used to expose, without the
 * extra drag-to-open step.
 *
 * Palette is yellow/orange so users can distinguish this from the in-game
 * [SettingsDrawerOverlay] (blue), which remains a full drawer since it hosts many
 * more controls than a single activate/disable pair.
 *
 * Layout is resolution/DPI-reactive: [registerDisplayListener] re-reads
 * [Context.resources]' displayMetrics and recomputes every size on any
 * [DisplayManager.DisplayListener.onDisplayChanged] callback — which fires both
 * for ordinary rotations and for the app's own per-app resolution/DPI override
 * (`AdbDisplayController`, backed by `wm size` / `wm density`), since either one
 * changes the metrics this Context reports.
 */
class LauncherDotOverlay(
    private val ctx: Context,
    private val targetPackageProvider: () -> String?,
    private val onActivate: () -> Unit,
    private val onDisableForApp: () -> Unit,
    private val entryMode: OverlayMode = OverlayMode.ICON_BUTTON,
) {

    companion object {
        private const val TAG = "LauncherHandle"
        // Yellow/orange palette — mirrors the blue palette in SettingsDrawerOverlay.
        private const val COLOR_PRIMARY = 0xFFFFC857.toInt()
        private const val COLOR_ACCENT_DEEP = 0xFFFF8A2B.toInt()
    }

    private var hostWm: WindowManager? = null
    private var root: FrameLayout? = null
    private var handleView: HandleView? = null
    private var params: WindowManager.LayoutParams? = null

    private var screenW: Int = 0
    private var screenH: Int = 0
    private var edgeStripWidthPx: Int = 0
    private var handleWidthPx: Int = 0
    private var handleHeightPx: Int = 0
    private var drawerEdge: DrawerEdge = DrawerEdge.RIGHT

    // The launcher handle lives outside any Service / Activity, so we listen
    // for display changes directly via DisplayManager. Without this the
    // collapsed strip / icon ends up off-screen (or sized for the wrong DPI)
    // the first time the display rotates or gets resized/re-densified while
    // the handle is showing — no Service.onConfigurationChanged path can
    // reach us here because AutoOverlayController owns the lifecycle.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var hostDisplayId: Int = Display.DEFAULT_DISPLAY

    private var pulseAnimator: ValueAnimator? = null

    // ICON_BUTTON mode state — mirrors SettingsDrawerOverlay.
    private var iconButton: View? = null
    private var iconSizePx: Int = 0
    private var iconX: Int = 0
    private var iconY: Int = 0
    private var iconDragStartRawX: Float = 0f
    private var iconDragStartRawY: Float = 0f
    private var iconDragStartX: Int = 0
    private var iconDragStartY: Int = 0
    private var iconDragMoved: Boolean = false
    // Coalesce icon-drag updateViewLayout calls to one per vsync. Touch events
    // can fire at 240+ Hz on flagships and each updateViewLayout is a Binder RPC
    // to WindowManagerService, so without this the drag floods the system
    // window thread.
    private var iconDragFramePending: Boolean = false
    private val iconDragFrameCallback = Choreographer.FrameCallback {
        iconDragFramePending = false
        val lp = params
        val r = root
        val wm = hostWm
        if (lp != null && r != null && wm != null && r.isAttachedToWindow) {
            lp.x = iconX
            lp.y = iconY
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "icon drag updateViewLayout failed", it) }
        }
    }

    fun show() {
        if (root != null) return

        val a11y = LsfgAccessibilityService.instance
        val hostCtx: Context = a11y ?: ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWm = wm
        hostDisplayId = runCatching { hostCtx.display?.displayId }.getOrNull()
            ?: Display.DEFAULT_DISPLAY

        recomputeSizesForCurrentDisplay(resetIconPosition = true)
        drawerEdge = LsfgPreferences(ctx).load().drawerEdge

        val layoutType = when {
            a11y != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

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

        if (entryMode == OverlayMode.DRAWER) {
            val handle = HandleView(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            handleView = handle
            rootLayout.addView(handle, handleLayoutParams())
            attachHandleTapBehavior(handle)
            startHandlePulse()
        } else {
            val icon = buildIconButton()
            iconButton = icon
            rootLayout.addView(icon, iconButtonLayoutParams())
            attachIconButtonBehavior(icon)
        }

        runCatching { wm.addView(rootLayout, lp) }
            .onFailure { Log.w(TAG, "addView failed", it) }
        registerDisplayListener()
        Log.i(TAG, "Launcher entry shown (mode=$entryMode)")
    }

    fun hide() {
        val r = root ?: return
        val wm = hostWm
        unregisterDisplayListener()
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (wm != null) {
            runCatching { wm.removeView(r) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        root = null
        handleView = null
        iconButton = null
        params = null
        hostWm = null
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != hostDisplayId) return
                mainHandler.post { relayoutForCurrentDisplay() }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { Log.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    /**
     * Re-reads displayMetrics (width, height AND density — `dp()` below always
     * reads density live, so every size computed from it already tracks a DPI
     * override, not just a resolution/rotation change) and recomputes every
     * size-dependent field. Shared by [show] (resetIconPosition = true) and
     * [relayoutForCurrentDisplay] (resetIconPosition = false, so an
     * already-positioned icon is clamped into the new bounds instead of being
     * re-centered).
     */
    private fun recomputeSizesForCurrentDisplay(resetIconPosition: Boolean) {
        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        edgeStripWidthPx = dp(16)
        handleWidthPx = dp(5)
        handleHeightPx = dp(68)
        iconSizePx = dp(48)
        if (resetIconPosition) {
            // Default: stuck to the right edge, vertically centred.
            iconX = (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
            iconY = ((screenH - iconSizePx) / 2).coerceAtLeast(0)
        } else {
            // Clamp the existing position back inside the new bounds so the
            // icon stays visible/tappable after a resolution or DPI change.
            iconX = iconX.coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
            iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
        }
    }

    private fun relayoutForCurrentDisplay() {
        val wm = hostWm ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return

        val prevW = screenW
        val prevH = screenH
        val prevDensity = ctx.resources.displayMetrics.density
        recomputeSizesForCurrentDisplay(resetIconPosition = false)
        if (screenW <= 0 || screenH <= 0) return
        if (screenW == prevW && screenH == prevH &&
            ctx.resources.displayMetrics.density == prevDensity
        ) {
            return
        }

        // Reload preferred edge in case the user toggled it via the in-game
        // drawer while the handle was hidden, then rebuild edge-dependent children.
        drawerEdge = LsfgPreferences(ctx).load().drawerEdge
        handleView?.layoutParams = handleLayoutParams()

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
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "relayoutForCurrentDisplay updateViewLayout failed", it) }
        Log.i(TAG, "Launcher relayout for ${screenW}x${screenH} mode=$entryMode")
    }

    // --- tap / long-press behaviour (DRAWER handle) ---------------------------------

    private fun attachHandleTapBehavior(handle: View) {
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            onDisableForApp()
        }
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.abs(ev.rawX - downX) > touchSlop ||
                        kotlin.math.abs(ev.rawY - downY) > touchSlop
                    if (moved) mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!longPressFired) onActivate()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

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

    private fun isVerticalEdge(): Boolean =
        drawerEdge == DrawerEdge.LEFT || drawerEdge == DrawerEdge.RIGHT

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

    private fun startHandlePulse() {
        // Set once rather than animated forever — a static minimal glow reads as
        // just as findable while costing nothing while idle. See SettingsDrawerOverlay
        // for the same tradeoff on the in-game drawer's handle.
        pulseAnimator?.cancel()
        pulseAnimator = null
        handleView?.setGlow(1f)
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Vertical rounded pill flush to the active edge with a top→bottom yellow→orange
     * gradient. Identical geometry to [SettingsDrawerOverlay.HandleView]; only the
     * colors differ.
     */
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

    // --- Icon button entry mode --------------------------------------------------------

    /**
     * Floating circular icon (LSFG app icon) used as the entry affordance when
     * [entryMode] == ICON_BUTTON. Tap activates; long-press disables for the app;
     * drag repositions.
     */
    private fun buildIconButton(): View {
        val container = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = false
        }
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
     * Drag the floating icon to reposition; tap (no significant movement) activates;
     * long-press (no significant movement) disables for the app. After a drag we snap
     * horizontally to the nearer screen edge so the icon doesn't end up floating
     * mid-screen.
     */
    private fun attachIconButtonBehavior(icon: View) {
        val touchSlop = dp(8)
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            onDisableForApp()
        }
        icon.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iconDragStartRawX = ev.rawX
                    iconDragStartRawY = ev.rawY
                    iconDragStartX = iconX
                    iconDragStartY = iconY
                    iconDragMoved = false
                    longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - iconDragStartRawX
                    val dy = ev.rawY - iconDragStartRawY
                    if (!iconDragMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        iconDragMoved = true
                        mainHandler.removeCallbacks(longPressRunnable)
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
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (iconDragMoved) {
                        if (iconDragFramePending) {
                            Choreographer.getInstance().removeFrameCallback(iconDragFrameCallback)
                            iconDragFramePending = false
                        }
                        val centerX = iconX + iconSizePx / 2
                        val nearLeft = centerX < screenW / 2
                        iconX = if (nearLeft) dp(8) else (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
                        iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        val lp = params
                        val r = root
                        val wm = hostWm
                        if (lp != null && r != null && wm != null && r.isAttachedToWindow) {
                            lp.x = iconX
                            lp.y = iconY
                            runCatching { wm.updateViewLayout(r, lp) }
                                .onFailure { Log.w(TAG, "icon snap updateViewLayout failed", it) }
                        }
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP && !longPressFired) {
                        onActivate()
                    }
                    iconDragMoved = false
                    true
                }
                else -> false
            }
        }
    }
}
