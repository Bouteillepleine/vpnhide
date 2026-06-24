package dev.okhsunrog.vpnhide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Force pure-black backgrounds/surfaces for OLED panels while keeping the
 * scheme's accent roles intact. Applied on top of the (system or generated)
 * dark scheme when the user enables AMOLED mode.
 */
fun ColorScheme.toAmoled(): ColorScheme {
    fun darken(
        color: Color,
        fraction: Float,
    ): Color =
        Color(
            red = color.red * (1 - fraction),
            green = color.green * (1 - fraction),
            blue = color.blue * (1 - fraction),
            alpha = color.alpha,
        )
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = darken(surfaceBright, 0.6f),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = darken(surfaceContainerLow, 0.55f),
        surfaceContainer = darken(surfaceContainer, 0.5f),
        surfaceContainerHigh = darken(surfaceContainerHigh, 0.45f),
        surfaceContainerHighest = darken(surfaceContainerHighest, 0.4f),
    )
}
