package com.firstt175.deepdrop.prefs

import android.content.Context

enum class AppThemeMode(val value: String) {
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.value == value } ?: DARK
    }
}

data class AppAppearance(
    val theme: AppThemeMode = AppThemeMode.DARK,
    val surfaceOpacity: Float = 0.86f,
    val borderOpacity: Float = 0.35f,
    val cornerRadius: Float = 14f,
    val shadowElevation: Float = 2f,
    val animationsEnabled: Boolean = true,
    val compactMode: Boolean = false,
)

object AppAppearancePrefs {
    private const val NAME = "lsfg_appearance"
    private const val THEME = "theme"
    private const val SURFACE_OPACITY = "surface_opacity"
    private const val BORDER_OPACITY = "border_opacity"
    private const val CORNER_RADIUS = "corner_radius"
    private const val SHADOW = "shadow"
    private const val ANIMATIONS = "animations"
    private const val COMPACT = "compact"

    fun get(ctx: Context): AppAppearance {
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return AppAppearance(
            theme = AppThemeMode.fromValue(p.getString(THEME, null)),
            surfaceOpacity = p.getFloat(SURFACE_OPACITY, 0.86f).coerceIn(0.45f, 1f),
            borderOpacity = p.getFloat(BORDER_OPACITY, 0.35f).coerceIn(0f, 1f),
            cornerRadius = p.getFloat(CORNER_RADIUS, 14f).coerceIn(4f, 32f),
            shadowElevation = p.getFloat(SHADOW, 2f).coerceIn(0f, 16f),
            animationsEnabled = p.getBoolean(ANIMATIONS, true),
            compactMode = p.getBoolean(COMPACT, false),
        )
    }

    fun set(ctx: Context, value: AppAppearance) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(THEME, value.theme.value)
            .putFloat(SURFACE_OPACITY, value.surfaceOpacity)
            .putFloat(BORDER_OPACITY, value.borderOpacity)
            .putFloat(CORNER_RADIUS, value.cornerRadius)
            .putFloat(SHADOW, value.shadowElevation)
            .putBoolean(ANIMATIONS, value.animationsEnabled)
            .putBoolean(COMPACT, value.compactMode)
            .apply()
    }
}
