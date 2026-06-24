package dev.okhsunrog.vpnhide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.LocalSettingsState

/*
 * Shared visual + interaction modifiers for the design system.
 *
 * Adapted from ImageToolbox's core/ui/widget/modifier (Apache-2.0, © T8RIN):
 * a single container look (soft shadow + shape + subtle border) applied
 * everywhere for consistency, plus haptic-aware click handling.
 */

/**
 * Unified surface styling: a soft elevation shadow, a filled background and an
 * optional hairline border, all clipped to [shape]. Use on every card/row so
 * surfaces read as one coherent system.
 */
@Composable
fun Modifier.container(
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    drawBorder: Boolean = true,
    shadowElevation: Dp = 1.dp,
): Modifier =
    this
        .shadow(elevation = shadowElevation, shape = shape, clip = false)
        // clip before background so a following clickable's ripple is shape-clipped
        .clip(shape)
        .background(color = color)
        .then(if (drawBorder) Modifier.border(width = 1.dp, color = borderColor, shape = shape) else Modifier)

/**
 * Clickable that fires a light haptic tick before [onClick] when haptics are
 * enabled in settings. The ripple/indication comes from the standard
 * [clickable].
 */
@Composable
fun Modifier.hapticsClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalSettingsState.current.hapticsEnabled
    return this.clickable(enabled = enabled) {
        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Tracks the pressed state of [interactionSource] (for press-scale effects). */
@Composable
fun rememberIsPressed(interactionSource: MutableInteractionSource): Boolean {
    val pressed by interactionSource.collectIsPressedAsState()
    return pressed
}

/** A medium rounded shape constant for ad-hoc use where the theme scale doesn't fit. */
val DefaultRowShape: Shape = RoundedCornerShape(20.dp)
