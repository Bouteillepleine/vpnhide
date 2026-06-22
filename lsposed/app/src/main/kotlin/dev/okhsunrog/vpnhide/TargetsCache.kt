package dev.okhsunrog.vpnhide

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Per-screen protection state from root-owned files and package
 * manager lookups, cached once for the lifetime of the app session.
 *
 * Without this cache, every tab switch into Protection triggered 3-4
 * sequential `suExec` roundtrips per screen. Root shell roundtrips
 * are ~50-100ms each on most devices, so a single tab switch added
 * hundreds of milliseconds of "loading" time even after AppListCache
 * made the package list itself instant. Bundling every read into a
 * single batched shell invocation + caching the result means subsequent
 * tab switches render immediately from memory.
 *
 * Invalidated when:
 * - The user taps Save on any Protection screen (target files have
 *   just been overwritten — need a fresh read next time).
 * - The user taps the top-bar Refresh button on Protection.
 */
internal data class TargetsSnapshot(
    val kmodModuleInstalled: Boolean,
    val zygiskModuleInstalled: Boolean,
    val portsModuleInstalled: Boolean,
    val kmodTargets: Set<String>,
    val zygiskTargets: Set<String>,
    val lsposedTargets: Set<String>,
    val hiddenPkgs: Set<String>,
    val observerUids: Set<Int>,
    val portsObservers: Set<String>,
    val uidToPkg: Map<Int, String>,
) {
    /** Observer UIDs resolved back to current package names via
     * `pm list packages -U`. UIDs that no longer map to an installed
     * package (e.g. after an uninstall) silently drop out.
     */
    val observerNames: Set<String>
        get() = observerUids.mapNotNull { uidToPkg[it] }.toSet()
}

internal object TargetsCache {
    private val _snapshot = MutableStateFlow<TargetsSnapshot?>(null)
    val snapshot: StateFlow<TargetsSnapshot?> = _snapshot.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var inflight: Job? = null

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
    ) {
        if (_snapshot.value != null || _error.value != null || inflight?.isActive == true) return
        inflight = scope.launch { reload(context.applicationContext) }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
    ) {
        inflight?.cancel()
        RootSnapshotCache.invalidate()
        _error.value = null
        inflight = scope.launch { reload(context.applicationContext, forceRootRefresh = true) }
    }

    /** Drop the cached snapshot so the next subscriber triggers a
     * fresh load. Save handlers call this because they just mutated
     * one of the files this cache mirrors.
     */
    fun invalidate() {
        _snapshot.value = null
        _error.value = null
        RootSnapshotCache.invalidate()
    }

    private suspend fun reload(
        @Suppress("UNUSED_PARAMETER") appContext: Context,
        forceRootRefresh: Boolean = false,
    ) {
        _loading.value = true
        try {
            StartupTrace.mark("targets_cache_start")
            val rootSnapshot =
                if (forceRootRefresh) {
                    RootSnapshotCache.refresh()
                } else {
                    RootSnapshotCache.getOrLoad()
                }
            _snapshot.value = parseTargetsSnapshot(rootSnapshot)
            _error.value = null
            StartupTrace.mark("targets_cache_done")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StartupTrace.mark("targets_cache_failed")
            _error.value = e.message ?: e.javaClass.simpleName
            VpnHideLog.w("VpnHide-Targets", "targets cache reload failed: ${e.message}", e)
        } finally {
            _loading.value = false
        }
    }
}

internal fun parseTargetsSnapshot(rootSnapshot: RootSnapshot): TargetsSnapshot {
    val sections = rootSnapshot.sections

    fun nonEmptyLines(raw: String?): Set<String> =
        raw
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            ?.toSet() ?: emptySet()

    val portsInstalled = sections["ports_prop"]?.isNotBlank() == true
    val observerUids = nonEmptyLines(sections["observer_uids"]).mapNotNull { it.toIntOrNull() }.toSet()

    // With `--user all`, multi-profile packages report comma-separated
    // UIDs: `package:com.android.chrome uid:10187,1010187`. Each UID
    // becomes its own entry in the reverse map so observer lookups
    // from any profile resolve back to the same package name.
    val pmLine = Regex("^package:(\\S+) uid:(\\S+)")
    val uidToPkg = mutableMapOf<Int, String>()
    sections["pm_packages"]?.lines()?.forEach { line ->
        val m = pmLine.find(line) ?: return@forEach
        val pkg = m.groupValues[1]
        for (id in m.groupValues[2].split(',')) {
            id.toIntOrNull()?.let { uidToPkg[it] = pkg }
        }
    }

    return TargetsSnapshot(
        kmodModuleInstalled = sections["kmod_module_dir"]?.trim() == "1",
        zygiskModuleInstalled = sections["zygisk_module_dir"]?.trim() == "1",
        portsModuleInstalled = portsInstalled,
        kmodTargets = nonEmptyLines(sections["kmod_targets"]),
        zygiskTargets = nonEmptyLines(sections["zygisk_targets"]),
        lsposedTargets = nonEmptyLines(sections["lsposed_targets"]),
        hiddenPkgs = nonEmptyLines(sections["hidden_pkgs"]),
        observerUids = observerUids,
        portsObservers = nonEmptyLines(sections["ports_observers"]),
        uidToPkg = uidToPkg,
    )
}
