package dev.okhsunrog.vpnhide.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import dev.okhsunrog.vpnhide.settings.AppSettings
import dev.okhsunrog.vpnhide.settings.LocalSettingsState
import dev.okhsunrog.vpnhide.settings.ThemeMode

/**
 * Root theme for the picker app.
 *
 * Reads the live [AppSettings] from [LocalSettingsState] and builds an
 * expressive Material 3 theme: wallpaper-derived Material You on Android 12+
 * (or a material-kolor scheme generated from the brand seed otherwise), with
 * AMOLED, contrast and motion honoring the user's preferences.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VpnHideTheme(content: @Composable () -> Unit) {
    val settings = LocalSettingsState.current
    val dark = settings.themeMode.isDark()

    val colorScheme = rememberAppColorScheme(settings, dark)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = AppMotionScheme,
        shapes = appShapes(settings.cornerStyle),
        content = content,
    )
}

@Composable
private fun ThemeMode.isDark(): Boolean =
    when (this) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

@Composable
private fun rememberAppColorScheme(
    settings: AppSettings,
    dark: Boolean,
): ColorScheme {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base =
        if (settings.dynamicColor && supportsDynamic) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (settings.dynamicColor) {
            // Pre-12 fallback when "Material You" is on but unavailable.
            if (dark) darkColorScheme() else lightColorScheme()
        } else {
            rememberDynamicColorScheme(
                seedColor = Color(settings.seedColor),
                isDark = dark,
                isAmoled = settings.amoled,
                style = PaletteStyle.TonalSpot,
                contrastLevel = settings.contrast.toDouble(),
            )
        }

    // The material-kolor path already bakes in AMOLED; the system/dynamic path
    // does not, so apply it here for dark mode.
    return if (settings.amoled && dark && (settings.dynamicColor)) base.toAmoled() else base
}
