package dev.okhsunrog.vpnhide

import android.os.FileObserver
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * [XposedBridge.log] wrapper gated by a filesystem flag set from the app.
 * Used by LSPosed hooks running inside `system_server`, where we don't
 * have access to the app's SharedPreferences.
 *
 * Source of truth is [SS_DEBUG_LOGGING_FILE] — the app rewrites it when
 * the user toggles the setting. We read it on [install] and via an
 * inotify watcher so a flip takes effect without restarting system_server.
 *
 * Only per-request / hot-path logs should go through [i]. Hook install
 * failures and other one-time errors use [e], which always prints —
 * losing those would make diagnosing "hooks didn't attach" reports
 * impossible.
 */
internal object HookLog {
    @Volatile private var enabled: Boolean = false

    @Volatile private var watcher: FileObserver? = null

    fun install() {
        reload()
        if (watcher != null) return
        // MODIFY is enabled here (unlike the UID / hidden-pkg watchers): this
        // flag is a single byte, so a transient mid-write read is harmless and
        // we'd rather react to an in-place `echo > file` immediately.
        watcher =
            watchSystemDataDir(extraEvents = FileObserver.MODIFY) { path ->
                if (path == "vpnhide_debug_logging") reload()
            }
    }

    private fun reload() {
        enabled =
            try {
                File(SS_DEBUG_LOGGING_FILE).readText().trim() == "1"
            } catch (_: Throwable) {
                false
            }
    }

    fun i(msg: String) {
        if (enabled) XposedBridge.log(msg)
    }

    /** Always prints — used for install failures and other diagnostics we can't afford to lose. */
    fun e(msg: String) {
        XposedBridge.log(msg)
    }
}
