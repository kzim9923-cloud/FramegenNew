package com.firstt175.deepdrop.prefs

import android.content.Context

/**
 * Controls the warp-style loading/pass-through screen shown for a moment
 * right after the app process starts, before the launcher UI ([Routes.HOME])
 * appears. The screen itself does no interactive configuration — it is a
 * short animated gate while startup state (appearance, display profile,
 * etc.) is prepared — so the only user-facing setting is whether to show it
 * at all.
 */
object LoadingScreenPrefs {
    private const val NAME = "lsfg_loading_screen"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
