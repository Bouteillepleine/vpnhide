package dev.okhsunrog.vpnhide

// Pure logic for the unified app picker's save path — data in, data out, no
// Android deps, unit-tested (see AppPickerDataTest), per lsposed/AGENTS.md.

/**
 * The hidden-package set to persist on Save. This preserves manual hidden
 * packages and auto-detected VPN apps, and always includes [selfPkg] (managed
 * invisibly, never shown in the picker).
 *
 * Hidden and app-hiding observer roles are **mutually exclusive**: a package
 * that is both crashes the framework on a self-lookup (an observer querying its
 * own PackageInfo gets a NameNotFoundException when it is also hidden). So
 * [observers] win — any package the user just marked "A" is dropped from the
 * hidden set. Self is never an observer (the picker never lists it), so it
 * always stays hidden.
 */
internal fun resolveHiddenPackages(
    existing: Set<String>,
    observers: Set<String>,
    selfPkg: String,
): List<String> = (existing.filterNot { it in observers } + selfPkg).distinct().sorted()

internal data class AppAutoHideSignal(
    val packageName: String,
    val declaresVpnService: Boolean = false,
    val nameContainsVpn: Boolean = false,
)

internal fun resolveAutoHiddenPackages(
    signals: Collection<AppAutoHideSignal>,
    settings: CanonicalSettings,
    selfPkg: String,
): Set<String> =
    signals
        .asSequence()
        .filter { it.packageName != selfPkg }
        .filter {
            (settings.autoHideVpnServices && it.declaresVpnService) ||
                (settings.autoHideVpnName && it.nameContainsVpn)
        }.mapTo(sortedSetOf()) { it.packageName }

internal data class AppRoleSelection(
    val packageName: String,
    val java: Boolean = false,
    val native: Boolean = false,
    val appHiding: Boolean = false,
    val ports: Boolean = false,
)

internal fun buildCanonicalConfigForAppPickerSave(
    debug: Boolean,
    selfPkg: String,
    selections: Collection<AppRoleSelection>,
    snapshot: TargetsSnapshot?,
    autoHideSignals: Collection<AppAutoHideSignal> = emptyList(),
): CanonicalConfig {
    val base = canonicalBaseForSave(debug, snapshot)
    val visiblePkgs = selections.mapTo(mutableSetOf()) { it.packageName }

    fun preserved(predicate: (CanonicalApp) -> Boolean): Set<String> =
        base.apps
            .filter { (pkg, app) -> pkg !in visiblePkgs && predicate(app) }
            .keys

    val javaPkgs = preserved { it.java } + selections.selectedPkgs { it.java } + selfPkg
    val nativePkgs = preserved { it.native.enabled } + selections.selectedPkgs { it.native } + selfPkg
    val observerPkgs = preserved { it.appHiding } + selections.selectedPkgs { it.appHiding }
    val portsPkgs = preserved { it.ports } + selections.selectedPkgs { it.ports }
    val manualHiddenPkgs = base.apps.filterValues { it.hidden }.keys - base.settings.autoHiddenPackages
    val hiddenPkgs =
        resolveHiddenPackages(
            existing = manualHiddenPkgs,
            observers = observerPkgs,
            selfPkg = selfPkg,
        )

    val canonical =
        buildCanonicalConfig(
            debug = debug,
            javaPkgs = javaPkgs,
            nativePkgs = nativePkgs,
            hiddenPkgs = hiddenPkgs,
            observerPkgs = observerPkgs,
            portsPkgs = portsPkgs,
            existing = base,
        )
    return applyAutoHiddenPackages(
        config = canonical,
        selfPkg = selfPkg,
        signals = autoHideSignals,
    )
}

internal fun applyAutoHiddenPackages(
    config: CanonicalConfig,
    selfPkg: String,
    signals: Collection<AppAutoHideSignal>,
): CanonicalConfig {
    val observerPkgs = config.apps.filterValues { it.appHiding }.keys
    val manualHiddenPkgs = config.apps.filterValues { it.hidden }.keys - config.settings.autoHiddenPackages
    val autoHiddenPkgs = resolveAutoHiddenPackages(signals, config.settings, selfPkg)
    val effectiveAutoHiddenPkgs = autoHiddenPkgs - observerPkgs
    val hiddenPkgs =
        resolveHiddenPackages(
            existing = manualHiddenPkgs + effectiveAutoHiddenPkgs,
            observers = observerPkgs,
            selfPkg = selfPkg,
        )
    return buildCanonicalConfig(
        debug = config.debug,
        javaPkgs = config.apps.filterValues { it.java }.keys,
        nativePkgs = config.apps.filterValues { it.native.enabled }.keys,
        hiddenPkgs = hiddenPkgs,
        observerPkgs = observerPkgs,
        portsPkgs = config.apps.filterValues { it.ports }.keys,
        existing = config.copy(settings = config.settings.copy(autoHiddenPackages = effectiveAutoHiddenPkgs.toSortedSet())),
    )
}

private fun canonicalBaseForSave(
    debug: Boolean,
    snapshot: TargetsSnapshot?,
): CanonicalConfig =
    when {
        snapshot?.canonicalConfig != null -> snapshot.canonicalConfig.copy(debug = debug)
        snapshot != null -> buildCanonicalConfigFromTargetsSnapshot(snapshot, debug = debug)
        else -> CanonicalConfig(debug = debug)
    }

private fun Collection<AppRoleSelection>.selectedPkgs(predicate: (AppRoleSelection) -> Boolean): Set<String> =
    filter(predicate).mapTo(mutableSetOf()) { it.packageName }
