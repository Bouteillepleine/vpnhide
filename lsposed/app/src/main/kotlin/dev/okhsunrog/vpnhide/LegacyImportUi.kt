package dev.okhsunrog.vpnhide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.ui.components.ButtonSpinner
import dev.okhsunrog.vpnhide.ui.components.EnhancedOutlinedButton
import kotlinx.coroutines.launch

/**
 * Dashboard banner for a pre-1.0 config found next to a config that already has
 * roles — the case the startup importer deliberately does not decide by itself
 * (see [LegacyConfigImport]).
 *
 * "Hide" only silences the banner ([AppSettings.legacyImportDismissed]); the
 * files stay on disk and Settings → Configuration keeps the import available,
 * so a mis-tap costs nothing.
 */
@Composable
internal fun LegacyImportBanner(
    prompt: LegacyImportPrompt,
    containerColor: Color,
    contentColor: Color,
    onImport: () -> Unit,
    onHide: () -> Unit,
) {
    StatusBanner(
        text = stringResource(R.string.legacy_import_banner, prompt.packages),
        containerColor = containerColor,
        contentColor = contentColor,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnhancedOutlinedButton(onClick = onImport) {
                    Text(stringResource(R.string.legacy_import_banner_action))
                }
                EnhancedOutlinedButton(onClick = onHide) {
                    Text(stringResource(R.string.legacy_import_banner_hide))
                }
            }
        },
    )
}

private sealed interface LegacyImportUiState {
    data object Choosing : LegacyImportUiState

    data object Running : LegacyImportUiState

    data class Done(
        val outcome: LegacyImportOutcome,
    ) : LegacyImportUiState
}

/**
 * Merge / replace picker for the pending import.
 *
 * Merge is offered first because it cannot lose anything: roles are unioned per
 * package. Replace exists for the user who wants the old list verbatim and
 * drops the current app roles — `settings` (auto-hide, superkey, optional
 * features) and VPN Hide's own entry survive either way.
 */
@Composable
internal fun LegacyImportDialog(
    prompt: LegacyImportPrompt,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LegacyImportUiState>(LegacyImportUiState.Choosing) }

    fun run(mode: LegacyImportMode) {
        state = LegacyImportUiState.Running
        scope.launch {
            state = LegacyImportUiState.Done(LegacyConfigImporter.import(mode, context.packageName))
        }
    }

    val running = state is LegacyImportUiState.Running
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.legacy_import_title)) },
        text = {
            when (val current = state) {
                is LegacyImportUiState.Running -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ButtonSpinner(size = 20.dp, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.legacy_import_running))
                    }
                }

                is LegacyImportUiState.Done -> {
                    Text(
                        when (val outcome = current.outcome) {
                            is LegacyImportOutcome.Imported -> {
                                stringResource(R.string.legacy_import_done, outcome.packages)
                            }

                            LegacyImportOutcome.NothingToImport -> {
                                stringResource(R.string.legacy_import_nothing)
                            }

                            is LegacyImportOutcome.Failed -> {
                                stringResource(R.string.legacy_import_failed, outcome.detail)
                            }
                        },
                    )
                }

                LegacyImportUiState.Choosing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(
                                R.string.legacy_import_summary,
                                prompt.packages,
                                prompt.java,
                                prompt.native,
                                prompt.appHiding,
                                prompt.ports,
                                prompt.hidden,
                            ),
                        )
                        if (prompt.unresolvedObserverUids > 0) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.legacy_import_unresolved,
                                        prompt.unresolvedObserverUids,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.legacy_import_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                LegacyImportUiState.Choosing -> {
                    TextButton(onClick = { run(LegacyImportMode.Merge) }) {
                        Text(stringResource(R.string.legacy_import_merge))
                    }
                }

                is LegacyImportUiState.Done -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.reset_close))
                    }
                }

                LegacyImportUiState.Running -> {
                    Unit
                }
            }
        },
        dismissButton = {
            if (state is LegacyImportUiState.Choosing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { run(LegacyImportMode.Replace) }) {
                        Text(stringResource(R.string.legacy_import_replace))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        },
    )
}
