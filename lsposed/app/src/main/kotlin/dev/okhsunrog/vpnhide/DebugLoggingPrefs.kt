package dev.okhsunrog.vpnhide

import android.content.Context

/**
 * Persisted "debug logging" preference and its propagation. Debug is now folded
 * into the control-config wire (`debug` line, docs/protocol.md §4.3), so the
 * kmod and Zygisk learn it from their config snapshot rather than from a private
 * debug node/file (those are gone). The sinks are:
 *
 *  - App Kotlin code → [VpnHideLog.enabled] (volatile).
 *  - system_server LSPosed hooks → [SS_DEBUG_LOGGING_FILE], the single canonical
 *    persistent flag. The hook ([HookLog]) inotify-watches it (a flip takes
 *    effect immediately for already-running apps), and the boot scripts read it
 *    as the source for the `debug` line they emit into each backend's config.
 *  - kmod (`/proc/vpnhide_ctl`) + Zygisk (module-dir config) → re-emitted via
 *    [ConfigChannels.reconcileCommand] with the new flag, so a running native
 *    backend picks it up without a Save (Zygisk takes effect on the target
 *    app's next restart, as targets always have).
 */
private const val PREFS_NAME = "vpnhide_prefs"
private const val KEY_DEBUG_LOGGING = "debug_logging"

internal const val SS_DEBUG_LOGGING_FILE = "/data/system/vpnhide_debug_logging"

/** Default is OFF — stealth-first matches the project's anti-detection stance. */
internal fun isEnabledInPrefs(context: Context): Boolean =
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_DEBUG_LOGGING, false)

/**
 * Flip the persisted preference and propagate it to every sink. Runs
 * SU commands, so callers should invoke from a background dispatcher.
 * Use this for the user-facing toggle in Diagnostics.
 */
internal fun setDebugLoggingEnabled(
    context: Context,
    enabled: Boolean,
) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DEBUG_LOGGING, enabled)
        .apply()
    applyDebugLoggingRuntime(enabled)
    RootSnapshotCache.invalidate()
    DashboardCache.invalidate()
}

/**
 * Push [enabled] to the runtime sinks only, without touching
 * SharedPreferences. Used by diagnostic capture paths (Collect debug
 * log button + [LogcatRecorder]) that temporarily force-enable logging
 * for the duration of a capture and then restore the user's persisted
 * choice — without this, the user-facing toggle would visually flip
 * under the user while they collected a bug report.
 */
internal fun applyDebugLoggingRuntime(enabled: Boolean) {
    VpnHideLog.enabled = enabled
    writeDebugFlagFiles(enabled)
}

private fun writeDebugFlagFiles(enabled: Boolean) {
    val value = if (enabled) "1" else "0"
    val parts = mutableListOf<String>()

    // The single canonical persistent flag: /data/system, labelled
    // system_data_file so system_server (and nothing else) can read it. The
    // LSPosed hook watches it directly; the boot scripts read it as the source
    // for each config's `debug` line. `chcon || true` so devices without chcon
    // still land on a working file at the kernel-default label.
    parts += "echo '$value' > $SS_DEBUG_LOGGING_FILE"
    parts += "chmod 644 $SS_DEBUG_LOGGING_FILE 2>/dev/null"
    parts += "chcon u:object_r:system_data_file:s0 $SS_DEBUG_LOGGING_FILE 2>/dev/null || true"

    // Re-emit the runtime config with the new flag so a running kmod/Zygisk
    // backend picks it up (debug is folded into the config, §4.3). Needs the
    // current targets; if the snapshot isn't loaded yet, the flag file alone is
    // written and the next reconcile/Save carries the flag into the channels.
    TargetsCache.snapshot.value?.let { snap ->
        parts += ConfigChannels.reconcileCommand(snap, enabled)
    }
    parts += "true"

    // Batched into one su invocation to keep the toggle UI snappy — each
    // round-trip is ~50-100ms. Channels whose component isn't installed/loaded
    // are skipped by the guards inside the config-write parts.
    suExec(parts.joinToString(" ; "))
}
