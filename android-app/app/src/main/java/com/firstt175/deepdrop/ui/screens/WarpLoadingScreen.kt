package com.firstt175.deepdrop.ui.screens

import com.firstt175.deepdrop.ui.Routes

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.LoadingScreenPrefs
import com.firstt175.deepdrop.prefs.LoadingStyle
import com.firstt175.deepdrop.ui.theme.LsfgGlow
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgWarpVoid
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Launch screen: a brief animated gate shown before [Routes.HOME], while
 * startup state (appearance prefs, display profile, asset scan, etc.)
 * settles. Purely a pass-through — nothing to configure here beyond the
 * on/off switch and the chosen [LoadingStyle], both in Appearance
 * ([LoadingScreenPrefs]). When disabled, this composable skips straight to
 * Home without drawing a frame.
 *
 * The chosen style ([LoadingOverlay]) is also reused by [GameLauncherScreen]
 * as a full-screen overlay right before a selected game's launch intent
 * fires, so the same effect appears both when entering Deepdrop itself and
 * when entering a launched game.
 */
const val WARP_DURATION_MS = 1900L
private const val STAR_COUNT = 140

private data class WarpStar(val angle: Float, val startRadius: Float, val speed: Float)

@Composable
fun WarpLoadingScreen(nav: NavHostController) {
    val context = LocalContext.current
    val enabled = remember { LoadingScreenPrefs.isEnabled(context) }
    val style = remember { LoadingScreenPrefs.getStyle(context) }

    if (!enabled) {
        LaunchedEffect(Unit) {
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.LOADING) { inclusive = true }
            }
        }
        return
    }

    LaunchedEffect(Unit) {
        delay(WARP_DURATION_MS)
        nav.navigate(Routes.HOME) {
            popUpTo(Routes.LOADING) { inclusive = true }
        }
    }

    LoadingOverlay(style = style)
}

/**
 * Stateless warp-speed visual: deep-space backdrop, an outward-streaking
 * starfield and a pulsing app icon at the core. Callers own the timing
 * (how long it's shown / what happens after) — this composable only draws.
 */
@Composable
fun WarpOverlay(modifier: Modifier = Modifier, title: String = stringResource(R.string.warp_loading_title)) {
    val stars = remember {
        List(STAR_COUNT) {
            WarpStar(
                angle = Random.nextFloat() * 360f,
                startRadius = Random.nextFloat() * 40f,
                speed = 0.55f + Random.nextFloat() * 1.2f,
            )
        }
    }

    val infinite = rememberInfiniteTransition(label = "warp")
    // Drives the streak length / outward travel — loops so the field keeps
    // rushing past even if a frame or two is skipped near navigation time.
    val warpPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "warpPhase",
    )
    // Icon "engine core" pulse.
    val iconScale by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "iconScale",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LsfgWarpVoid),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxReach = (size.width.coerceAtLeast(size.height)) * 0.75f
            stars.forEach { star ->
                val rad = Math.toRadians(star.angle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                // Radius grows with both the star's own speed and the looping
                // warpPhase, then wraps — this is what reads as continuous
                // acceleration rather than a single one-shot burst.
                val travel = ((warpPhase * star.speed) % 1f)
                val headRadius = star.startRadius + travel * maxReach
                val tailLength = 24f + travel * 130f * star.speed
                val tailRadius = (headRadius - tailLength).coerceAtLeast(0f)
                val alpha = (travel).coerceIn(0f, 1f) * (1f - travel * 0.15f)
                drawLine(
                    color = if (star.speed > 1.3f) LsfgGlow.copy(alpha = alpha) else Color.White.copy(alpha = alpha * 0.85f),
                    start = Offset(center.x + dx * tailRadius, center.y + dy * tailRadius),
                    end = Offset(center.x + dx * headRadius, center.y + dy * headRadius),
                    strokeWidth = 1.6f + travel * 1.8f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Soft glow ring behind the app icon.
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(iconScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(LsfgPrimary.copy(alpha = 0.35f), Color.Transparent),
                            ),
                        ),
                )
                Image(
                    painter = painterResource(id = R.drawable.lsfg_app_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .scale(iconScale)
                        .clip(CircleShape),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}
