package dev.okhsunrog.vpnhide

internal sealed interface KpmProblemKind {
    val reason: ModuleBrokenReason?

    data class UnsupportedKernel(
        val unameR: String,
    ) : KpmProblemKind {
        override val reason get() = ModuleBrokenReason.UnsupportedKernel
    }

    // The activator binary itself is missing from the module directory — a
    // corrupted or partial KPM install (see kmod/kpm/module/service.sh).
    // [path] is the path the boot script tried to exec.
    data class ActivatorMissing(
        val path: String,
    ) : KpmProblemKind {
        override val reason get() = ModuleBrokenReason.KpmActivatorMissing
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
    loadStatusSection: String,
    currentBootId: String,
): KpmProblemKind? {
    if (kpm !is ModuleState.Installed || kpm.active) return null
    val load = parseKeyValueLines(loadStatusSection)
    val bootId = load["boot_id"]?.trim()
    val runtime = load["runtime"]?.trim()
    if (runtime !in setOf("activator", "kpatch-next") ||
        load["loaded"]?.trim() != "0" ||
        bootId.isNullOrEmpty() ||
        bootId != currentBootId.trim()
    ) {
        return null
    }
    val reason = load["reason"]?.trim()
    if (reason == "unsupported_kernel") {
        return KpmProblemKind.UnsupportedKernel(load["uname_r"]?.trim().orEmpty().ifBlank { "?" })
    }
    val detail = load["detail"]?.trim().orEmpty()
    val missingPrefix = "activator missing at "
    return if (reason == "missing_activator" || detail.startsWith(missingPrefix)) {
        KpmProblemKind.ActivatorMissing(detail.removePrefix(missingPrefix))
    } else {
        KpmProblemKind.LoadFailed(detail)
    }
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

                is KpmProblemKind.ActivatorMissing -> {
                    res.getString(R.string.dashboard_issue_kpm_activator_missing, kind.path)
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
