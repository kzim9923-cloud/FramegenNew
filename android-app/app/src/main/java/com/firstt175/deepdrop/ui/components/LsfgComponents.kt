package com.firstt175.deepdrop.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.AppAppearancePrefs
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgStatusBad
import com.firstt175.deepdrop.ui.theme.LsfgStatusGood
import com.firstt175.deepdrop.ui.theme.LsfgStatusWarn

/**
 * A card with subtle surface elevation used as the base container throughout the UI.
 * Accented cards get a thin top "readout" strip in the accent color — a small
 * retro-console cue that doesn't get in the way of reading the content.
 */
@Composable
fun LsfgCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    accent: Boolean = false,
    accentColor: Color = LsfgPrimary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val appearance = AppAppearancePrefs.get(androidx.compose.ui.platform.LocalContext.current)
    val shape = MaterialTheme.shapes.medium
    val container = MaterialTheme.colorScheme.surfaceContainer
    val borderAlpha = appearance.borderOpacity
    val border = if (accent) {
        BorderStroke(1.dp, SolidColor(accentColor.copy(alpha = (borderAlpha + 0.3f).coerceAtMost(1f))))
    } else {
        BorderStroke(1.dp, SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)))
    }
    val cardModifier = if (appearance.animationsEnabled) modifier.animateContentSize() else modifier
    val effectivePadding = if (appearance.compactMode) {
        PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    } else contentPadding
    val cardContent: @Composable ColumnScope.() -> Unit = {
        if (accent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accentColor.copy(alpha = 0.85f)),
            )
        }
        Column(
            Modifier.padding(effectivePadding),
            content = content,
        )
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = appearance.shadowElevation.dp),
            content = cardContent,
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = appearance.shadowElevation.dp),
            content = cardContent,
        )
    }
}

/**
 * Section header with uppercase eyebrow and title below.
 */
@Composable
fun SectionHeader(
    eyebrow: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LsfgPrimary,
        )
        if (title != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

enum class StatusTone { Good, Warn, Bad, Neutral }

@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color = when (tone) {
        StatusTone.Good -> LsfgStatusGood
        StatusTone.Warn -> LsfgStatusWarn
        StatusTone.Bad -> LsfgStatusBad
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}



@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color = LsfgPrimary,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.50f),
        )
    }
}

/**
 * Row with leading icon, title, description, and trailing Material3 Switch.
 */
@Composable
fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        IconBadge(icon = icon, size = 28.dp)
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = LsfgPrimary,
                checkedBorderColor = LsfgPrimary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * Slider row with title, value chip, description, and Material3 slider.
 * Wraps androidx.compose.material3.Slider.
 */
@Composable
fun ValueSlider(
    title: String,
    valueDisplay: String,
    description: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                IconBadge(icon = leadingIcon, size = 32.dp)
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LsfgPrimary.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = valueDisplay,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = LsfgPrimary,
                )
            }
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = LsfgPrimary,
                activeTrackColor = LsfgPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A card that starts collapsed and expands on tap. Use this for advanced /
 * diagnostic / rarely-needed content (raw device info, low-level tuning)
 * so a first-time user sees a short, calm screen by default instead of
 * every card at once — the detail is one tap away, not gone.
 *
 * [subtitle] stays visible even while collapsed so the user knows roughly
 * what's inside before deciding to open it (e.g. a one-line status summary).
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    startExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(startExpanded) }

    // Only the header row is clickable — not the whole card. Wiring onClick
    // onto LsfgCard itself would make every tap inside the expanded content
    // (e.g. tapping a line of body text between two sliders) also toggle
    // the section shut, which fights the user while they're reading it.
    LsfgCard(modifier = modifier) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(content = content)
            }
        }
    }
}

/**
 * Filled "hero" button using a two-tone orange gradient — the redesign's
 * primary call-to-action style (replaces flat Material filled buttons for
 * the app's main actions: Continue, Apply, Start).
 */
@Composable
fun LsfgPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
        colors = if (enabled) {
            listOf(com.firstt175.deepdrop.ui.theme.LsfgGlow, com.firstt175.deepdrop.ui.theme.LsfgGradientEnd)
        } else {
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        },
    )
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(gradient)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = LsfgOnPrimaryColorForGradient(enabled),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = LsfgOnPrimaryColorForGradient(enabled),
            )
        }
    }
}

@Composable
private fun LsfgOnPrimaryColorForGradient(enabled: Boolean): Color =
    if (enabled) Color(0xFF1A0F00) else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Outlined "back" / secondary button with small icon slot.
 */
@Composable
fun LsfgSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * App top bar with optional back + trailing icon slot.
 */
@Composable
fun LsfgTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(4.dp))
        } else {
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/**
 * Brand logo mark — renders the app icon inside a rounded tile.
 */
@Composable
fun LsfgLogoMark(size: androidx.compose.ui.unit.Dp = 28.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(
            id = com.firstt175.deepdrop.R.drawable.lsfg_app_icon,
        ),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp)),
    )
}
