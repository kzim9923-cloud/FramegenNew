package com.firstt175.deepdrop.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.LoadingStyle
import com.firstt175.deepdrop.ui.theme.LsfgGlow
import com.firstt175.deepdrop.ui.theme.LsfgGradientEnd
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgWarpVoid
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single entry point that renders whichever [LoadingStyle] the user picked
 * in Appearance ([com.firstt175.deepdrop.prefs.LoadingScreenPrefs]). Used
 * both by [WarpLoadingScreen] (app startup gate) and by
 * `GameLauncherScreen` (pre-launch overlay for a tapped game), so the two
 * spots stay in sync automatically whenever the style preference changes.
 */
@Composable
fun LoadingOverlay(
    style: LoadingStyle,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.warp_loading_title),
) {
    when (style) {
        LoadingStyle.WARP -> WarpOverlay(modifier, title)
        LoadingStyle.PULSE -> PulseOverlay(modifier, title)
        LoadingStyle.ORBIT -> OrbitOverlay(modifier, title)
        LoadingStyle.WAVE -> WaveOverlay(modifier, title)
        LoadingStyle.MINIMAL -> MinimalOverlay(modifier, title)
    }
}

/** Small helper: the void-black backdrop + centered column shared by every style. */
@Composable
private fun LoadingScaffold(
    modifier: Modifier = Modifier,
    background: Color = LsfgWarpVoid,
    behindIcon: @Composable () -> Unit = {},
    icon: @Composable () -> Unit,
    title: String,
) {
    Box(
        modifier = modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center,
    ) {
        behindIcon()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun AppIcon(scale: Float, glowAlpha: Float = 0.35f) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(LsfgPrimary.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                ),
        )
        Image(
            painter = painterResource(id = R.drawable.lsfg_app_icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .clip(CircleShape),
        )
    }
}

/**
 * Radar-style pulse: three expanding, fading rings breathe outward from
 * behind the icon in a staggered loop, evoking a signal/ping rather than
 * the Warp style's rushing starfield.
 */
@Composable
fun PulseOverlay(modifier: Modifier = Modifier, title: String = stringResource(R.string.warp_loading_title)) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "pulsePhase",
    )
    val iconScale by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseIconScale",
    )

    LoadingScaffold(
        modifier = modifier,
        title = title,
        behindIcon = {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = (size.width.coerceAtLeast(size.height)) * 0.32f
                repeat(3) { ring ->
                    val ringPhase = (phase + ring / 3f) % 1f
                    val radius = 40f + ringPhase * maxRadius
                    val alpha = (1f - ringPhase).coerceIn(0f, 1f) * 0.55f
                    drawCircle(
                        color = LsfgGlow.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3f + ringPhase * 2f),
                    )
                }
            }
        },
        icon = { AppIcon(scale = iconScale, glowAlpha = 0.4f) },
    )
}

/**
 * Small glowing satellites orbiting steadily around the app icon — a calm,
 * mechanical "system booting" feel rather than a burst of motion.
 */
@Composable
fun OrbitOverlay(modifier: Modifier = Modifier, title: String = stringResource(R.string.warp_loading_title)) {
    val infinite = rememberInfiniteTransition(label = "orbit")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "orbitRotation",
    )
    val iconScale by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "orbitIconScale",
    )
    val dotColors = listOf(LsfgPrimary, LsfgGlow, LsfgGradientEnd, Color.White.copy(alpha = 0.85f))

    LoadingScaffold(
        modifier = modifier,
        title = title,
        behindIcon = {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val orbitRadius = 100f
                dotColors.forEachIndexed { index, color ->
                    val angle = Math.toRadians((rotation + index * (360f / dotColors.size)).toDouble())
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    drawCircle(
                        color = color,
                        radius = 7f,
                        center = Offset(center.x + dx * orbitRadius, center.y + dy * orbitRadius),
                    )
                }
            }
        },
        icon = { AppIcon(scale = iconScale, glowAlpha = 0.3f) },
    )
}

/**
 * A row of bars beneath the icon rising and falling like a compact
 * audio/frame-rate equalizer, tying the loading moment back to Deepdrop's
 * frame-generation theme without repeating the Warp starfield.
 */
@Composable
fun WaveOverlay(modifier: Modifier = Modifier, title: String = stringResource(R.string.warp_loading_title)) {
    val barCount = 5
    val infinite = rememberInfiniteTransition(label = "wave")
    val iconScale by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "waveIconScale",
    )
    val barPhases = (0 until barCount).map { index ->
        val phase by infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500 + index * 90, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waveBar$index",
        )
        phase
    }

    LoadingScaffold(
        modifier = modifier,
        title = title,
        icon = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(scale = iconScale, glowAlpha = 0.35f)
                Spacer(Modifier.height(18.dp))
                Canvas(modifier = Modifier.size(width = 96.dp, height = 36.dp)) {
                    val barWidth = size.width / (barCount * 2f - 1f)
                    barPhases.forEachIndexed { index, phase ->
                        val barHeight = size.height * phase
                        drawLineBar(
                            x = index * barWidth * 2f + barWidth / 2f,
                            barWidth = barWidth,
                            barHeight = barHeight,
                            totalHeight = size.height,
                            color = if (index % 2 == 0) LsfgPrimary else LsfgGlow,
                        )
                    }
                }
            }
        },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineBar(
    x: Float,
    barWidth: Float,
    barHeight: Float,
    totalHeight: Float,
    color: Color,
) {
    drawLine(
        color = color,
        start = Offset(x, totalHeight),
        end = Offset(x, totalHeight - barHeight),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
    )
}

/**
 * The plainest option: the icon fading gently with a slim circular progress
 * ring, no particle effects — for anyone who'd rather the startup gate stay
 * out of the way.
 */
@Composable
fun MinimalOverlay(modifier: Modifier = Modifier, title: String = stringResource(R.string.warp_loading_title)) {
    val infinite = rememberInfiniteTransition(label = "minimal")
    val iconAlpha by infinite.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "minimalIconAlpha",
    )

    LoadingScaffold(
        modifier = modifier,
        title = title,
        icon = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(96.dp),
                    color = LsfgPrimary,
                    strokeWidth = 2.5.dp,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
                Image(
                    painter = painterResource(id = R.drawable.lsfg_app_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent),
                    alpha = iconAlpha,
                )
            }
        },
    )
}
