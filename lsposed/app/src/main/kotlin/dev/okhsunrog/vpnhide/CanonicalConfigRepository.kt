package dev.okhsunrog.vpnhide

/** Runtime channels that must be re-derived after the canonical config changes. */
internal data class CanonicalActivation(
    val native: Boolean = true,
    val ports: Boolean = false,
)

internal data class CanonicalWriteResult(
    val exitCode: Int,
    val output: String,
) {
    val succeeded: Boolean
        get() = exitCode == 0
}

/**
 * Build the one root transaction used for canonical-config persistence.
 *
 * Every step is joined with `&&`: activators must never run against stale
 * state when the atomic write (or a coupled secret write) failed.
 */
internal fun buildCanonicalPersistenceCommand(
    config: CanonicalConfig,
    coupledCommands: List<String> = emptyList(),
    activation: CanonicalActivation = CanonicalActivation(),
): String =
    buildList {
        add(buildCanonicalConfigWriteCommand(config))
        addAll(coupledCommands)
        if (activation.native) add(ConfigChannels.nativeActivatorCommand())
        if (activation.ports) add(ConfigChannels.portsActivatorCommand())
    }.joinToString(" && ")

/**
 * Sole app-side coordinator for canonical JSON writes and runtime activation.
 *
 * The monitor prevents two background UI operations from interleaving root
 * writes in this process. The filesystem write itself remains atomic for
 * system_server and native readers.
 */
internal object CanonicalConfigRepository {
    @Synchronized
    fun persist(
        config: CanonicalConfig,
        coupledCommands: List<String> = emptyList(),
        activation: CanonicalActivation = CanonicalActivation(),
        timeoutSec: Long = SU_DEFAULT_TIMEOUT_SEC,
    ): CanonicalWriteResult {
        val command = buildCanonicalPersistenceCommand(config, coupledCommands, activation)
        val (exit, output) = suExec(command, timeoutSec)
        if (exit == 0) invalidateCanonicalConfigCaches()
        return CanonicalWriteResult(exit, output)
    }
}

private fun invalidateCanonicalConfigCaches() {
    RootSnapshotCache.invalidate()
    TargetsCache.invalidate()
    DashboardCache.invalidate()
    StatisticsCache.invalidate()
}
