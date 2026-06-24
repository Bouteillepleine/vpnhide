package dev.okhsunrog.vpnhide.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.CornerStyle
import dev.okhsunrog.vpnhide.settings.LocalSettingsState
import sv.lib.squircleshape.CornerSmoothing
import sv.lib.squircleshape.SquircleShape

/*
 * Grouped-shape system — the signature ImageToolbox look (adapted from
 * ShapeDefaults, Apache-2.0, © T8RIN). Items in a vertical group share big
 * outer corners (the first item rounds the top, the last rounds the bottom)
 * and small inner corners, so a stack of cards reads as one unit. Corners
 * morph smoothly when group membership or press state changes, and every shape
 * honors the user's corner style (plain rounded vs iOS-style squircle).
 */

/** The four corner radii of a [CornerBasedShape]. */
@Immutable
data class CornerRadii(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp,
) {
    companion object {
        fun all(size: Dp) = CornerRadii(size, size, size, size)
    }
}

/** Outer (exposed) corner radius of a group. */
val GroupBigCorner: Dp = 22.dp

/** Inner corner radius between adjacent items in a group. */
val GroupSmallCorner: Dp = 6.dp

/** Corner radius a surface morphs toward while pressed (a subtle "squish"). */
val PressedCorner: Dp = 12.dp

/** Build the right [CornerBasedShape] for [cornerStyle] from these radii. */
fun CornerRadii.toShape(cornerStyle: CornerStyle): CornerBasedShape =
    when (cornerStyle) {
        // Compose's RoundedCornerShape orders corners ts, te, be, bs.
        CornerStyle.Rounded -> {
            RoundedCornerShape(
                topStart = topStart,
                topEnd = topEnd,
                bottomEnd = bottomEnd,
                bottomStart = bottomStart,
            )
        }

        // squircle-shape orders corners ts, te, bs, be, smoothing.
        CornerStyle.Smooth -> {
            SquircleShape(topStart, topEnd, bottomStart, bottomEnd, CornerSmoothing.Medium)
        }
    }

/**
 * Target corner radii for the item at [index] in a vertical group of [count].
 * Single item → all big; first → big top, small bottom; last → small top, big
 * bottom; middle → all small.
 */
fun groupCornerRadii(
    index: Int,
    count: Int,
): CornerRadii {
    if (count <= 1) return CornerRadii.all(GroupBigCorner)
    val big = GroupBigCorner
    val small = GroupSmallCorner
    return when (index) {
        0 -> CornerRadii(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
        count - 1 -> CornerRadii(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
        else -> CornerRadii.all(small)
    }
}

/**
 * Grouped shape for the item at [index] of [count]. When [animated], the four
 * corners morph via [animateDpAsState] as membership changes — e.g. when a card
 * appears/disappears and a neighbor becomes the new edge of the group.
 */
@Composable
fun groupedShape(
    index: Int,
    count: Int,
    animated: Boolean = LocalSettingsState.current.animationsEnabled,
): Shape {
    val cornerStyle = LocalSettingsState.current.cornerStyle
    val target = groupCornerRadii(index, count)
    if (!animated) return target.toShape(cornerStyle)
    val ts by animateDpAsState(target.topStart, AppMotion.defaultSpatial(), label = "group-ts")
    val te by animateDpAsState(target.topEnd, AppMotion.defaultSpatial(), label = "group-te")
    val bs by animateDpAsState(target.bottomStart, AppMotion.defaultSpatial(), label = "group-bs")
    val be by animateDpAsState(target.bottomEnd, AppMotion.defaultSpatial(), label = "group-be")
    return CornerRadii(ts, te, bs, be).toShape(cornerStyle)
}

/**
 * A press-reactive shape: [base] normally, morphing every corner toward
 * [pressedRadius] while [interactionSource] reports a press. Gated by the
 * animations setting; returns [base] verbatim when off. The signature
 * "squish on tap".
 */
@Composable
fun shapeByInteraction(
    base: CornerRadii,
    interactionSource: InteractionSource,
    pressedRadius: Dp = PressedCorner,
    enabled: Boolean = LocalSettingsState.current.animationsEnabled,
): Shape {
    val cornerStyle = LocalSettingsState.current.cornerStyle
    if (!enabled) return base.toShape(cornerStyle)
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed) CornerRadii.all(pressedRadius) else base
    val ts by animateDpAsState(target.topStart, AppMotion.fastSpatial(), label = "press-ts")
    val te by animateDpAsState(target.topEnd, AppMotion.fastSpatial(), label = "press-te")
    val bs by animateDpAsState(target.bottomStart, AppMotion.fastSpatial(), label = "press-bs")
    val be by animateDpAsState(target.bottomEnd, AppMotion.fastSpatial(), label = "press-be")
    return CornerRadii(ts, te, bs, be).toShape(cornerStyle)
}
