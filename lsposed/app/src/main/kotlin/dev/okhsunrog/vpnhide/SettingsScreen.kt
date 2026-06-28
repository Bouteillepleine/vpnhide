package dev.okhsunrog.vpnhide

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
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
import dev.okhsunrog.vpnhide.ui.components.EnhancedOutlinedButton
import dev.okhsunrog.vpnhide.ui.components.PreferenceRow
import dev.okhsunrog.vpnhide.ui.components.PreferenceRowSwitch
import dev.okhsunrog.vpnhide.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

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

            AutoHideSettingsSection()
            ConfigBackupSection()
            SuperkeySettingsSection()
        }
    }
}

@Composable
private fun ConfigBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by TargetsCache.snapshot.collectAsState()
    var operation by remember { mutableStateOf(ConfigOperation.Idle) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val exportDone = stringResource(R.string.settings_config_export_done)
    val exportFailed = stringResource(R.string.settings_config_export_failed)
    val importDone = stringResource(R.string.settings_config_import_done)
    val importInvalid = stringResource(R.string.settings_config_import_invalid)
    val importRootFailed = stringResource(R.string.settings_config_import_root_failed)

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val raw = pendingExport
            pendingExport = null
            if (uri == null || raw == null) return@rememberLauncherForActivityResult
            operation = ConfigOperation.Export
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeTextToUri(context, uri, raw) }
                operation = ConfigOperation.Idle
                status = if (ok) exportDone else exportFailed
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            operation = ConfigOperation.Import
            status = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { importConfigFromUri(context, uri) }
                operation = ConfigOperation.Idle
                status =
                    when (result) {
                        ConfigImportResult.Success -> {
                            TargetsCache.refreshAfterSave(scope, context)
                            importDone
                        }

                        ConfigImportResult.InvalidJson -> {
                            importInvalid
                        }

                        ConfigImportResult.RootFailed -> {
                            importRootFailed
                        }
                    }
            }
        }

    LaunchedEffect(Unit) {
        TargetsCache.ensureLoaded(scope, context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_config_section))
        PreferenceRow(
            title = stringResource(R.string.settings_config_backup_title),
            subtitle = stringResource(R.string.settings_config_backup_sub),
            icon = Icons.Default.FileDownload,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnhancedOutlinedButton(
                onClick = {
                    pendingExport = buildConfigExportJson(context, targets)
                    status = null
                    exportLauncher.launch("vpnhide_config.json")
                },
                enabled = operation == ConfigOperation.Idle && targets != null,
                modifier = Modifier.weight(1f),
            ) {
                if (operation == ConfigOperation.Export) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_config_export))
            }
            EnhancedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "text/plain",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                enabled = operation == ConfigOperation.Idle,
                modifier = Modifier.weight(1f),
            ) {
                if (operation == ConfigOperation.Import) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_config_import))
            }
        }
        Text(
            text = stringResource(R.string.settings_config_import_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
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

private enum class ConfigImportResult {
    Success,
    InvalidJson,
    RootFailed,
}

private enum class ConfigOperation {
    Idle,
    Export,
    Import,
}

private fun buildConfigExportJson(
    context: android.content.Context,
    snapshot: TargetsSnapshot?,
): String {
    val canonical =
        when {
            snapshot?.canonicalConfig != null -> snapshot.canonicalConfig
            snapshot != null -> buildCanonicalConfigFromTargetsSnapshot(snapshot)
            else -> CanonicalConfig(debug = isEnabledInPrefs(context))
        }
    return canonicalConfigJson(canonical)
}

private fun writeTextToUri(
    context: android.content.Context,
    uri: Uri,
    text: String,
): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
        } ?: error("openOutputStream returned null")
    }.isSuccess

private fun importConfigFromUri(
    context: android.content.Context,
    uri: Uri,
): ConfigImportResult {
    val raw =
        runCatching {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull() ?: return ConfigImportResult.InvalidJson
    val canonical = parseImportedCanonicalConfig(raw, context.packageName) ?: return ConfigImportResult.InvalidJson
    val cmd =
        listOf(
            buildCanonicalConfigWriteCommand(canonical),
            ConfigChannels.reconcileCommand(),
            "if [ -x $PORTS_ACTIVATOR ]; then $PORTS_ACTIVATOR; fi",
        ).joinToString(" ; ")
    val (exit, _) = suExec(cmd)
    if (exit != 0) return ConfigImportResult.RootFailed
    storeDebugLoggingPreference(context, canonical.debug)
    RootSnapshotCache.invalidate()
    DashboardCache.invalidate()
    return ConfigImportResult.Success
}

@Composable
private fun AutoHideSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by TargetsCache.snapshot.collectAsState()
    val apps by AppListCache.apps.collectAsState()
    var saving by remember { mutableStateOf<AutoHideSetting?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val savedMessage = stringResource(R.string.settings_auto_hide_saved)
    val failedMessage = stringResource(R.string.settings_auto_hide_failed)
    val settings = targets?.canonicalConfig?.settings ?: CanonicalSettings()
    val canWrite = targets != null && apps != null && saving == null

    fun updateSetting(
        setting: AutoHideSetting,
        transform: (CanonicalSettings) -> CanonicalSettings,
    ) {
        saving = setting
        status = null
        val appSignals = apps.orEmpty()
        scope.launch {
            val exit = withContext(Dispatchers.IO) { writeAutoHideSetting(context, appSignals, transform) }
            saving = null
            status = if (exit == 0) savedMessage else failedMessage
            if (exit == 0) {
                TargetsCache.refreshAfterSave(scope, context)
            }
        }
    }

    LaunchedEffect(Unit) {
        TargetsCache.ensureLoaded(scope, context)
        AppListCache.ensureLoaded(scope, context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SettingsSectionHeader(stringResource(R.string.settings_advanced_protection))
        PreferenceRowSwitch(
            title = stringResource(R.string.settings_auto_hide_vpn_services),
            subtitle = stringResource(R.string.settings_auto_hide_vpn_services_sub),
            icon = Icons.Default.VpnKey,
            index = 0,
            count = 2,
            checked = settings.autoHideVpnServices,
            enabled = canWrite,
            onCheckedChange = { enabled ->
                updateSetting(AutoHideSetting.VpnService) { it.copy(autoHideVpnServices = enabled) }
            },
        )
        PreferenceRowSwitch(
            title = stringResource(R.string.settings_auto_hide_vpn_name),
            subtitle = stringResource(R.string.settings_auto_hide_vpn_name_sub),
            icon = Icons.Default.TextFields,
            index = 1,
            count = 2,
            checked = settings.autoHideVpnName,
            enabled = canWrite,
            onCheckedChange = { enabled ->
                updateSetting(AutoHideSetting.VpnName) { it.copy(autoHideVpnName = enabled) }
            },
        )
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

private enum class AutoHideSetting {
    VpnService,
    VpnName,
}

private fun writeAutoHideSetting(
    context: android.content.Context,
    apps: List<AppSummary>,
    transform: (CanonicalSettings) -> CanonicalSettings,
): Int {
    val snapshot = TargetsCache.snapshot.value
    val base =
        snapshot?.let(::buildCanonicalConfigFromTargetsSnapshot)
            ?: CanonicalConfig(debug = isEnabledInPrefs(context))
    val canonical =
        applyAutoHiddenPackages(
            config = base.copy(settings = transform(base.settings)),
            selfPkg = context.packageName,
            signals = apps.map(AppSummary::toAutoHideSignal),
        )
    val (exit, _) = suExec(buildCanonicalConfigWriteCommand(canonical))
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
