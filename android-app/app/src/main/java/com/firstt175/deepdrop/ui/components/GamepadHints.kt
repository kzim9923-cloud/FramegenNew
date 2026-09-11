package com.firstt175.deepdrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstt175.deepdrop.session.GamepadInputManager
import com.firstt175.deepdrop.ui.theme.LsfgMonoFontFamily
import com.firstt175.deepdrop.ui.theme.LsfgOnPrimary
import com.firstt175.deepdrop.ui.theme.LsfgOnSurfaceVariant
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgSurfaceContainerHigh

/**
 * One controller button paired with what it does on the screen showing it,
 * e.g. `GamepadHint("B", "Back")` or `GamepadHint("L1", "Switch tab")`.
 */
data class GamepadHint(val glyph: String, val label: String)

/**
 * True whenever [GamepadInputManager] currently has a controller connected.
 * Screens use this to conditionally show gamepad-only UI (hint bars, focus
 * outlines, etc.) instead of cluttering the touch-only experience.
 */
@Composable
fun rememberGamepadConnected(): Boolean {
    val connected by GamepadInputManager.connected.collectAsState()
    return connected != null
}

/** Small pill showing a single button glyph, e.g. "B", "A", "L1", "R1". */
@Composable
fun GamepadButtonGlyph(glyph: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LsfgPrimary)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = LsfgOnPrimary,
            fontFamily = LsfgMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

/** Row of [GamepadButtonGlyph] + label pairs on a rounded pill background. */
@Composable
fun GamepadHintRow(hints: List<GamepadHint>, modifier: Modifier = Modifier) {
    if (hints.isEmpty()) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(LsfgSurfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GamepadButtonGlyph(hint.glyph)
                Text(
                    text = hint.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = LsfgOnSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Docks a [GamepadHintRow] to the bottom-center of the screen, sliding in the
 * moment [GamepadInputManager] detects a controller and back out when it's
 * unplugged — this is the whole "when it detects a gamepad, something shows
 * up telling you what the buttons do" affordance. Drop one call of this
 * inside a screen's outer `Box(Modifier.fillMaxSize())`; it positions and
 * animates itself, so callers only need to supply the hints relevant to that
 * screen.
 */
@Composable
fun BoxScope.GamepadHintOverlay(hints: List<GamepadHint>, modifier: Modifier = Modifier) {
    val gamepadConnected = rememberGamepadConnected()
    if (gamepadConnected) {
        GamepadHintRow(
            hints = hints,
            modifier = modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        )
    }
}
