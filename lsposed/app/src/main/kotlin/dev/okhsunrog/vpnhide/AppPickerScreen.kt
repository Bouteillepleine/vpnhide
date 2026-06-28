package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * One row per app across every protection role. The three old tabs (Tun /
 * App-hiding / Ports) are merged into this single list so a target is
 * configured once, in one place, and saved once. Roles:
 *
 *  - [java]      "J" — LSPosed (the always-on Java layer)
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
    val native: Boolean = false,
    val appHiding: Boolean = false,
    val ports: Boolean = false,
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
            MergeResult(
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            java = app.packageName in t.lsposedTargets,
                            native = app.packageName in nativeTargets,
                            appHiding = app.packageName in observers,
                            ports = app.packageName in t.portsObservers,
                        )
                    },
            )
        },
        countText = { entries, res ->
            res.getString(R.string.selected_count, entries.count { it.anySelected })
        },
        buildSaveCommand = { entries, selfPkg, header ->
            val javaPkgs = (entries.filter { it.java }.map { it.packageName } + selfPkg).distinct().sorted()
            val nativePkgs = (entries.filter { it.native }.map { it.packageName } + selfPkg).distinct().sorted()
            val observerPkgs = entries.filter { it.appHiding }.map { it.packageName }.sorted()
            val portsPkgs = entries.filter { it.ports }.map { it.packageName }.sorted()
            buildUnifiedSaveCommand(header, javaPkgs, nativePkgs, observerPkgs, portsPkgs, selfPkg)
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
                        Layer.JAVA -> app.copy(java = !app.java)
                        Layer.NATIVE -> app.copy(native = !app.native)
                        Layer.APP_HIDING -> app.copy(appHiding = !app.appHiding)
                        Layer.PORTS -> app.copy(ports = !app.ports)
                    },
                )
            },
            onToggleAll = {
                val newState = !app.anySelected
                onChange(
                    app.copy(
                        java = newState,
                        native = if (targets.anyNativeInstalled) newState else false,
                        appHiding = newState,
                        ports = if (targets.portsModuleInstalled) newState else false,
                    ),
                )
            },
        )
    }
}

/**
 * Build the single root command that persists every role at once.
 *
 *  - Java   -> LSPosed targets file + system_server UID file.
 *  - Native -> one list written to every installed backend (kmod /proc + file,
 *              Zygisk file + module mirror, KPM file). Only the active backend
 *              acts (§1.5).
 *  - App-hiding -> observer UID file + the (auto-detected) hidden-package file.
 *  - Ports  -> observers file + apply script, when the ports module is present.
 */
private fun buildUnifiedSaveCommand(
    header: String,
    javaPkgs: List<String>,
    nativePkgs: List<String>,
    observerPkgs: List<String>,
    portsPkgs: List<String>,
    selfPkg: String,
): String {
    val parts = mutableListOf<String>()

    // --- Java (LSPosed) ---
    parts += "mkdir -p /data/adb/vpnhide_lsposed"
    parts += "${buildConfigWriteCommand(LSPOSED_TARGETS, header, javaPkgs)} && chmod 644 $LSPOSED_TARGETS"
    if (javaPkgs.isNotEmpty()) {
        parts += buildUidResolverCommand(javaPkgs, SS_UIDS_FILE)
    } else {
        parts += "echo > $SS_UIDS_FILE 2>/dev/null"
    }
    parts += systemDataFilePermsParts(SS_UIDS_FILE, "640")

    // --- Native: one list, fanned out to every installed backend ---
    parts +=
        "if [ -d /data/adb/vpnhide_kmod ]; then ${buildConfigWriteCommand(KMOD_TARGETS, header, nativePkgs)} && chmod 644 $KMOD_TARGETS; fi"
    parts +=
        "if [ -d /data/adb/vpnhide_zygisk ]; then ${buildConfigWriteCommand(
            ZYGISK_TARGETS,
            header,
            nativePkgs,
        )} && chmod 644 $ZYGISK_TARGETS; fi"
    parts += "cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>/dev/null; true"
    parts +=
        "if [ -d /data/adb/vpnhide_kpm ]; then ${buildConfigWriteCommand(KPM_TARGETS, header, nativePkgs)} && chmod 644 $KPM_TARGETS; fi"
    // kmod live-applies via /proc; resolve native UIDs there. The KPM applies
    // its targets at boot from the file above — a live `kpatch kpm ctl0` push
    // needs the runtime CLI + (APatch) superkey and lands with the on-device
    // KPM integration (TODO).
    if (nativePkgs.isNotEmpty()) {
        parts += buildUidResolverCommand(nativePkgs, PROC_TARGETS)
    } else {
        parts += "echo > $PROC_TARGETS 2>/dev/null; true"
    }

    // --- App hiding: observers (A) + hidden packages (auto-detected, stub) ---
    val existingHidden = TargetsCache.snapshot.value?.hiddenPkgs ?: emptySet()
    val hiddenPkgs = resolveHiddenPackages(existingHidden, observerPkgs.toSet(), selfPkg)
    parts += appHidingSaveParts(header, hiddenPkgs, observerPkgs)

    // --- Ports (only when the module is installed) ---
    parts +=
        "if [ -d $PORTS_MODULE_DIR ]; then mkdir -p /data/adb/vpnhide_ports && " +
        "${buildConfigWriteCommand(PORTS_OBSERVERS_FILE, header, portsPkgs)} && " +
        "chmod 644 $PORTS_OBSERVERS_FILE && sh $PORTS_APPLY_SCRIPT; fi"

    return parts.joinToString(" ; ")
}

/**
 * Hidden-package file (auto-detected set) + observer (A) UID file. Both live at
 * 0640 root:system so system_server reads them and untrusted apps get EACCES.
 * Hidden vs observer are mutually exclusive (enforced by [resolveHiddenPackages]).
 */
private fun appHidingSaveParts(
    header: String,
    hiddenPkgs: List<String>,
    observerPkgs: List<String>,
): List<String> =
    buildList {
        add(buildConfigWriteCommand(SS_HIDDEN_PKGS_FILE, header, hiddenPkgs))
        addAll(systemDataFilePermsParts(SS_HIDDEN_PKGS_FILE, "640"))
        add(
            if (observerPkgs.isNotEmpty()) {
                buildUidResolverCommand(observerPkgs, SS_OBSERVER_UIDS_FILE)
            } else {
                "echo > $SS_OBSERVER_UIDS_FILE 2>/dev/null"
            },
        )
        addAll(systemDataFilePermsParts(SS_OBSERVER_UIDS_FILE, "640"))
    }

@Composable
private fun AppRow(
    app: AppEntry,
    userNames: Map<Int, String>,
    anyNativeInstalled: Boolean,
    portsInstalled: Boolean,
    onToggle: (Layer) -> Unit,
    onToggleAll: () -> Unit,
) {
    TargetRowShell(
        label = app.label,
        packageName = app.packageName,
        icon = app.icon,
        userIds = app.userIds,
        userNames = userNames,
        modifier = Modifier.clickable(onClick = onToggleAll),
    ) {
        TargetChip(stringResource(R.string.chip_java), app.java) { onToggle(Layer.JAVA) }
        if (anyNativeInstalled) {
            TargetChip(stringResource(R.string.chip_native), app.native) { onToggle(Layer.NATIVE) }
        }
        TargetChip(stringResource(R.string.chip_app_hiding), app.appHiding) { onToggle(Layer.APP_HIDING) }
        if (portsInstalled) {
            TargetChip(stringResource(R.string.chip_ports), app.ports) { onToggle(Layer.PORTS) }
        }
    }
}
