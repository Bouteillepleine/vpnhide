package dev.okhsunrog.vpnhide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.CornerStyle
import dev.okhsunrog.vpnhide.settings.LocalSettingsInteractor
import dev.okhsunrog.vpnhide.settings.LocalSettingsState
import dev.okhsunrog.vpnhide.settings.ThemeMode
import dev.okhsunrog.vpnhide.ui.components.EnhancedButton
import dev.okhsunrog.vpnhide.ui.components.PreferenceRow
import dev.okhsunrog.vpnhide.ui.components.PreferenceRowSwitch
import dev.okhsunrog.vpnhide.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = LocalSettingsState.current
    val interactor = LocalSettingsInteractor.current

    Scaffold(
        containerColor = AppColors.screenBackground,
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
                        containerColor = AppColors.topBarContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Appearance ── one grouped block of five rows.
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                    index = 0,
                    count = 5,
                    onClick = {
                        val next =
                            when (settings.themeMode) {
                                ThemeMode.System -> ThemeMode.Light
                                ThemeMode.Light -> ThemeMode.Dark
                                ThemeMode.Dark -> ThemeMode.System
                            }
                        interactor.setThemeMode(next)
                    },
                )
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_sub),
                    icon = Icons.Default.Palette,
                    index = 1,
                    count = 5,
                    checked = settings.dynamicColor,
                    onCheckedChange = interactor::setDynamicColor,
                )
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_amoled),
                    subtitle = stringResource(R.string.settings_amoled_sub),
                    icon = Icons.Default.DarkMode,
                    index = 2,
                    count = 5,
                    checked = settings.amoled,
                    onCheckedChange = interactor::setAmoled,
                )
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_squircle),
                    subtitle = stringResource(R.string.settings_squircle_sub),
                    icon = Icons.Default.RoundedCorner,
                    index = 3,
                    count = 5,
                    checked = settings.cornerStyle == CornerStyle.Smooth,
                    onCheckedChange = { value ->
                        interactor.setCornerStyle(if (value) CornerStyle.Smooth else CornerStyle.Rounded)
                    },
                )
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_shadows),
                    subtitle = stringResource(R.string.settings_shadows_sub),
                    icon = Icons.Default.Layers,
                    index = 4,
                    count = 5,
                    checked = settings.drawContainerShadows,
                    onCheckedChange = interactor::setDrawContainerShadows,
                )
            }

            // ── Interaction ── grouped block of two rows.
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SettingsSectionHeader(stringResource(R.string.settings_interaction))
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_haptics),
                    subtitle = stringResource(R.string.settings_haptics_sub),
                    icon = Icons.Default.Vibration,
                    index = 0,
                    count = 2,
                    checked = settings.hapticsEnabled,
                    onCheckedChange = interactor::setHapticsEnabled,
                )
                PreferenceRowSwitch(
                    title = stringResource(R.string.settings_animations),
                    subtitle = stringResource(R.string.settings_animations_sub),
                    icon = Icons.Default.Animation,
                    index = 1,
                    count = 2,
                    checked = settings.animationsEnabled,
                    onCheckedChange = interactor::setAnimationsEnabled,
                )
            }

            SuperkeySettingsSection()
        }
    }
}

@Composable
private fun SuperkeySettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by TargetsCache.snapshot.collectAsState()
    var superkey by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val remembered = targets?.apatchSuperkeySaved == true
    val storedMessage = stringResource(R.string.settings_superkey_stored)
    val clearedMessage = stringResource(R.string.settings_superkey_cleared)
    val failedMessage = stringResource(R.string.settings_superkey_failed)

    LaunchedEffect(Unit) {
        TargetsCache.ensureLoaded(scope, context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_security))
        PreferenceRow(
            title = stringResource(R.string.settings_superkey_title),
            subtitle =
                stringResource(
                    if (remembered) {
                        R.string.settings_superkey_saved
                    } else {
                        R.string.settings_superkey_not_saved
                    },
                ),
            icon = Icons.Default.Lock,
        )
        OutlinedTextField(
            value = superkey,
            onValueChange = {
                superkey = it
                status = null
            },
            label = { Text(stringResource(R.string.settings_superkey_placeholder)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    saving = true
                    status = null
                    scope.launch {
                        val exit = withContext(Dispatchers.IO) { writeSuperkeySetting(context, remember = false, superkey = "") }
                        saving = false
                        status = if (exit == 0) clearedMessage else failedMessage
                        if (exit == 0) {
                            superkey = ""
                            TargetsCache.refresh(scope, context)
                        }
                    }
                },
                enabled = !saving && targets != null,
            ) {
                Text(stringResource(R.string.settings_superkey_clear))
            }
            Spacer(Modifier.width(8.dp))
            EnhancedButton(
                onClick = {
                    saving = true
                    status = null
                    val keyToWrite = superkey
                    scope.launch {
                        val exit = withContext(Dispatchers.IO) { writeSuperkeySetting(context, remember = true, superkey = keyToWrite) }
                        saving = false
                        status = if (exit == 0) storedMessage else failedMessage
                        if (exit == 0) {
                            superkey = ""
                            TargetsCache.refresh(scope, context)
                        }
                    }
                },
                enabled = !saving && superkey.isNotBlank() && targets != null,
                modifier = Modifier.weight(1f),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(stringResource(R.string.settings_superkey_store))
            }
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun writeSuperkeySetting(
    context: android.content.Context,
    remember: Boolean,
    superkey: String,
): Int {
    val snapshot = TargetsCache.snapshot.value
    val base =
        snapshot?.let(::buildCanonicalConfigFromTargetsSnapshot)
            ?: CanonicalConfig(debug = isEnabledInPrefs(context))
    val canonical = base.copy(settings = base.settings.copy(rememberSuperkey = remember))
    val requiredParts =
        listOf(
            buildCanonicalConfigWriteCommand(canonical),
            if (remember) buildSuperkeyWriteCommand(superkey) else buildSuperkeyClearCommand(),
        )
    val cmd =
        if (remember) {
            requiredParts.joinToString(" && ") + " && " + ConfigChannels.reconcileCommand()
        } else {
            requiredParts.joinToString(" && ")
        }
    val (exit, _) = suExec(cmd)
    if (exit == 0) {
        RootSnapshotCache.invalidate()
        DashboardCache.invalidate()
    }
    return exit
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}
