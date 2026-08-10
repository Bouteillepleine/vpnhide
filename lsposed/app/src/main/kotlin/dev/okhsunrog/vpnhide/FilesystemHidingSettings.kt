package dev.okhsunrog.vpnhide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.ui.components.PreferenceRowSwitch
import dev.okhsunrog.vpnhide.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FilesystemHidingSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets by TargetsCache.snapshot.collectAsState()
    var saving by remember { mutableStateOf(false) }
    val enabled = targets?.canonicalConfig?.settings?.experimentalFilesystemHiding == true

    LaunchedEffect(Unit) { TargetsCache.ensureLoaded(scope, context) }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SectionHeader(
            text = stringResource(R.string.settings_experimental_protection),
            bold = false,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        )
        PreferenceRowSwitch(
            title = stringResource(R.string.settings_filesystem_hiding),
            subtitle = stringResource(R.string.settings_filesystem_hiding_sub),
            icon = Icons.Default.VisibilityOff,
            checked = enabled,
            enabled = targets != null && !saving,
            onCheckedChange = { value ->
                saving = true
                scope.launch {
                    val exit = withContext(Dispatchers.IO) { writeFilesystemHidingSetting(value) }
                    saving = false
                    if (exit == 0) TargetsCache.refresh(scope, context)
                }
            },
        )
    }
}

private fun writeFilesystemHidingSetting(enabled: Boolean): Int {
    val snapshot = TargetsCache.snapshot.value ?: return 1
    val base = buildCanonicalConfigFromTargetsSnapshot(snapshot)
    val canonical =
        base.copy(
            settings = base.settings.copy(experimentalFilesystemHiding = enabled),
        )
    return CanonicalConfigRepository
        .persist(canonical, activation = CanonicalActivation(native = false))
        .exitCode
}
