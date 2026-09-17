package com.firstt175.deepdrop.prefs

import android.content.Context
import com.firstt175.deepdrop.R

/**
 * The available visual styles for the loading/pass-through screen. Each maps
 * to a stateless overlay composable in `LoadingOverlays.kt`; see
 * `LoadingOverlay(style, title)` for the dispatch.
 */
enum class LoadingStyle(val prefKey: String, val labelRes: Int) {
    WARP("warp", R.string.loading_style_warp),
    PULSE("pulse", R.string.loading_style_pulse),
    ORBIT("orbit", R.string.loading_style_orbit),
    WAVE("wave", R.string.loading_style_wave),
    MINIMAL("minimal", R.string.loading_style_minimal),
    ;

    companion object {
        val DEFAULT = WARP
        fun fromPrefKey(key: String?): LoadingStyle = entries.find { it.prefKey == key } ?: DEFAULT
    }
}

/**
 * Controls the loading/pass-through screen shown for a moment right after
 * the app process starts, before the launcher UI ([Routes.HOME]) appears.
 * The screen itself does no interactive configuration — it is a short
 * animated gate while startup state (appearance, display profile, etc.) is
 * prepared — so the user-facing settings are just whether to show it at all,
 * and which of the available [LoadingStyle] visuals to use. The same style
 * is also reused for the pre-game-launch overlay in `GameLauncherScreen`.
 */
object LoadingScreenPrefs {
    private const val NAME = "lsfg_loading_screen"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STYLE = "style"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getStyle(ctx: Context): LoadingStyle =
        LoadingStyle.fromPrefKey(
            ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_STYLE, LoadingStyle.DEFAULT.prefKey),
        )

    fun setStyle(ctx: Context, style: LoadingStyle) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STYLE, style.prefKey)
            .apply()
    }
}
