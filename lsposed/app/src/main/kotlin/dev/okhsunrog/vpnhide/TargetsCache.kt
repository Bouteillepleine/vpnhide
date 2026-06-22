package dev.okhsunrog.vpnhide

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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

internal object TargetsCache : StateCache<TargetsSnapshot>(
    traceName = "targets_cache",
    logTag = "VpnHide-Targets",
) {
    val snapshot: StateFlow<TargetsSnapshot?> get() = value

    // The snapshot is parsed entirely from the shared RootSnapshotCache, so
    // `load` needs no context — the parameter is kept only for call-site
    // symmetry with the other caches.
    fun ensureLoaded(
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER") context: Context,
    ) = ensure(scope)

    fun refresh(
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER") context: Context,
    ) {
        RootSnapshotCache.invalidate()
        forceRefresh(scope)
    }

    fun refreshAfterSave(
        scope: CoroutineScope,
        context: Context,
    ) {
        DashboardCache.invalidate()
        refresh(scope, context)
    }

    /** Drop the cached snapshot (and the shared root snapshot it derives
     * from) so the next subscriber triggers a fresh load. Use
     * [refreshAfterSave] when a Protection save should also invalidate
     * Dashboard counts.
     */
    override fun invalidate() {
        super.invalidate()
        RootSnapshotCache.invalidate()
    }

    override suspend fun load(force: Boolean): TargetsSnapshot {
        val rootSnapshot =
            if (force) RootSnapshotCache.refresh() else RootSnapshotCache.getOrLoad()
        return parseTargetsSnapshot(rootSnapshot)
    }
}

internal fun parseTargetsSnapshot(rootSnapshot: RootSnapshot): TargetsSnapshot {
    val sections = rootSnapshot.sections

    fun nonEmptyLines(raw: String?): Set<String> = raw?.let { parseConfigLines(it).toSet() } ?: emptySet()

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
