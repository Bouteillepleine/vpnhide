package dev.okhsunrog.vpnhide

internal enum class ActivatorState {
    Executable,
    Missing,
    NotExecutable,
    Unknown,
}

internal fun parseActivatorState(raw: String): ActivatorState =
    when (raw.trim()) {
        "executable" -> ActivatorState.Executable
        "missing" -> ActivatorState.Missing
        "not_executable" -> ActivatorState.NotExecutable
        else -> ActivatorState.Unknown
    }

internal data class ModuleIntegrityProblem(
    val kind: FlashableModuleKind,
    val activatorState: ActivatorState,
    val activatorPath: String,
) {
    val reason: ModuleBrokenReason
        get() =
            when (activatorState) {
                ActivatorState.Missing -> ModuleBrokenReason.ActivatorMissing
                ActivatorState.NotExecutable -> ModuleBrokenReason.ActivatorNotExecutable
                ActivatorState.Executable, ActivatorState.Unknown -> error("healthy or unknown activator is not an integrity problem")
            }
}

internal fun classifyModuleIntegrity(
    kind: FlashableModuleKind,
    module: ModuleState,
    activatorState: ActivatorState,
    activatorPath: String,
    disabled: Boolean,
): ModuleIntegrityProblem? {
    if (module !is ModuleState.Installed || disabled || activatorState !in ACTIVATOR_FAILURE_STATES) return null
    return ModuleIntegrityProblem(kind, activatorState, activatorPath)
}

internal fun moduleIntegrityProblem(
    kind: FlashableModuleKind,
    module: ModuleState,
    sections: Map<String, String>,
    activatorPath: String,
): ModuleIntegrityProblem? {
    val prefix =
        when (kind) {
            FlashableModuleKind.Kmod -> "kmod"
            FlashableModuleKind.Kpm -> "kpm"
            FlashableModuleKind.Zygisk -> "zygisk"
            FlashableModuleKind.Ports -> "ports"
        }
    return classifyModuleIntegrity(
        kind = kind,
        module = module,
        activatorState = parseActivatorState(sections["${prefix}_activator_state"].orEmpty()),
        activatorPath = activatorPath,
        disabled = sections["${prefix}_disabled"]?.trim() == "1",
    )
}

internal fun renderModuleIntegrityProblem(
    problem: ModuleIntegrityProblem,
    res: android.content.res.Resources,
): ModuleProblem {
    val moduleName =
        when (problem.kind) {
            FlashableModuleKind.Kmod -> "kmod"
            FlashableModuleKind.Kpm -> "KPM"
            FlashableModuleKind.Zygisk -> "Zygisk"
            FlashableModuleKind.Ports -> "Ports"
        }
    val message =
        when (problem.kind to problem.activatorState) {
            FlashableModuleKind.Kpm to ActivatorState.Missing -> {
                R.string.dashboard_issue_kpm_bundle_activator_missing
            }

            FlashableModuleKind.Kpm to ActivatorState.NotExecutable -> {
                R.string.dashboard_issue_kpm_bundle_activator_not_executable
            }

            else -> {
                when (problem.activatorState) {
                    ActivatorState.Missing -> {
                        R.string.dashboard_issue_module_activator_missing
                    }

                    ActivatorState.NotExecutable -> {
                        R.string.dashboard_issue_module_activator_not_executable
                    }

                    ActivatorState.Executable, ActivatorState.Unknown -> {
                        error("healthy or unknown activator is not an integrity problem")
                    }
                }
            }
        }
    val text =
        if (problem.kind == FlashableModuleKind.Kpm) {
            res.getString(message, problem.activatorPath)
        } else {
            res.getString(message, moduleName, problem.activatorPath)
        }
    return ModuleProblem(
        reason = problem.reason,
        text = text,
    )
}

internal fun ModuleState.withBrokenReason(reason: ModuleBrokenReason?): ModuleState =
    if (this is ModuleState.Installed && reason != null) copy(brokenReason = reason) else this

private val ACTIVATOR_FAILURE_STATES = setOf(ActivatorState.Missing, ActivatorState.NotExecutable)
