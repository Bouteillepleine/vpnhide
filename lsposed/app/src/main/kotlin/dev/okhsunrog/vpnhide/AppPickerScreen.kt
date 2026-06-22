package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

data class AppEntry(
    override val packageName: String,
    override val label: String,
    override val icon: Drawable?,
    override val isSystem: Boolean,
    override val userIds: List<Int> = emptyList(),
    val kmod: Boolean = false,
    val zygisk: Boolean = false,
    val lsposed: Boolean = false,
) : TargetEntry {
    override val anySelected get() = kmod || zygisk || lsposed
}

/** Which installed modules are present (detected once at load). */
data class InstalledModules(
    val kmod: Boolean = false,
    val zygisk: Boolean = false,
)

internal enum class Layer { KMOD, ZYGISK, LSPOSED }

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
        helpPrefKey = "apps_tun",
        helpTitle = stringResource(R.string.apps_help_title),
        help = {
            Text(
                text = stringResource(R.string.apps_hint_toggles),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.apps_hint_restart_target),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.apps_hint_zygisk),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        merge = { apps, t, selfPkg ->
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
                            kmod = app.packageName in t.kmodTargets,
                            zygisk = app.packageName in t.zygiskTargets,
                            lsposed = app.packageName in t.lsposedTargets,
                        )
                    },
            )
        },
        countText = { entries, res ->
            res.getString(R.string.selected_count, entries.count { it.anySelected })
        },
        buildSaveCommand = { entries, selfPkg, header ->
            val kmodPkgs = (entries.filter { it.kmod }.map { it.packageName } + selfPkg).distinct().sorted()
            val zygiskPkgs = (entries.filter { it.zygisk }.map { it.packageName } + selfPkg).distinct().sorted()
            val lsposedPkgs = (entries.filter { it.lsposed }.map { it.packageName } + selfPkg).distinct().sorted()
            buildSaveCommand(header, kmodPkgs, zygiskPkgs, lsposedPkgs)
        },
        successMessage = { entries, res ->
            res.getString(R.string.save_success, entries.count { it.anySelected })
        },
    ) { app, userNames, targets, onChange ->
        val installed =
            InstalledModules(
                kmod = targets.kmodModuleInstalled,
                zygisk = targets.zygiskModuleInstalled,
            )
        AppRow(
            app = app,
            userNames = userNames,
            installed = installed,
            onToggle = { layer ->
                onChange(
                    when (layer) {
                        Layer.KMOD -> app.copy(kmod = !app.kmod)
                        Layer.ZYGISK -> app.copy(zygisk = !app.zygisk)
                        Layer.LSPOSED -> app.copy(lsposed = !app.lsposed)
                    },
                )
            },
            onToggleAll = {
                val newState = !app.anySelected
                onChange(
                    app.copy(
                        kmod = if (installed.kmod) newState else false,
                        zygisk = if (installed.zygisk) newState else false,
                        lsposed = newState,
                    ),
                )
            },
        )
    }
}

private fun buildSaveCommand(
    header: String,
    kmodPkgs: List<String>,
    zygiskPkgs: List<String>,
    lsposedPkgs: List<String>,
): String {
    fun encodeBody(pkgs: List<String>): String {
        val body = "$header\n" + pkgs.joinToString("\n") + if (pkgs.isNotEmpty()) "\n" else ""
        return android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
    }

    val parts = mutableListOf<String>()

    // Write kmod targets
    val kmodB64 = encodeBody(kmodPkgs)
    parts += "if [ -d /data/adb/vpnhide_kmod ]; then echo '$kmodB64' | base64 -d > $KMOD_TARGETS && chmod 644 $KMOD_TARGETS; fi"

    // Write zygisk targets
    val zygiskB64 = encodeBody(zygiskPkgs)
    parts += "if [ -d /data/adb/vpnhide_zygisk ]; then echo '$zygiskB64' | base64 -d > $ZYGISK_TARGETS && chmod 644 $ZYGISK_TARGETS; fi"
    parts += "cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>/dev/null; true"

    // Write lsposed targets (always — the dir is created by service.sh or us)
    val lsposedB64 = encodeBody(lsposedPkgs)
    parts += "mkdir -p /data/adb/vpnhide_lsposed"
    parts += "echo '$lsposedB64' | base64 -d > $LSPOSED_TARGETS && chmod 644 $LSPOSED_TARGETS"

    // Resolve kmod UIDs -> /proc/vpnhide_targets
    if (kmodPkgs.isNotEmpty()) {
        parts += buildUidResolverCommand(kmodPkgs, PROC_TARGETS)
    } else {
        parts += "echo > $PROC_TARGETS 2>/dev/null; true"
    }

    // Resolve lsposed UIDs -> /data/system/vpnhide_uids.txt
    // Mode 0640 + group=system: system_server reads via the group bit;
    // untrusted apps get EACCES because /data/system/ is mode 0775 (parent
    // is traversable, file's "other" bits decide). Prevents apps from
    // enumerating the target UID list to fingerprint vpnhide.
    if (lsposedPkgs.isNotEmpty()) {
        parts += buildUidResolverCommand(lsposedPkgs, SS_UIDS_FILE)
    } else {
        parts += "echo > $SS_UIDS_FILE 2>/dev/null"
    }
    parts += systemDataFilePermsParts(SS_UIDS_FILE, "640")

    return parts.joinToString(" ; ")
}

@Composable
private fun AppRow(
    app: AppEntry,
    userNames: Map<Int, String>,
    installed: InstalledModules,
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
        TargetChip("L", app.lsposed) { onToggle(Layer.LSPOSED) }
        if (installed.kmod) {
            TargetChip("K", app.kmod) { onToggle(Layer.KMOD) }
        }
        if (installed.zygisk) {
            TargetChip("Z", app.zygisk) { onToggle(Layer.ZYGISK) }
        }
    }
}
