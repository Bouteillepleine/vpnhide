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
