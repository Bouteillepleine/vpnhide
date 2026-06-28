#!/system/bin/sh
# Applies iptables rules from canonical JSON at boot time. iptables rules are
# in-memory, so this restores them after reboot.

MODDIR="${0%/*}"
APPLY="$MODDIR/vpnhide_ports_apply.sh"

apply_when_ready() {
    # Wait for netd to finish its own iptables setup so our rules survive
    # netd's initial chain rebuild. Check for the bw_OUTPUT chain as a signal
    # that netd has populated its baseline rules.
    for i in $(seq 1 60); do
        iptables -L bw_OUTPUT -n >/dev/null 2>&1 && break
        sleep 1
    done

    sh "$APPLY" && log -t vpnhide_ports "applied iptables rules at boot"

    # Re-apply once more 30 s later. On slow boots netd has been observed to
    # flush/rebuild its own chains AFTER bw_OUTPUT first appears, which
    # would wipe ours. The apply script is idempotent — chains are created
    # with `-N ... 2>/dev/null || true` and rebuilt atomically via
    # `iptables-restore --noflush` — so a second pass is harmless when
    # nothing was wiped and self-healing when it was.
    sleep 30
    sh "$APPLY" && log -t vpnhide_ports "re-applied iptables rules (T+30s safety pass)"
}

apply_when_ready &
exit 0
