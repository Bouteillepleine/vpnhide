#!/system/bin/sh
# Boot-time config delivery for the kmod backend. The activator owns JSON ->
# protocol projection; this script only waits for the proc node and invokes it.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"
PROC_CTL="/proc/vpnhide_ctl"

apply_when_pm_ready() {
    for i in 1 2 3 4 5 6 7 8 9 10; do
        [ -e "$PROC_CTL" ] && break
        sleep 1
    done

    if [ ! -e "$PROC_CTL" ]; then
        log -t vpnhide "kmod: $PROC_CTL not present, skipping config"
        return 0
    fi

    while ! pm list packages -U 2>/dev/null | grep -q "^package:dev.okhsunrog.vpnhide "; do
        sleep 5
    done

    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kmod: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR"; then
        log -t vpnhide "kmod: activator applied config"
    else
        rc=$?
        log -t vpnhide "kmod: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_when_pm_ready &
exit 0
