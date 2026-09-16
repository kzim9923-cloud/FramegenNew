package com.firstt175.deepdrop.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

fun LsfgShapes(radius: Float = 14f) = Shapes(
    extraSmall = RoundedCornerShape((radius * 0.30f).dp),
    small = RoundedCornerShape((radius * 0.50f).dp),
    medium = RoundedCornerShape(radius.dp),
    large = RoundedCornerShape((radius * 1.15f).dp),
    extraLarge = RoundedCornerShape((radius * 1.35f).dp),
)
