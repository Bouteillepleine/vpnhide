package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class PortsEntry(
    override val packageName: String,
    override val label: String,
    override val icon: Drawable?,
    override val isSystem: Boolean,
    override val userIds: List<Int> = emptyList(),
    val observer: Boolean = false,
) : TargetEntry {
    override val anySelected get() = observer
}

@Composable
fun PortsHidingScreen(
    searchQuery: String,
    showSystem: Boolean,
    showRussianOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    TargetPickerScreen(
        searchQuery = searchQuery,
        showSystem = showSystem,
        showRussianOnly = showRussianOnly,
        modifier = modifier,
        helpPrefKey = "apps_ports",
        helpTitle = stringResource(R.string.ports_help_title),
        help = {
            Text(
                text = stringResource(R.string.ports_hint_role),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.ports_hint_safe),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ports_hint_reboot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        merge = { apps, t, selfPkg ->
            MergeResult(
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        PortsEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            observer = app.packageName in t.portsObservers,
                        )
                    },
            )
        },
        countText = { entries, res ->
            res.getString(R.string.ports_count, entries.count { it.observer })
        },
        buildSaveCommand = { entries, _, header ->
            val observerPkgs = entries.filter { it.observer }.map { it.packageName }.sorted()
            buildPortsSaveCommand(header, observerPkgs)
        },
        successMessage = { entries, res ->
            res.getString(R.string.ports_save_success, entries.count { it.observer })
        },
        moduleMissing = { it.portsModuleInstalled.not() },
        moduleMissingContent = { m -> NotInstalledCard(modifier = m) },
    ) { app, userNames, _, onChange ->
        PortsAppRow(
            app = app,
            userNames = userNames,
            onToggle = { onChange(app.copy(observer = !app.observer)) },
        )
    }
}

private fun buildPortsSaveCommand(
    header: String,
    observerPkgs: List<String>,
): String {
    // observers.txt stores package names (one per line). UID resolution lives
    // entirely inside vpnhide_ports_apply.sh so app reinstalls (which rotate
    // the UID) get picked up automatically on the next apply.
    val body = (listOf(header) + observerPkgs).joinToString(separator = "\n", postfix = "\n")
    val b64 = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
    return listOf(
        "mkdir -p /data/adb/vpnhide_ports",
        "echo '$b64' | base64 -d > $PORTS_OBSERVERS_FILE",
        "chmod 644 $PORTS_OBSERVERS_FILE",
        "sh $PORTS_APPLY_SCRIPT",
    ).joinToString(" && ")
}

@Composable
private fun NotInstalledCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ports_module_not_installed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.ports_module_not_installed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PortsAppRow(
    app: PortsEntry,
    userNames: Map<Int, String>,
    onToggle: () -> Unit,
) {
    TargetRowShell(
        label = app.label,
        packageName = app.packageName,
        icon = app.icon,
        userIds = app.userIds,
        userNames = userNames,
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        TargetChip(
            label = stringResource(R.string.ports_chip),
            enabled = app.observer,
            onClick = onToggle,
        )
    }
}
