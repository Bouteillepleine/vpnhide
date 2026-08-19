package dev.okhsunrog.vpnhide

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = LogTags.TEST

internal data class DiagnosticFileEntry(
    val name: String,
    val file: File,
)

// ==========================================================================
//  Debug export — one canonical JSON
// ==========================================================================

/**
 * Collect the whole app state into one [VpnHideState] and write it as a single
 * `vpnhide_debug_<ts>.json`. Module/liveness state is derived from the same
 * [RootSnapshotCache] the live dashboard uses (so the dump can't disagree with the
 * screen), the diagnostics run is folded into the canonical [DiagnosticReport], and
 * the heavy forensic captures ride along as raw sections + log blobs. The document
 * always serializes — partial-capture failures are recorded in `errors`, never lost.
 */
@Suppress("LongMethod")
internal suspend fun exportDebugJson(
    cm: ConnectivityManager,
    context: Context,
    selfNeedsRestart: Boolean,
): File? =
    withContext(Dispatchers.IO) {
        // Force-enable debug logging across app, system_server and active native
        // sinks while the capture runs; the session records what it applied/restored.
        val loggingSession = beginDebugCaptureLogging()
        var restoreAttempted = false
        val errors = mutableListOf<String>()
        try {
            val counterBaseline = collectHookCounterSnapshot()
            // Clear dmesg so we only capture output from the hooks the checks fire.
            suExec("dmesg -c > /dev/null 2>&1")
            val checkResults = runAllChecks(cm, context)
            val (_, dmesg) = suExec("dmesg 2>/dev/null")

            // Authoritative module/liveness state — the SAME snapshot the dashboard
            // derives from. This is what fixes the old export path silently reading
            // "inactive" from a shell that never emitted proc_exists/ports_chain.
            val rootSnapshot =
                runCatching { RootSnapshotCache.refresh() }
                    .getOrElse {
                        errors += "root snapshot failed: ${it.message}"
                        RootSnapshot(emptyMap())
                    }
            val shellSnapshot = collectDebugShellSnapshot()
            if (shellSnapshot.exitCode != 0) errors += "debug shell exit=${shellSnapshot.exitCode}"
            shellSnapshot.sections["debug_snapshot_truncated"]?.let { errors += "snapshot truncated at: $it" }

            val logcat = captureDebugLogcat()
            val restore = restoreDebugCaptureLogging(loggingSession)
            restoreAttempted = true
            val session = loggingSession.withRestore(restore)

            val gate =
                resolveDiagnosticGate(
                    vpnActive = isVpnActive(),
                    selfRouted = GroundTruthProbe.selfRoutedThroughVpn(context),
                    selfNeedsRestart = selfNeedsRestart,
                )
            val state =
                buildVpnHideState(
                    context = context,
                    captureKind = "debug",
                    generatedAt = isoNow(),
                    selfNeedsRestart = selfNeedsRestart,
                    rootSnapshot = rootSnapshot,
                    shellSnapshot = shellSnapshot,
                    gate = gate,
                    checkResults = checkResults,
                    dmesg = dmesg,
                    logcat = logcat.ifEmpty { "(no logcat entries)" },
                    bootLsposedLogcat = captureBootLsposedLogcat(),
                    lsposedConfigDb = buildLsposedConfigText(context),
                    hookReport = buildHookDiagnosticsText(context, shellSnapshot, counterBaseline),
                    debugCapture = session.toDebugCaptureInfo(),
                    errors = errors,
                )

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val jsonFile = File(context.cacheDir, "vpnhide_debug_$timestamp.json")
            jsonFile.writeText(state.toJson())
            jsonFile
        } catch (c: CancellationException) {
            // The finally below still restores debug logging; don't mask cancellation
            // as a normal "export failed" — rethrow for structured concurrency.
            throw c
        } catch (e: Exception) {
            VpnHideLog.e(TAG, "Debug export failed", e)
            null
        } finally {
            if (!restoreAttempted) {
                restoreDebugCaptureLogging(loggingSession)
            }
        }
    }

/** ISO-8601 timestamp for [VpnHideState.generatedAt] (the serializer has no clock). */
internal fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

/**
 * Pack a small ZIP: named text entries + raw file entries. Retained for the two
 * captures with a non-text payload — the full-logcat recorder (a multi-MB raw log)
 * and the kernel-image export (binary partition images) — each of which carries the
 * canonical `state.json` alongside its payload. The plain debug export is a single
 * `.json` and does not use this.
 */
internal fun writeDiagnosticZip(
    zipFile: File,
    textEntries: Map<String, String>,
    fileEntries: List<DiagnosticFileEntry> = emptyList(),
) {
    ZipOutputStream(zipFile.outputStream()).use { zos ->
        for ((name, content) in textEntries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
        for ((name, file) in fileEntries) {
            zos.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

private fun captureDebugLogcat(): String {
    val tags =
        listOf(
            "VPNHideTest:*",
            "VpnHide:*",
            "VpnHide-Dashboard:*",
            "VpnHide-Startup:*",
            "VpnHide-LSPosed:*",
            "VpnHide-Diag:*",
            "VpnHide-Logcat:*",
            "VpnHide-Update:*",
            "VpnHideAgentBridge:*",
            "vpnhide:*",
            "vpnhide_ports:*",
            "vpnhide-zygisk:*",
            "shadowhook_tag:*",
        ).joinToString(" ")
    val (exit, output) = suExec("logcat -d -b all -v threadtime -s $tags 2>/dev/null")
    return if (exit == 0) output else "(logcat failed: exit=$exit)\n$output"
}

internal fun captureBootLsposedLogcat(): String {
    val (exit, output) = suExec(buildBootLsposedLogcatCommand(), timeoutSec = 15)
    return buildString {
        appendLine("commandExit=$exit")
        appendLine("source=logcat -d -b all -v threadtime")
        appendLine("scope=best_effort_current_ring_buffer")
        appendLine("note=Contains boot-time LSPosed/Vector context only if the logcat ring buffer has not rotated yet.")
        appendLine("patterns=${BOOT_LSPOSED_LOGCAT_PATTERNS.joinToString(",")}")
        appendLine()
        appendLine(output.ifBlank { "(no LSPosed/Vector boot logcat entries in current buffers)" }.trimEnd())
    }.trimEnd()
}

internal fun buildBootLsposedLogcatCommand(): String {
    val pattern = BOOT_LSPOSED_LOGCAT_PATTERNS.joinToString("|")
    return """
        logcat -d -b all -v threadtime 2>/dev/null |
          grep -Ei '$pattern' |
          tail -2000 || true
        """.trimIndent()
}

private val BOOT_LSPOSED_LOGCAT_PATTERNS =
    listOf(
        LogTags.LSPOSED,
        "LSPosed-Bridge",
        "VectorNative",
        "VectorBridge",
        "LSPosedService",
        "LSPlt",
        "LSPHooker",
        "LSPosedBridge",
        "Xposed",
        "org[.]lsposed",
        "lspd",
        "modules_config",
        "dev[.]okhsunrog[.]vpnhide",
    )

internal fun appVersionText(context: Context): String =
    try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val code =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toString()
            }
        "${pInfo.versionName} ($code)"
    } catch (_: Exception) {
        "(unknown)"
    }

internal fun buildLsposedConfigText(context: Context): String {
    val config =
        runCatching {
            readLsposedConfig(context, context.packageName)
        }.getOrNull()
            ?: return "(not available)"
    return when (config) {
        LsposedConfig.ModuleNotConfigured -> {
            "module=not_configured"
        }

        LsposedConfig.Disabled -> {
            "module=disabled"
        }

        is LsposedConfig.Enabled -> {
            buildString {
                appendLine("module=enabled")
                appendLine("hasSystemFramework=${config.hasSystemFramework}")
                appendLine("scope=${config.entries.joinToString()}")
                appendLine("extraScope=${config.extraEntries.joinToString()}")
            }.trimEnd()
        }
    }
}
