package dev.okhsunrog.vpnhide

// Pure logic for the unified app picker's save path — data in, data out, no
// Android deps, unit-tested (see AppPickerDataTest), per lsposed/AGENTS.md.

/**
 * The hidden-package set to persist on Save. Real auto-detection of sensitive
 * apps is a follow-up; for now this preserves the [existing] hidden set (so a
 * migrating user doesn't lose a prior config) and always includes [selfPkg]
 * (managed invisibly, never shown in the picker).
 *
 * Hidden and observer are **mutually exclusive**: a package that is both crashes
 * the framework on a self-lookup (an observer querying its own PackageInfo gets
 * a NameNotFoundException when it is also hidden — the bug the old AppHidingScreen
 * guarded against). So [observers] win — any package the user just marked "A" is
 * dropped from the hidden set. Self is never an observer (the picker never lists
 * it), so it always stays hidden.
 */
internal fun resolveHiddenPackages(
    existing: Set<String>,
    observers: Set<String>,
    selfPkg: String,
): List<String> = (existing.filterNot { it in observers } + selfPkg).distinct().sorted()

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
    val hiddenPkgs =
        resolveHiddenPackages(
            existing = base.apps.filterValues { it.hidden }.keys,
            observers = observerPkgs,
            selfPkg = selfPkg,
        )

    return buildCanonicalConfig(
        debug = debug,
        javaPkgs = javaPkgs,
        nativePkgs = nativePkgs,
        hiddenPkgs = hiddenPkgs,
        observerPkgs = observerPkgs,
        portsPkgs = portsPkgs,
        existing = base,
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
