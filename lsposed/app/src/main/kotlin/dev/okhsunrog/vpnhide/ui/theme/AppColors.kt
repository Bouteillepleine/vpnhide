package dev.okhsunrog.vpnhide.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * App-level color roles layered on top of Material 3's generated ColorScheme.
 *
 * Dynamic Color can map `surfaceContainerLowest` to pure black in dark mode, so
 * large app surfaces use lifted roles here while still following the user's
 * wallpaper palette.
 */
object AppColors {
    val screenBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainer

    val topBarContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainer

    val topBarScrolledContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

    val navigationBarContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainer

    val cardContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

    val cardContainerStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHighest

    val toolbarActionContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHighest

    val neutralAccentContainer: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHighest
}
