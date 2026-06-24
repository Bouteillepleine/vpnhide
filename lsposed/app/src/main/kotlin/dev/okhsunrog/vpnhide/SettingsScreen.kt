package dev.okhsunrog.vpnhide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.CornerStyle
import dev.okhsunrog.vpnhide.settings.LocalSettingsState
import dev.okhsunrog.vpnhide.settings.SettingsRepository
import dev.okhsunrog.vpnhide.settings.ThemeMode
import dev.okhsunrog.vpnhide.ui.components.PreferenceRow
import dev.okhsunrog.vpnhide.ui.components.PreferenceRowSwitch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings = LocalSettingsState.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_appearance))

            val themeModeLabel =
                when (settings.themeMode) {
                    ThemeMode.System -> stringResource(R.string.theme_mode_system)
                    ThemeMode.Light -> stringResource(R.string.theme_mode_light)
                    ThemeMode.Dark -> stringResource(R.string.theme_mode_dark)
                }
            PreferenceRow(
                title = stringResource(R.string.settings_theme_mode),
                subtitle = themeModeLabel,
                icon = Icons.Default.BrightnessMedium,
                onClick = {
                    val next =
                        when (settings.themeMode) {
                            ThemeMode.System -> ThemeMode.Light
                            ThemeMode.Light -> ThemeMode.Dark
                            ThemeMode.Dark -> ThemeMode.System
                        }
                    scope.launch { repo.setThemeMode(next) }
                },
            )
            PreferenceRowSwitch(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_sub),
                icon = Icons.Default.Palette,
                checked = settings.dynamicColor,
                onCheckedChange = { value -> scope.launch { repo.setDynamicColor(value) } },
            )
            PreferenceRowSwitch(
                title = stringResource(R.string.settings_amoled),
                subtitle = stringResource(R.string.settings_amoled_sub),
                icon = Icons.Default.DarkMode,
                checked = settings.amoled,
                onCheckedChange = { value -> scope.launch { repo.setAmoled(value) } },
            )
            PreferenceRowSwitch(
                title = stringResource(R.string.settings_squircle),
                subtitle = stringResource(R.string.settings_squircle_sub),
                icon = Icons.Default.RoundedCorner,
                checked = settings.cornerStyle == CornerStyle.Smooth,
                onCheckedChange = { value ->
                    scope.launch {
                        repo.setCornerStyle(if (value) CornerStyle.Smooth else CornerStyle.Rounded)
                    }
                },
            )

            SettingsSectionHeader(stringResource(R.string.settings_interaction))
            PreferenceRowSwitch(
                title = stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_sub),
                icon = Icons.Default.Vibration,
                checked = settings.hapticsEnabled,
                onCheckedChange = { value -> scope.launch { repo.setHapticsEnabled(value) } },
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}
