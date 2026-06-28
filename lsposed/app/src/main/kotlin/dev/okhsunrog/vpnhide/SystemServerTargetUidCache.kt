package dev.okhsunrog.vpnhide

import android.os.SystemClock
import java.io.File

/**
 * Numeric target UIDs consumed by system_server hooks.
 *
 * FileObserver is only a fast invalidation path: on some devices /data/system
 * writes are not reported to the hooked process reliably, so the cache also
 * validates the backing file's cheap stat fingerprint on every read.
 */
internal class SystemServerTargetUidCache(
    private val file: File = File(SS_UIDS_FILE),
) {
    private companion object {
        const val STAT_CHECK_INTERVAL_MS = 1_000L
    }

    private data class Fingerprint(
        val exists: Boolean,
        val lastModified: Long,
        val length: Long,
    )

    private data class Cache(
        val fingerprint: Fingerprint,
        val uids: Set<Int>,
        val nextStatCheckUptimeMs: Long,
    )

    @Volatile private var cache: Cache? = null
    private val lock = Any()

    fun load(): Set<Int> {
        val now = SystemClock.uptimeMillis()
        cache?.let { cached ->
            if (now < cached.nextStatCheckUptimeMs) return cached.uids
        }

        synchronized(lock) {
            val lockedNow = SystemClock.uptimeMillis()
            cache?.let { cached ->
                if (lockedNow < cached.nextStatCheckUptimeMs) return cached.uids
                val fingerprint = fingerprint()
                if (cached.fingerprint == fingerprint) {
                    val refreshed = cached.withNextCheck(lockedNow)
                    cache = refreshed
                    return refreshed.uids
                }
            }

            val result = readUids()
            val loadedFingerprint = fingerprint()
            HookLog.i("VpnHide: system_server loaded ${result.size} target UIDs: $result")
            cache = Cache(loadedFingerprint, result, nextStatCheck(lockedNow))
            return result
        }
    }

    fun invalidate() {
        cache = null
    }

    private fun Cache.withNextCheck(now: Long): Cache = copy(nextStatCheckUptimeMs = nextStatCheck(now))

    private fun nextStatCheck(now: Long): Long = now + STAT_CHECK_INTERVAL_MS

    private fun readUids(): Set<Int> =
        try {
            if (!file.exists()) {
                emptySet()
            } else {
                // The file is a `vpnhide 1 config` snapshot (docs/protocol.md):
                // the LSPosed channel speaks the same wire as every other
                // backend. We act on target *presence* (UID is the key, §4.3);
                // LSPosed owns no registry hook bits yet, so the per-hook mask
                // is ignored (§6 note). An invalid/old-format file parses to
                // null ⇒ no targets, rewritten on the next boot or Save.
                Protocol
                    .parseConfig(file.readText())
                    ?.targets
                    ?.map { it.uid.toInt() }
                    ?.toSet()
                    ?: emptySet()
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to read UIDs: ${t.message}")
            emptySet()
        }

    private fun fingerprint(): Fingerprint =
        try {
            if (!file.exists()) {
                Fingerprint(exists = false, lastModified = 0L, length = 0L)
            } else {
                Fingerprint(
                    exists = true,
                    lastModified = file.lastModified(),
                    length = file.length(),
                )
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to stat UIDs file: ${t.message}")
            Fingerprint(exists = false, lastModified = 0L, length = 0L)
        }
}
