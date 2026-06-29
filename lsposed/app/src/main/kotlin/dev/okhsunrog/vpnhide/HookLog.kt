package dev.okhsunrog.vpnhide

import android.os.FileObserver
import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Log wrapper gated by a filesystem flag set from the app. Used by LSPosed
 * hooks running inside `system_server`, where we don't have access to the
 * app's SharedPreferences.
 *
 * Source of truth is the canonical JSON config. We read it on [install] and via an
 * inotify watcher so a flip takes effect without restarting system_server.
 *
 * The logcat sink makes Settings → Debugging → Debug logging visible through
 * ordinary bug-report captures. The Xposed sink is kept for framework UIs /
 * files that expose `XposedBridge.log` separately.
 *
 * Only per-request / hot-path logs should go through [i]. Hook install failures
 * and other one-time errors use [e], which always prints — losing those would
 * make diagnosing "hooks didn't attach" reports impossible.
 */
internal object HookLog {
    private const val LOGCAT_TAG = "VpnHide-LSPosed"

    @Volatile private var enabled: Boolean = false

    @Volatile private var watcher: FileObserver? = null

    fun install() {
        reload()
        if (watcher != null) return
        // MODIFY covers manual in-place edits; MOVED_TO/CLOSE_WRITE from
        // watchSystemDataDir cover the app's atomic JSON replacement.
        watcher =
            watchSystemDataDir(extraEvents = FileObserver.MODIFY) { path ->
                if (path == "vpnhide_config.json") {
                    SystemServerConfigCache.invalidate()
                    reload()
                }
            }
    }

    private fun reload() {
        enabled = SystemServerConfigCache.load().debug
    }

    fun i(msg: String) {
        if (!enabled) return
        Log.i(LOGCAT_TAG, msg)
        XposedBridge.log(msg)
    }

    /** Always prints — used for install failures and other diagnostics we can't afford to lose. */
    fun e(msg: String) {
        Log.e(LOGCAT_TAG, msg)
        XposedBridge.log(msg)
    }
}
