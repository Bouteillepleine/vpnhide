package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.generated.HookIds
import dev.okhsunrog.vpnhide.settings.LocalSettingsState

/**
 * One row per app across every protection role. The three old tabs (Tun /
 * App-hiding / Ports) are merged into this single list so a target is
 * configured once, in one place, and saved once. Roles:
 *
 *  - [java]      "J" — LSPosed (the Java layer)
 *  - [native]    "N" — the one active native backend (kmod / KPM / Zygisk, §1.5);
 *                       written to every installed native backend, only the
 *                       active one acts.
 *  - [appHiding] "A" — observer of the hidden-package view (which packages get
 *                       hidden is auto-detected; see autoDetectHiddenPackages).
 *  - [ports]     "P" — localhost-port blocking observer.
 */
data class AppEntry(
    override val packageName: String,
    override val label: String,
    override val icon: Drawable?,
    override val isSystem: Boolean,
    override val userIds: List<Int> = emptyList(),
    val java: Boolean = false,
    val javaHooks: List<String>? = null,
    val native: Boolean = false,
    val nativeHooks: List<String>? = null,
    val appHiding: Boolean = false,
    val ports: Boolean = false,
    val declaresVpnService: Boolean = false,
    val nameContainsVpn: Boolean = false,
) : TargetEntry {
    override val anySelected get() = java || native || appHiding || ports
}

internal enum class Layer { JAVA, NATIVE, APP_HIDING, PORTS }

@Composable
fun AppPickerScreen(
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
        helpPrefKey = "apps_unified",
        helpTitle = stringResource(R.string.apps_help_title),
        help = {
            Text(
                text = stringResource(R.string.apps_hint_roles),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.apps_hint_restart_target),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.apps_hint_zygisk),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        merge = { apps, t, selfPkg ->
            val nativeTargets = t.nativeTargets
            val observers = t.observerNames
            val autoHideSignals = apps.map(AppSummary::toAutoHideSignal)
            val baseCanonical = t.canonicalConfig ?: buildCanonicalConfigFromTargetsSnapshot(t)
            val autoApplied =
                applyAutoHiddenPackages(
                    config = baseCanonical,
                    selfPkg = selfPkg,
                    signals = autoHideSignals,
                )
            MergeResult(
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        val canonicalApp = baseCanonical.apps[app.packageName]
                        val nativeRole = canonicalApp?.native
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            java = canonicalApp?.java ?: (app.packageName in t.lsposedTargets),
                            javaHooks = canonicalApp?.takeIf { it.java }?.javaHooks?.takeIf { it.isNotEmpty() },
                            native = app.packageName in nativeTargets,
                            nativeHooks = nativeRole?.hooks?.takeIf { it.isNotEmpty() },
                            appHiding = app.packageName in observers,
                            ports = app.packageName in t.portsObservers,
                            declaresVpnService = app.declaresVpnService,
                            nameContainsVpn = app.nameContainsVpn,
                        )
                    },
                resaveNeeded = autoApplied != baseCanonical,
            )
        },
        countText = { entries, res ->
            res.getString(R.string.selected_count, entries.count { it.anySelected })
        },
        buildSaveCommand = { entries, ctx ->
            buildUnifiedSaveCommand(
                ctx = ctx,
                selections = entries.map(AppEntry::toRoleSelection),
                autoHideSignals = entries.map(AppEntry::toAutoHideSignal),
            )
        },
        successMessage = { entries, res ->
            res.getString(R.string.save_success, entries.count { it.anySelected })
        },
    ) { app, userNames, targets, onChange ->
        AppRow(
            app = app,
            userNames = userNames,
            anyNativeInstalled = targets.anyNativeInstalled,
            portsInstalled = targets.portsModuleInstalled,
            onToggle = { layer ->
                onChange(
                    when (layer) {
                        Layer.JAVA -> app.copy(java = !app.java, javaHooks = null)
                        Layer.NATIVE -> app.copy(native = !app.native, nativeHooks = null)
                        Layer.APP_HIDING -> app.copy(appHiding = !app.appHiding)
                        Layer.PORTS -> app.copy(ports = !app.ports)
                    },
                )
            },
            onJavaHooksChange = { hooks ->
                onChange(
                    app.copy(
                        java = hooks == null || hooks.isNotEmpty(),
                        javaHooks = hooks?.takeIf { it.isNotEmpty() },
                    ),
                )
            },
            onNativeHooksChange = { hooks ->
                onChange(
                    app.copy(
                        native = hooks == null || hooks.isNotEmpty(),
                        nativeHooks = hooks?.takeIf { it.isNotEmpty() },
                    ),
                )
            },
            onToggleAll = {
                val newState = !app.anySelected
                onChange(
                    app.copy(
                        java = newState,
                        javaHooks = null,
                        native = if (targets.anyNativeInstalled) newState else false,
                        nativeHooks = null,
                        appHiding = newState,
                        ports = if (targets.portsModuleInstalled) newState else false,
                    ),
                )
            },
        )
    }
}

/**
 * Build the single root command that persists every role at once. The canonical
 * JSON is the single persistent source of truth. Native backends are updated by
 * running the installed activator; LSPosed reads the JSON directly; the ports
 * activator derives its observer set from the same JSON.
 */
private fun buildUnifiedSaveCommand(
    ctx: SaveContext,
    selections: Collection<AppRoleSelection>,
    autoHideSignals: Collection<AppAutoHideSignal>,
): String {
    val parts = mutableListOf<String>()

    val canonical =
        buildCanonicalConfigForAppPickerSave(
            debug = ctx.debug,
            selfPkg = ctx.selfPkg,
            selections = selections,
            snapshot = TargetsCache.snapshot.value,
            autoHideSignals = autoHideSignals,
        )
    parts += buildCanonicalConfigWriteCommand(canonical)

    parts += ConfigChannels.nativeWriteParts()

    parts += "if [ -x $PORTS_ACTIVATOR ]; then $PORTS_ACTIVATOR; fi"

    return parts.joinToString(" ; ")
}

private fun AppEntry.toRoleSelection(): AppRoleSelection =
    AppRoleSelection(
        packageName = packageName,
        java = java,
        javaHooks = javaHooks,
        native = native,
        nativeHooks = nativeHooks,
        appHiding = appHiding,
        ports = ports,
    )

private fun AppEntry.toAutoHideSignal(): AppAutoHideSignal =
    AppAutoHideSignal(
        packageName = packageName,
        declaresVpnService = declaresVpnService,
        nameContainsVpn = nameContainsVpn,
    )

@Composable
private fun AppRow(
    app: AppEntry,
    userNames: Map<Int, String>,
    anyNativeInstalled: Boolean,
    portsInstalled: Boolean,
    onToggle: (Layer) -> Unit,
    onJavaHooksChange: (List<String>?) -> Unit,
    onNativeHooksChange: (List<String>?) -> Unit,
    onToggleAll: () -> Unit,
) {
    var javaHookDialogOpen by remember { mutableStateOf(false) }
    var nativeHookDialogOpen by remember { mutableStateOf(false) }
    val fullRoleLabels = LocalSettingsState.current.fullProtectionRoleLabels
    TargetRowShell(
        label = app.label,
        packageName = app.packageName,
        icon = app.icon,
        userIds = app.userIds,
        userNames = userNames,
        modifier = Modifier.clickable(onClick = onToggleAll),
    ) {
        HookTargetChip(
            label =
                roleLabel(
                    compact = stringResource(R.string.chip_java),
                    full = stringResource(R.string.chip_java_full),
                    partial = app.javaHooks != null,
                    fullLabels = fullRoleLabels,
                ),
            enabled = app.java,
            onToggle = { onToggle(Layer.JAVA) },
            onConfigure = { javaHookDialogOpen = true },
            contentDescription = stringResource(R.string.java_hooks_title),
        )
        if (anyNativeInstalled) {
            HookTargetChip(
                label =
                    roleLabel(
                        compact = stringResource(R.string.chip_native),
                        full = stringResource(R.string.chip_native_full),
                        partial = app.nativeHooks != null,
                        fullLabels = fullRoleLabels,
                    ),
                enabled = app.native,
                onToggle = { onToggle(Layer.NATIVE) },
                onConfigure = { nativeHookDialogOpen = true },
                contentDescription = stringResource(R.string.native_hooks_title),
            )
        }
        TargetChip(
            label =
                if (fullRoleLabels) {
                    stringResource(R.string.chip_app_hiding_full)
                } else {
                    stringResource(R.string.chip_app_hiding)
                },
            enabled = app.appHiding,
        ) {
            onToggle(Layer.APP_HIDING)
        }
        if (portsInstalled) {
            TargetChip(
                label =
                    if (fullRoleLabels) {
                        stringResource(R.string.chip_ports_full)
                    } else {
                        stringResource(R.string.chip_ports)
                    },
                enabled = app.ports,
            ) {
                onToggle(Layer.PORTS)
            }
        }
    }

    if (javaHookDialogOpen) {
        HooksDialog(
            app = app,
            title = stringResource(R.string.java_hooks_title),
            hookEntries = LsposedJavaHookEntries,
            selectedHooks = app.javaHooks,
            onDismiss = { javaHookDialogOpen = false },
            onSave = { hooks ->
                onJavaHooksChange(hooks)
                javaHookDialogOpen = false
            },
        )
    }

    if (nativeHookDialogOpen) {
        HooksDialog(
            app = app,
            title = stringResource(R.string.native_hooks_title),
            hookEntries = NativeHookEntries,
            selectedHooks = app.nativeHooks,
            onDismiss = { nativeHookDialogOpen = false },
            onSave = { hooks ->
                onNativeHooksChange(hooks)
                nativeHookDialogOpen = false
            },
        )
    }
}

private fun roleLabel(
    compact: String,
    full: String,
    partial: Boolean,
    fullLabels: Boolean,
): String = (if (fullLabels) full else compact) + if (partial) "*" else ""

@Composable
private fun HookTargetChip(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onConfigure: () -> Unit,
    contentDescription: String,
) {
    val containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                modifier =
                    Modifier
                        .clickable(onClick = onToggle)
                        .padding(start = 8.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
            )
            Box(
                modifier =
                    Modifier
                        .clickable(onClick = onConfigure)
                        .padding(start = 4.dp, top = 3.dp, end = 7.dp, bottom = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun HooksDialog(
    app: AppEntry,
    title: String,
    hookEntries: List<HookIds.Hook>,
    selectedHooks: List<String>?,
    onDismiss: () -> Unit,
    onSave: (List<String>?) -> Unit,
) {
    val hookNames = remember(hookEntries) { hookEntries.map { it.hookName } }
    var selected by remember(app.packageName, selectedHooks, hookNames) {
        mutableStateOf(selectedHooks?.toSet() ?: hookNames.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(hookEntries, key = { it.hookName }) { hook ->
                        val checked = hook.hookName in selected
                        HookRow(
                            hook = hook,
                            checked = checked,
                            onCheckedChange = { enabled ->
                                selected =
                                    if (enabled) {
                                        selected + hook.hookName
                                    } else {
                                        selected - hook.hookName
                                    }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(resolveHookSelection(hookNames, selected))
                },
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

@Composable
private fun HookRow(
    hook: HookIds.Hook,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clickable { onCheckedChange(!checked) }
                .fillMaxWidth()
                .padding(vertical = 6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = hook.hookName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = hook.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
