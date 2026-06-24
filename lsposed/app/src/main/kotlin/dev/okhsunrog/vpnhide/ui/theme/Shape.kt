package dev.okhsunrog.vpnhide.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.CornerStyle

/**
 * The app's Material 3 shape scale.
 *
 * [CornerStyle.Smooth] currently resolves to the same rounded scale as
 * [CornerStyle.Rounded]; the continuous ("squircle") corner shape lands with
 * the design-system port (it reuses ImageToolbox's `AutoCornersShape`, Apache-2.0,
 * with attribution). The knob is wired now so the switch persists from day one.
 */
fun appShapes(cornerStyle: CornerStyle): Shapes {
    // cornerStyle is intentionally read here so the parameter is part of the
    // public contract before Smooth diverges from Rounded in the next milestone.
    @Suppress("UNUSED_EXPRESSION")
    cornerStyle
    return Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
}
