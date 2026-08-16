package dev.okhsunrog.vpnhide

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.okhsunrog.vpnhide.generated.IfaceLists
import dev.okhsunrog.vpnhide.ui.components.ButtonSpinner
import dev.okhsunrog.vpnhide.ui.components.EnhancedButton
import dev.okhsunrog.vpnhide.ui.components.EnhancedCard
import dev.okhsunrog.vpnhide.ui.components.GroupedCard
import dev.okhsunrog.vpnhide.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun DiagnosticsScreen(
    selfNeedsRestart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val diagState by DiagnosticsCache.state.collectAsState()
    // The dashboard state carries which native backend is active + the optional hooks
    // it installed — the inputs needed to rebuild the canonical DiagnosticReport here,
    // so each check can be shown against the vectors the backend actually OWNS. Null
    // until the dashboard has loaded (then we fall back to the raw, ownership-less list).
    val dashState by DashboardCache.state.collectAsState()
    val tallyFmt = stringResource(R.string.diag_summary_tally)

    // Kick off the diagnostics run once per process. The cache parks at
    // Blocked(NEEDS_RESTART) itself when selfNeedsRestart (hooks aren't applied to this
    // app yet, so a run would be meaningless); run is idempotent otherwise.
    LaunchedEffect(selfNeedsRestart) {
        DiagnosticsCache.run(scope, context, selfNeedsRestart)
        // Ensure the backend/ownership state is available even when the user opens
        // Diagnostics without visiting the Dashboard first (cheap no-op if cached).
        DashboardCache.ensureLoaded(scope, context, selfNeedsRestart)
    }

    val results = (diagState as? DiagnosticsCache.State.Ready)?.results
    val blockedGate = (diagState as? DiagnosticsCache.State.Blocked)?.gate
    // Native probes that couldn't run (ECONNREFUSED from socket()) classify as
    // NotMeasured(NoNetworkPermission). Java-level checks never produce that state,
    // so this isolates the "app has no network permission" banner from everything else.
    val networkBlocked = results?.native?.anyNetworkBlocked() == true

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        val onRetry = {
            DiagnosticsCache.retry(scope, context, selfNeedsRestart)
            DashboardCache.refresh(scope, context, selfNeedsRestart)
        }
        when {
            blockedGate == DiagnosticGate.NEEDS_RESTART -> {
                StatusBanner(
                    text = stringResource(R.string.banner_added_self),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            blockedGate == DiagnosticGate.VPN_OFF -> {
                VpnOffPrompt(onRetry = onRetry)
            }

            blockedGate == DiagnosticGate.SELF_NOT_ROUTED -> {
                SelfNotRoutedPrompt(onRetry = onRetry)
            }

            diagState is DiagnosticsCache.State.Failed -> {
                DiagnosticsFailedPrompt(onRetry = onRetry)
            }

            diagState is DiagnosticsCache.State.Running ||
                diagState is DiagnosticsCache.State.NotRun -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            diagState is DiagnosticsCache.State.Ready -> {
                StatusBanner(
                    text = stringResource(R.string.banner_ready),
                    containerColor = StatusColors.successContainer(),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )

                if (networkBlocked) {
                    Spacer(Modifier.height(6.dp))
                    StatusBanner(
                        text = stringResource(R.string.banner_network_blocked),
                        containerColor = StatusColors.errorContainer(),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }

                results?.let { r ->
                    // Build the canonical report when the dashboard has loaded so each
                    // check knows whether the active backend OWNS its vector; otherwise
                    // render the raw list (every leak reads as a leak — the pre-report
                    // behaviour, used only in the brief window before the dashboard loads).
                    val report =
                        dashState?.let { ds ->
                            buildDiagnosticReport(
                                gate = DiagnosticGate.ROUTED,
                                results = r,
                                backend = ds.nativeBackend,
                                lsposedActive = ds.lsposed is LsposedState.Active,
                                complete = (diagState as? DiagnosticsCache.State.Ready)?.complete == true,
                                installedOptionalHooks = ds.installedOptionalHooks,
                            )
                        }
                    DiagnosticsResults(report = report, results = r, tallyFmt = tallyFmt)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A "Save As…" launcher for a generated `application/zip` file: on a picked uri it
 * copies [source]`()` off the main thread and swallows IO errors (a large zip would
 * block the UI, and a write failure would crash) — logging under [errorLabel].
 * [source] is read at launch time, so it always sees the latest generated file.
 */
@Composable
private fun rememberZipSaveLauncher(
    errorLabel: String,
    source: () -> File?,
): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val src = source() ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }.onFailure { HookLog.e("VpnHide: $errorLabel save failed: ${it.message}") }
            }
        }
    }
}

@Composable
fun DebugToolsSection(
    selfNeedsRestart: Boolean?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var debugZipFile by remember { mutableStateOf<File?>(null) }

    val saveLauncher = rememberZipSaveLauncher("debug-zip") { debugZipFile }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        LogcatRecordCard(selfNeedsRestart = selfNeedsRestart)

        Spacer(Modifier.height(16.dp))

        KernelImageExportCard()

        Spacer(Modifier.height(16.dp))

        // Collect button
        val zip = debugZipFile
        if (zip == null) {
            EnhancedButton(
                onClick = {
                    val restartState = selfNeedsRestart ?: return@EnhancedButton
                    exporting = true
                    scope.launch {
                        debugZipFile = exportDebugZip(cm, context, restartState)
                        exporting = false
                    }
                },
                enabled = !exporting && selfNeedsRestart != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    ButtonSpinner()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (exporting) {
                        stringResource(R.string.btn_export_debug_running)
                    } else {
                        stringResource(R.string.btn_export_debug)
                    },
                )
            }
        } else {
            FileSaveShareRow(
                saveLabel = stringResource(R.string.btn_save_debug),
                shareLabel = stringResource(R.string.btn_share_debug),
                sharePrimary = true,
                onSave = { saveLauncher.launch(zip.name) },
                onShare = { shareFileViaProvider(context, zip, "application/zip") },
            )
        }
    }
}

@Composable
private fun KernelImageExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var kernelImagesFile by remember { mutableStateOf<File?>(null) }

    val saveLauncher = rememberZipSaveLauncher("kernel-image") { kernelImagesFile }

    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.kernel_images_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.kernel_images_card_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val zip = kernelImagesFile
            if (zip == null || !zip.exists()) {
                EnhancedButton(
                    onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                kernelImagesFile = exportKernelImagesZip(context)
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (exporting) {
                        ButtonSpinner()
                    } else {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (exporting) {
                            stringResource(R.string.kernel_images_btn_export_running)
                        } else {
                            stringResource(R.string.kernel_images_btn_export)
                        },
                    )
                }
            } else {
                FileSaveShareRow(
                    saveLabel = stringResource(R.string.btn_save_kernel_images),
                    shareLabel = stringResource(R.string.btn_share_debug),
                    sharePrimary = true,
                    onSave = { saveLauncher.launch(zip.name) },
                    onShare = { shareFileViaProvider(context, zip, "application/zip") },
                )
            }
        }
    }
}

@Composable
private fun LogcatRecordCard(selfNeedsRestart: Boolean?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LogcatRecorder.state.collectAsState()

    // Tick every second while recording so the elapsed counter updates
    // even when sizeBytes happens to hold steady.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        if (state is LogcatRecorder.State.Recording) {
            while (true) {
                nowMs = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val saveLauncher =
        rememberZipSaveLauncher("logcat") { (state as? LogcatRecorder.State.Stopped)?.lastFile }

    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.logcat_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.logcat_card_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is LogcatRecorder.State.Recording -> {
                    val elapsed = (nowMs - s.startMs).coerceAtLeast(0L) / 1000
                    Text(
                        text =
                            stringResource(
                                R.string.logcat_recording_status,
                                formatElapsed(elapsed),
                                formatSize(s.sizeBytes),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    EnhancedButton(
                        onClick = {
                            scope.launch { LogcatRecorder.stop(context) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.logcat_btn_stop))
                    }
                }

                is LogcatRecorder.State.Stopped -> {
                    val last = s.lastFile
                    if (last != null && last.exists()) {
                        Text(
                            text =
                                stringResource(
                                    R.string.logcat_last_recording,
                                    formatElapsed(s.lastDurationMs / 1000),
                                    formatSize(last.length()),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FileSaveShareRow(
                            saveLabel = stringResource(R.string.btn_save),
                            shareLabel = stringResource(R.string.btn_share_debug),
                            sharePrimary = false,
                            onSave = { saveLauncher.launch(last.name) },
                            onShare = { shareFileViaProvider(context, last, "application/zip") },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    EnhancedButton(
                        onClick = {
                            val restartState = selfNeedsRestart ?: return@EnhancedButton
                            scope.launch { LogcatRecorder.start(context, restartState) }
                        },
                        enabled = selfNeedsRestart != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.logcat_btn_start))
                    }
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

/**
 * One row on the Diagnostics list, unified across sources so [CheckCard] renders one
 * shape. [uncovered] marks a native leak on a vector the active backend does not own
 * — a detection surface no active hook covers on this device. It is shown neutrally
 * (see [diagStatusUncovered]), never as a red leak, and grouped apart from the
 * backend's own vectors.
 */
private data class DiagCard(
    val name: String,
    val detail: String,
    val groundTruthDetail: String?,
    val outcome: CheckOutcome,
    val uncovered: Boolean,
)

/** From a canonical report check — the only source that knows [DiagnosticCheck.owned],
 * so it is the only one that can flag an uncovered native leak. */
private fun DiagnosticCheck.toDiagCard(): DiagCard =
    DiagCard(
        name = label,
        detail = appDetail,
        groundTruthDetail = groundTruthDetail,
        outcome = outcome,
        uncovered = layer == CheckLayer.NATIVE && outcome is CheckOutcome.Leak && !owned,
    )

/** Raw-list fallback (dashboard not yet loaded): no ownership known, so nothing is
 * marked uncovered — a leak reads as a leak, the pre-report behaviour. */
private fun CheckResult.toDiagCard(): DiagCard = DiagCard(name, detail, groundTruthDetail, outcome, uncovered = false)

/**
 * The results body: the honest headline (hidden vs still-leaking) plus the check
 * cards, split into the backend's own vectors, the vectors no active backend covers
 * on this device (shown neutrally), and the Java layer. [report] is null only in the
 * brief window before the dashboard loads, when we fall back to the raw list.
 */
@Composable
private fun DiagnosticsResults(
    report: DiagnosticReport?,
    results: CheckResults,
    tallyFmt: String,
) {
    val nativeCards = report?.native?.checks?.map { it.toDiagCard() } ?: results.nativeAll.map { it.toDiagCard() }
    val javaCards = report?.java?.checks?.map { it.toDiagCard() } ?: results.java.map { it.toDiagCard() }
    val covered = nativeCards.filterNot { it.uncovered }
    val uncovered = nativeCards.filter { it.uncovered }

    // Headline counts the backend's job: hidden vectors vs still-leaking OWNED
    // vectors. Uncovered vectors are out of the active backend's scope, so they are
    // reported below rather than folded into "leaking".
    val scored = covered + javaCards
    val hidden = scored.count { it.outcome is CheckOutcome.HiddenByBackend || it.outcome is CheckOutcome.HiddenBySelinux }
    val leaks = scored.count { it.outcome is CheckOutcome.Leak }

    Spacer(Modifier.height(12.dp))
    Text(
        text = String.format(tallyFmt, hidden, leaks),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(Modifier.height(16.dp))
    SectionHeader(stringResource(R.string.section_native))
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        covered.forEachIndexed { i, c -> CheckCard(c, index = i, count = covered.size) }
    }

    if (uncovered.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.section_native_uncovered))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.diag_uncovered_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            uncovered.forEachIndexed { i, c -> CheckCard(c, index = i, count = uncovered.size) }
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionHeader(stringResource(R.string.section_java))
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        javaCards.forEachIndexed { i, c -> CheckCard(c, index = i, count = javaCards.size) }
    }
}

/**
 * One check's status: a coloured **dot + short word** (the "3-B" treatment — the
 * app's own status-dot idiom from the module rows). The card colour tracks current
 * reality only — green when hidden (by backend OR SELinux) or nothing-to-leak, red
 * on a real leak, neutral when not measured — so a normal enforcing device is all
 * green. The backend-vs-SELinux attribution rides on the dot colour + word, never
 * on the card colour (no alarm on SELinux-protected items). Detail is collapsed by
 * default and revealed on tap; a leak is the only thing expanded up front.
 */
private data class DiagStatus(
    val label: String,
    val accent: Color,
    val container: Color,
    val expandedByDefault: Boolean,
)

// Thin renderer: the bucket decision lives in [diagStatusKind] (pure, unit-tested);
// here we only attach the localized word + theme colour. A leak is the one thing
// expanded by default. NothingToLeak/SELinux keep the no-alarm green container but
// a distinct dot + word (grey "nothing", blue "SELinux") so attribution shows.
@Composable
private fun diagStatus(outcome: CheckOutcome): DiagStatus =
    when (outcome.diagStatusKind()) {
        DiagStatusKind.Ok -> {
            DiagStatus(stringResource(R.string.diag_status_ok), StatusColors.successDot, StatusColors.successContainer(), false)
        }

        DiagStatusKind.Leak -> {
            DiagStatus(stringResource(R.string.diag_status_leak), StatusColors.errorDot, StatusColors.errorContainer(), true)
        }

        DiagStatusKind.NothingToLeak -> {
            DiagStatus(stringResource(R.string.diag_status_nothing), StatusColors.neutralAccent, StatusColors.successContainer(), false)
        }

        DiagStatusKind.Selinux -> {
            DiagStatus(stringResource(R.string.diag_status_selinux), StatusColors.infoAccent, StatusColors.successContainer(), false)
        }

        DiagStatusKind.NotMeasured -> {
            DiagStatus(stringResource(R.string.diag_status_nomeasure), StatusColors.neutralAccent, StatusColors.neutralContainer(), false)
        }
    }

/** Neutral "out of scope" status for a native leak on a vector the active backend
 * does not own: no active hook covers it on this device, so it is not the backend
 * failing — it is reported calmly (grey dot + word, no alarm, collapsed), never as a
 * red leak, so a working backend never reads as broken over a gap it cannot close. */
@Composable
private fun diagStatusUncovered(): DiagStatus =
    DiagStatus(
        stringResource(R.string.diag_status_uncovered),
        StatusColors.neutralAccent,
        StatusColors.neutralContainer(),
        false,
    )

@Composable
private fun CheckCard(
    r: DiagCard,
    index: Int = -1,
    count: Int = 1,
) {
    val status = if (r.uncovered) diagStatusUncovered() else diagStatus(r.outcome)
    var expanded by remember(r.name) { mutableStateOf(status.expandedByDefault) }
    val caretRotation by animateFloatAsState(if (expanded) 90f else 0f, label = "caret")
    GroupedCard(
        index = index,
        count = count,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = status.container,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = r.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(status.accent))
                    Text(
                        text = status.label,
                        color = status.accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).rotate(caretRotation),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                // For native checks the root ground-truth detail is shown next to the
                // app-view read — it is what the verdict is derived from (e.g. a
                // SELinux-blocked read reads as "nothing to leak" precisely because
                // "root: N routes, no VPN"). Java checks have no root diff → plain detail.
                val detailText =
                    r.groundTruthDetail?.let { gt -> "app:  ${r.detail}\nroot: $gt" } ?: r.detail
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}
