package com.firstt175.deepdrop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LsfgFontFamily = FontFamily.SansSerif

// Used for short technical readouts — eyebrows, status pills, numeric chips,
// version tags, step numbers. Monospace + wide tracking is where the "retro
// terminal" identity actually lives; everything else stays plain sans-serif
// so long-form text is never harder to read.
val LsfgMonoFontFamily = FontFamily.Monospace

val LsfgTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 33.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = LsfgMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = LsfgFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = LsfgMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    ),
)
