package dev.okhsunrog.vpnhide

/**
 * Per-layer backend health for a dashboard tile. Presence (Absent / Inactive)
 * is decided *before* the checks, so an unloaded backend can never render as a
 * leak or a verdict — it just reads "not active". This is the fix for the old
 * "Partial" that a not-loaded backend used to show from SELinux-only passes.
 */
sealed interface LayerStatus {
    /** No backend module installed for this layer. */
    data object Absent : LayerStatus

    /** Installed but not loaded this boot (needs a reboot / manager toggle). */
    data object Inactive : LayerStatus

    /** Active and measured. [hidden] = vectors the backend provably hid;
     * [leaks] = vectors it owns that still leak. */
    data class Active(
        val hidden: Int,
        val leaks: Int,
    ) : LayerStatus
}

enum class Verdict { Ok, Partial, Broken }

/**
 * Ok = nothing owned leaks. Partial = the backend hides some but an owned vector
 * still leaks (works, has a gap). Broken = active but hid nothing while leaking
 * (loaded yet dead). [hidden] must be a *measurement* (root differential), never
 * inferred from a clean probe — else Partial and Broken are indistinguishable.
 */
val LayerStatus.Active.verdict: Verdict
    get() =
        when {
            leaks == 0 -> Verdict.Ok
            hidden > 0 -> Verdict.Partial
            else -> Verdict.Broken
        }

/** Native-check ids the active backend owns — its checks whose expected hooks the
 * backend covers. One derivation shared by the tile summary and the unowned count. */
private fun ownedNativeCheckIds(
    backend: DisplayNativeBackend,
    kmodFilesystemHookInstalled: Boolean = false,
): Set<String> {
    val owned = ownedNativeHooks(backend.id, kmodFilesystemHookInstalled)
    return NATIVE_CHECKS.filter { it.coveredBy(owned) }.map { it.id }.toSet()
}

internal fun kmodFilesystemHookInstalled(statusRaw: String): Boolean =
    parseProtocolStatusBlock(statusRaw)?.hooks?.let { hooks -> KMOD_HOOKS.any(hooks::hasHook) } == true

/**
 * Native tile = health of the active backend, judged **only on vectors it owns**
 * (has a hook for). A leak on a not-owned vector (e.g. /proc/net/dev under a
 * kernel backend — no kernel hook exists) is out of scope for the tile; it
 * surfaces via the hero instead. Kernel backends (kmod/KPM) own kernel-hook
 * vectors; Zygisk owns zygisk-hook vectors.
 */
internal fun summarizeNativeLayer(
    backend: DisplayNativeBackend,
    outcomes: Map<String, CheckOutcome>,
    kmodFilesystemHookInstalled: Boolean = false,
): LayerStatus {
    if (backend.state is ModuleState.NotInstalled) return LayerStatus.Absent
    if (!moduleActive(backend.state)) return LayerStatus.Inactive
    val ownedIds = ownedNativeCheckIds(backend, kmodFilesystemHookInstalled)
    // Both counts are scoped to vectors this backend owns, so hidden and leaks
    // describe the same vector set — a cross-backend hidden (only possible if the
    // one-active-backend invariant ever breaks) can't mask an owned Broken verdict.
    val hidden = outcomes.count { (id, outcome) -> outcome is CheckOutcome.HiddenByBackend && id in ownedIds }
    val leaks = outcomes.count { (id, outcome) -> outcome is CheckOutcome.Leak && id in ownedIds }
    return LayerStatus.Active(hidden = hidden, leaks = leaks)
}

/**
 * Java tile from the LSPosed hook state + the Java check results. Java probes are
 * framework IPC with no root differential, so a clean result is taken as
 * hidden-by-LSPosed; a couple of failing probes read as Partial ("leaking n"),
 * not a blanket "not working".
 */
internal fun summarizeJavaLayer(
    lsposedActive: Boolean,
    javaChecks: List<CheckResult>,
): LayerStatus {
    if (!lsposedActive) return LayerStatus.Inactive
    // Same shape as the native tile: hidden/leaks read off the who-hid-it outcome
    // (set at construction via javaCheck), so Partial vs Broken is a measurement.
    return LayerStatus.Active(
        hidden = javaChecks.count { it.outcome is CheckOutcome.HiddenByBackend },
        leaks = javaChecks.count { it.outcome is CheckOutcome.Leak },
    )
}

/** Native leaks on vectors the active backend does NOT own — surfaced via the
 * hero (a warning), not the tile. Zero when the backend covers everything that
 * leaks, or when SELinux is masking (those are hidden_selinux, not leaks). */
internal fun unownedNativeLeaks(
    backend: DisplayNativeBackend,
    outcomes: Map<String, CheckOutcome>,
    kmodFilesystemHookInstalled: Boolean = false,
): Int {
    if (backend.state !is ModuleState.Installed || !moduleActive(backend.state)) return 0
    val ownedIds = ownedNativeCheckIds(backend, kmodFilesystemHookInstalled)
    return outcomes.count { (id, outcome) -> outcome is CheckOutcome.Leak && id !in ownedIds }
}
