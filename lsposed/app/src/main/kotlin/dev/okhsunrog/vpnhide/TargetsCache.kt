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
 * - The user taps Save on Protection (canonical config has just been
 *   overwritten — need a fresh read next time).
 * - The user taps the top-bar Refresh button on Protection.
 */
internal data class TargetsSnapshot(
    val kmodModuleInstalled: Boolean,
    val kpmModuleInstalled: Boolean,
    val zygiskModuleInstalled: Boolean,
    val portsModuleInstalled: Boolean,
    val nativeTargets: Set<String>,
    val lsposedTargets: Set<String>,
    val hiddenPkgs: Set<String>,
    val observerUids: Set<Int>,
    val portsObservers: Set<String>,
    val uidToPkg: Map<Int, String>,
    val canonicalConfig: CanonicalConfig?,
    val apatchSuperkeySaved: Boolean = false,
    val activeNativeBackendId: NativeBackendId? = null,
    /** Exact package → UID projection from the shared per-user package
     * inventory. The picker uses it to enforce native capacity before Save,
     * including profiles and shared UIDs. */
    val packageUids: Map<String, List<Int>> = emptyMap(),
) {
    /** True if any native backend is installed (kmod / KPM / Zygisk). The
     * picker's "N" toggle is meaningful only when at least one is present. */
    val anyNativeInstalled: Boolean
        get() = kmodModuleInstalled || kpmModuleInstalled || zygiskModuleInstalled

    val displayNativeBackendId: NativeBackendId?
        get() =
            activeNativeBackendId
                ?: when {
                    kmodModuleInstalled -> NativeBackendId.Kmod
                    kpmModuleInstalled -> NativeBackendId.Kpm
                    zygiskModuleInstalled -> NativeBackendId.Zygisk
                    else -> null
                }

    val nativeHookFamily: NativeHookFamily
        get() = nativeHookFamilyFor(displayNativeBackendId)

    /** Observer UIDs resolved back to current package names via
     * `pm list packages -U`. UIDs that no longer map to an installed
     * package (e.g. after an uninstall) silently drop out.
     */
    val observerNames: Set<String>
        get() = observerUids.mapNotNull { uidToPkg[it] }.toSet()
}

internal object TargetsCache : StateCache<TargetsSnapshot>(
    traceName = "targets_cache",
    logTag = LogTags.TARGETS,
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

    override suspend fun load(
        @Suppress("UNUSED_PARAMETER") force: Boolean,
    ): TargetsSnapshot {
        val rootSnapshot = RootSnapshotCache.getOrLoad()
        requireNonEmptyPackageInventory(rootSnapshot.sections)
        return parseTargetsSnapshot(rootSnapshot)
    }
}

internal fun parseTargetsSnapshot(rootSnapshot: RootSnapshot): TargetsSnapshot {
    val sections = rootSnapshot.sections
    val portsInstalled = sections["ports_prop"]?.isNotBlank() == true
    val canonical = runCatching { parseCanonicalConfig(sections["canonical_config"].orEmpty()) }.getOrNull()
    val desired = canonical ?: CanonicalConfig()

    // The inventory contains one block per Android user. Each resolved UID
    // becomes its own reverse-map entry so observer lookups from any profile
    // resolve back to the same package name.
    val uidToPkg = mutableMapOf<Int, String>()
    val pkgToUids = parsePackageUidMap(sections["pm_packages"].orEmpty())
    pkgToUids.forEach { (pkg, uids) ->
        uids.forEach { uidToPkg[it] = pkg }
    }

    fun uidsFor(pkgs: Set<String>): Set<Int> = pkgs.flatMap { pkgToUids[it].orEmpty() }.toSet()
    val activeNativeBackendId = detectNativeBackendStates(sections).activeId

    val javaTargets = desired.apps.filterValues { it.java }.keys
    val nativeTargets = desired.apps.filterValues { it.native.enabled }.keys
    val observerNames = desired.apps.filterValues { it.appHiding }.keys
    val hiddenPkgs = desired.apps.filterValues { it.hidden }.keys
    val portsObservers = desired.apps.filterValues { it.ports }.keys
    return TargetsSnapshot(
        kmodModuleInstalled = sections["kmod_module_dir"]?.trim() == "1",
        kpmModuleInstalled = sections["kpm_module_dir"]?.trim() == "1",
        zygiskModuleInstalled = sections["zygisk_module_dir"]?.trim() == "1",
        portsModuleInstalled = portsInstalled,
        nativeTargets = nativeTargets,
        lsposedTargets = javaTargets,
        hiddenPkgs = hiddenPkgs,
        observerUids = uidsFor(observerNames),
        portsObservers = portsObservers,
        uidToPkg = uidToPkg,
        canonicalConfig = canonical,
        apatchSuperkeySaved = sections["superkey_saved"]?.trim() == "1",
        activeNativeBackendId = activeNativeBackendId,
        packageUids = pkgToUids,
    )
}
