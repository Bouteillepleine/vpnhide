package dev.okhsunrog.vpnhide

import android.content.Context
import dev.okhsunrog.vpnhide.generated.HookIds

/**
 * The single place that turns a desired target set into the §4 control-config
 * wire (docs/protocol.md) and fans it to every backend's RUNTIME channel. This
 * is the app's serialiser end (protocol §1.4) — [Protocol.formatConfig] is the
 * one Kotlin serialiser, parity-tested against the C/Rust sides by the golden
 * vectors. Save, the debug toggle, and the startup reconcile all go through
 * here, so there is exactly one config-emission path in the app.
 *
 * Two storage layers are kept apart (protocol §1.2):
 *  - **Persistent PACKAGE lists** (the per-backend `targets.txt`) — NOT
 *    protocol. They survive
 *    reinstall and feed the picker + boot re-resolution. Written elsewhere via
 *    [buildConfigWriteCommand].
 *  - **Runtime CONFIG channels** (here) — resolved UIDs in the `vpnhide 1
 *    config` wire:
 *      - kmod   → `/proc/vpnhide_ctl`           (live, kernel reads on write)
 *      - zygisk → module-dir `targets.txt`      (read by the .so via dir-fd)
 *      - lsposed → `/data/system/vpnhide_uids.txt` (read by system_server)
 *      - KPM    → live `ctl0` push is a TODO; it boot-applies from its package
 *                 list, so Save/reconcile only refresh the persistent list.
 */
internal object ConfigChannels {
    // The hookmask written for every target: the full kernel hook mask. Kernel
    // backends gate per-hook on it; zygisk/lsposed own no registry bits yet, so
    // they act on target *presence* and ignore it (protocol §6 note).
    private val FULL_MASK = HookIds.KERNEL_HOOK_MASK.toLong()

    /** A `vpnhide 1 config` snapshot for [uids] with [debug] folded in (§4.3). */
    fun config(
        debug: Boolean,
        uids: Collection<Int>,
    ): String =
        Protocol.formatConfig(
            debug,
            uids.toSortedSet().map { Protocol.Target(it.toLong(), FULL_MASK) },
        )

    /**
     * Shell parts writing the native config to every installed native runtime
     * channel: the kmod `/proc` node (only when loaded — the node's presence is
     * the liveness signal) and the zygisk module dir (only when installed). The
     * KPM has no live push yet (boot-applies from its package list).
     */
    fun nativeWriteParts(
        debug: Boolean,
        nativeUids: Collection<Int>,
    ): List<String> {
        val body = config(debug, nativeUids)
        return listOf(
            "if [ -e $PROC_CTL ]; then ${buildRawWriteCommand(PROC_CTL, body)}; fi",
            "if [ -d $ZYGISK_MODULE_DIR ]; then ${buildRawWriteCommand(
                ZYGISK_MODULE_TARGETS,
                body,
            )} && chmod 644 $ZYGISK_MODULE_TARGETS; fi",
        )
    }

    /**
     * Shell parts writing the LSPosed (Java-layer) config to the system_server
     * UID file at 0640 root:system (system_data_file), the same perms the file
     * has always carried.
     */
    fun javaWriteParts(
        debug: Boolean,
        javaUids: Collection<Int>,
    ): List<String> = listOf(buildRawWriteCommand(SS_UIDS_FILE, config(debug, javaUids))) + systemDataFilePermsParts(SS_UIDS_FILE, "640")

    /**
     * Re-emit the runtime config for the CURRENT persisted targets — used by the
     * startup reconcile (seeds the runtime channels from the persistent package
     * lists, e.g. after a fresh install or a target-app reinstall that rotated
     * its UID, without waiting for a reboot) and by the debug toggle (re-emits
     * with the new flag so a running kernel/zygisk backend picks it up). Targets
     * are resolved in-process from the snapshot's package→UID map; self is
     * already in every persistent list so it resolves like any other target.
     */
    fun reconcileCommand(
        snapshot: TargetsSnapshot,
        debug: Boolean,
    ): String {
        val pkgToUids = HashMap<String, MutableList<Int>>()
        snapshot.uidToPkg.forEach { (uid, pkg) -> pkgToUids.getOrPut(pkg) { mutableListOf() }.add(uid) }

        fun uidsFor(pkgs: Set<String>): List<Int> = pkgs.flatMap { pkgToUids[it].orEmpty() }
        val parts = javaWriteParts(debug, uidsFor(snapshot.lsposedTargets)) + nativeWriteParts(debug, uidsFor(snapshot.nativeTargets))
        return parts.joinToString(" ; ")
    }
}

/**
 * Run the startup runtime-config reconcile over root: derive the targets from
 * [rootSnapshot], fold in the persisted debug flag, and (re-)write the
 * `vpnhide 1 config` snapshot to every live channel. Blocking — call from a
 * background dispatcher. Best-effort: a non-zero exit is logged, not fatal.
 */
internal fun runRuntimeConfigReconcile(
    context: Context,
    rootSnapshot: RootSnapshot,
) {
    val cmd = ConfigChannels.reconcileCommand(parseTargetsSnapshot(rootSnapshot), isEnabledInPrefs(context))
    val (exit, _) = suExec(cmd)
    if (exit != 0) VpnHideLog.w("VpnHide-Startup", "runtime config reconcile failed (exit=$exit)")
}
