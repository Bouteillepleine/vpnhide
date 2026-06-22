package dev.okhsunrog.vpnhide

import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

internal data class HidingEntry(
    override val packageName: String,
    override val label: String,
    override val icon: Drawable?,
    override val isSystem: Boolean,
    override val userIds: List<Int> = emptyList(),
    val hidden: Boolean = false,
    val observer: Boolean = false,
) : TargetEntry {
    override val anySelected get() = hidden || observer
}

internal enum class HidingRole { HIDDEN, OBSERVER }

@Composable
fun AppHidingScreen(
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
        helpPrefKey = "apps_hiding",
        helpTitle = stringResource(R.string.hiding_help_title),
        help = {
            Text(
                text = stringResource(R.string.hiding_hint_roles),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.hiding_hint_system),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.hiding_hint_reboot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        merge = { apps, t, selfPkg ->
            // Packages with both roles crash on startup: the app queries its own
            // PackageInfo/ResolveInfo during init, we detect the observer caller
            // (itself) and strip its own package from the result, so frameworks
            // see a self-lookup NameNotFoundException and bail. Collapse to
            // observer-only on load so the next Save persists the fix.
            val hidden = t.hiddenPkgs
            val observers = t.observerNames
            var autoFixedConflict = false
            val entries =
                apps
                    .filter { it.packageName != selfPkg }
                    .map { app ->
                        val rawHidden = app.packageName in hidden
                        val rawObserver = app.packageName in observers
                        val (finalHidden, finalObserver) =
                            if (rawHidden && rawObserver) {
                                autoFixedConflict = true
                                false to true
                            } else {
                                rawHidden to rawObserver
                            }
                        HidingEntry(
                            packageName = app.packageName,
                            label = app.label,
                            icon = app.icon,
                            isSystem = app.isSystem,
                            userIds = app.userIds,
                            hidden = finalHidden,
                            observer = finalObserver,
                        )
                    }
            MergeResult(entries, resaveNeeded = autoFixedConflict)
        },
        countText = { entries, _ ->
            "H: ${entries.count { it.hidden }} · O: ${entries.count { it.observer }}"
        },
        buildSaveCommand = { entries, selfPkg, header ->
            // Always include self in the hidden list — self is managed invisibly, never shown in UI.
            val hiddenPkgs =
                (entries.filter { it.hidden }.map { it.packageName } + selfPkg).distinct().sorted()
            val observerPkgs = entries.filter { it.observer }.map { it.packageName }.sorted()
            buildHidingSaveCommand(header, hiddenPkgs, observerPkgs)
        },
        successMessage = { entries, res ->
            res.getString(
                R.string.hiding_save_success,
                entries.count { it.hidden },
                entries.count { it.observer },
            )
        },
    ) { app, userNames, _, onChange ->
        HidingAppRow(
            app = app,
            userNames = userNames,
            onToggle = { role ->
                // Roles are mutually exclusive: turning one on forces the other
                // off. Avoids the H+O self-hide crash (app can't resolve its own
                // package info).
                onChange(
                    when (role) {
                        HidingRole.HIDDEN -> {
                            val newHidden = !app.hidden
                            app.copy(hidden = newHidden, observer = if (newHidden) false else app.observer)
                        }

                        HidingRole.OBSERVER -> {
                            val newObserver = !app.observer
                            app.copy(observer = newObserver, hidden = if (newObserver) false else app.hidden)
                        }
                    },
                )
            },
        )
    }
}

private fun buildHidingSaveCommand(
    header: String,
    hiddenPkgs: List<String>,
    observerPkgs: List<String>,
): String {
    fun encode(body: String): String = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)

    val parts = mutableListOf<String>()

    // Hidden list: package names, one per line.
    // Mode 0640 + group=system: system_server reads via the group bit;
    // untrusted apps get EACCES because /data/system/ is mode 0775 (the
    // file's "other" bits decide reachability). Prevents apps from
    // enumerating the hidden-package list to fingerprint vpnhide.
    val hiddenBody = "$header\n" + hiddenPkgs.joinToString("\n") + if (hiddenPkgs.isNotEmpty()) "\n" else ""
    val hiddenB64 = encode(hiddenBody)
    parts += "echo '$hiddenB64' | base64 -d > $SS_HIDDEN_PKGS_FILE"
    parts += systemDataFilePermsParts(SS_HIDDEN_PKGS_FILE, "640")

    // Observer list: resolved UIDs. Same 0640 root:system rationale.
    if (observerPkgs.isNotEmpty()) {
        parts += buildUidResolverCommand(observerPkgs, SS_OBSERVER_UIDS_FILE)
    } else {
        parts += "echo > $SS_OBSERVER_UIDS_FILE 2>/dev/null"
    }
    parts += systemDataFilePermsParts(SS_OBSERVER_UIDS_FILE, "640")

    return parts.joinToString(" ; ")
}

@Composable
private fun HidingAppRow(
    app: HidingEntry,
    userNames: Map<Int, String>,
    onToggle: (HidingRole) -> Unit,
) {
    TargetRowShell(
        label = app.label,
        packageName = app.packageName,
        icon = app.icon,
        userIds = app.userIds,
        userNames = userNames,
    ) {
        TargetChip(
            label = stringResource(R.string.hiding_chip_hidden),
            enabled = app.hidden,
            onClick = { onToggle(HidingRole.HIDDEN) },
        )
        TargetChip(
            label = stringResource(R.string.hiding_chip_observer),
            enabled = app.observer,
            onClick = { onToggle(HidingRole.OBSERVER) },
        )
    }
}
