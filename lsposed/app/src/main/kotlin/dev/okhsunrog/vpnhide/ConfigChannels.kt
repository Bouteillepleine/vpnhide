package dev.okhsunrog.vpnhide

import android.content.Context

/**
 * Launches the native activators. The app does not fan out wire snapshots
 * itself: it writes canonical JSON, then runs the installed native module's
 * activator, which derives that backend's wire from the JSON.
 */
internal object ConfigChannels {
    /** Shell part running exactly one native activator by backend priority. */
    fun nativeActivatorCommand(): String =
        "if [ -x $KMOD_ACTIVATOR ] && [ ! -f $KMOD_MODULE_DIR/disable ]; then $KMOD_ACTIVATOR; " +
            "elif [ -x $KPM_ACTIVATOR ] && [ ! -f $KPM_MODULE_DIR/disable ]; then $KPM_ACTIVATOR; " +
            "elif [ -x $ZYGISK_ACTIVATOR ] && [ ! -f $ZYGISK_MODULE_DIR/disable ]; then $ZYGISK_ACTIVATOR; " +
            "else true; fi"

    /** Shell part running the optional ports activator when its module is enabled. */
    fun portsActivatorCommand(): String =
        "if [ -x $PORTS_ACTIVATOR ] && [ ! -f $PORTS_MODULE_DIR/disable ]; then $PORTS_ACTIVATOR 2>&1; else true; fi"

    /**
     * Re-emit the runtime config for the current canonical config. Package→UID
     * resolution and wire formatting live in the activator, not in the app.
     */
    fun reconcileCommand(): String = nativeActivatorCommand()
}

/**
 * If capture left debug enabled, return a canonical copy with effective debug
 * snapped back to user intent for startup reconciliation.
 */
internal fun canonicalConfigForStartupDebugReconcile(config: CanonicalConfig): CanonicalConfig? =
    if (config.debug != config.debugSwitch) config.copy(debug = config.debugSwitch) else null

/**
 * Run the startup runtime-config reconcile. Canonical JSON already contains
 * debug, so reconcile only needs to re-run activators to pick up the current
 * file state. Blocking — call from a background dispatcher. Best-effort: a
 * non-zero exit is logged, not fatal.
 */
internal fun runRuntimeConfigReconcile() {
    val parts = mutableListOf<String>()
    parts += ConfigChannels.reconcileCommand()
    val cmd = parts.joinToString(" ; ")
    val (exit, _) = suExec(cmd)
    if (exit != 0) VpnHideLog.w(LogTags.STARTUP, "runtime config reconcile failed (exit=$exit)")
}

/**
 * Re-materialize `settings.autoHiddenPackages` for the on-disk [config] against
 * fresh VpnService [signals], and persist it iff the auto-hidden set changed.
 * This keeps a newly-installed VPN app hidden from observers after a Hiding-tab
 * Refresh or a cold start, without the user having to open the picker and Save.
 *
 * Idempotent: [applyAutoHiddenPackages] only touches the auto-hidden set and the
 * hidden flags derived from it — every manual role (Java / Native / Apps / Ports
 * and manually-hidden packages) is preserved — so an unchanged set writes
 * nothing. Best-effort, blocking; call from a background dispatcher. Returns
 * true when it wrote (and re-activated) a new config.
 */
internal fun reconcileAutoHiddenPackages(
    context: Context,
    config: CanonicalConfig,
    signals: Collection<AppAutoHideSignal>,
): Boolean {
    val selfPkg = context.packageName
    if (!autoHiddenPackagesNeedReconcile(config, selfPkg, signals)) return false
    val next = applyAutoHiddenPackages(config, selfPkg, signals)
    val result = CanonicalConfigRepository.persist(next)
    if (!result.succeeded) {
        VpnHideLog.w(
            LogTags.STARTUP,
            "auto-hide reconcile failed (exit=${result.exitCode}): ${result.output.trim()}",
        )
        return false
    }
    VpnHideLog.i(
        LogTags.STARTUP,
        "auto-hide reconcile: ${next.settings.autoHiddenPackages.size} auto-hidden package(s)",
    )
    return true
}
