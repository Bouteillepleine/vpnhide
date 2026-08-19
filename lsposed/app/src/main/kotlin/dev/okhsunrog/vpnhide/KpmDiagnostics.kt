package dev.okhsunrog.vpnhide

internal sealed interface KpmProblemKind {
    val reason: ModuleBrokenReason?

    data class UnsupportedKernel(
        val unameR: String,
    ) : KpmProblemKind {
        override val reason get() = ModuleBrokenReason.UnsupportedKernel
    }

    // The KPM is installed but no *live* KernelPatch runtime is present to load
    // it — either none is installed (no APatch, no KPatch-Next), or the
    // KPatch-Next-Module is installed but the kernel has not been patched from
    // its UI yet (kpatch hello fails). Activation can never succeed until the
    // user provides one, so this must not read as a corrupt module or a
    // "reinstall the zip" problem.
    data object NoKernelPatchRuntime : KpmProblemKind {
        override val reason: ModuleBrokenReason? get() = null
    }

    // Null reason mirrors kmod's raw LoadFailed fallback: an untriaged exit
    // isn't a confident enough diagnosis to paint the module card red.
    data class LoadFailed(
        val detail: String,
    ) : KpmProblemKind {
        override val reason: ModuleBrokenReason? get() = null
    }
}

/** Diagnose a complete KPM installation that failed in either boot path. */
internal fun classifyKpmProblem(
    kpm: ModuleState,
    status: KpmLoadStatus,
    currentBootId: String,
    hasKpatchRuntime: Boolean,
): KpmProblemKind? {
    if (kpm !is ModuleState.Installed || kpm.active) return null
    if (status.runtime !in setOf(KpmRuntime.Activator, KpmRuntime.KpatchNext) ||
        status.loaded != false ||
        !status.isFreshFor(currentBootId)
    ) {
        return null
    }
    if (status.reason == KpmFailureReason.UnsupportedKernel) {
        return KpmProblemKind.UnsupportedKernel(status.unameR ?: "?")
    }
    // No APatch/KPatch-Next runtime is installed, so the activator had nothing
    // to load the .kpm with ("kpatch CLI not found"). Point the user at the
    // runtime rather than at a log-collection / reinstall dead end.
    if (!hasKpatchRuntime) {
        return KpmProblemKind.NoKernelPatchRuntime
    }
    return KpmProblemKind.LoadFailed(status.detail.orEmpty())
}

internal fun renderKpmProblem(
    kind: KpmProblemKind,
    res: android.content.res.Resources,
): ModuleProblem =
    ModuleProblem(
        reason = kind.reason,
        text =
            when (kind) {
                is KpmProblemKind.UnsupportedKernel -> {
                    res.getString(R.string.dashboard_issue_kpm_unsupported_kernel, kind.unameR)
                }

                is KpmProblemKind.NoKernelPatchRuntime -> {
                    res.getString(R.string.dashboard_issue_kpm_needs_runtime)
                }

                is KpmProblemKind.LoadFailed -> {
                    res.getString(R.string.dashboard_issue_kpm_load_failed, kind.detail)
                }
            },
    )

/**
 * A runtime-loaded `vpnhide` KPM without our flashable module directory is a
 * raw `.kpm` installation. It lacks the activator and boot/config lifecycle,
 * so it must not count as a valid native backend even if KernelPatch lists it.
 */
internal fun standaloneKpmLoaded(
    kpm: ModuleState,
    runtimeModulesSection: String,
): Boolean {
    if (kpm is ModuleState.Installed) return false
    val lines =
        runtimeModulesSection
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    if (lines.firstOrNull() != "available=1") return false
    return lines.drop(1).flatMap { it.split(Regex("\\s+")) }.any { it == "vpnhide" }
}
