package dev.okhsunrog.vpnhide.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.CornerStyle
import sv.lib.squircleshape.CornerSmoothing
import sv.lib.squircleshape.SquircleShape

/**
 * The app's Material 3 shape scale.
 *
 * [CornerStyle.Smooth] uses iOS-style continuous ("squircle") corners
 * (squircle-shape lib); [CornerStyle.Rounded] uses plain circular-arc corners.
 */
fun appShapes(cornerStyle: CornerStyle): Shapes {
    fun corner(size: Dp): CornerBasedShape =
        when (cornerStyle) {
            CornerStyle.Rounded -> RoundedCornerShape(size)
            CornerStyle.Smooth -> SquircleShape(size, CornerSmoothing.Medium)
        }
    return Shapes(
        extraSmall = corner(6.dp),
        small = corner(10.dp),
        medium = corner(16.dp),
        large = corner(22.dp),
        extraLarge = corner(32.dp),
    )
}
