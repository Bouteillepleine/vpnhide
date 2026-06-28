#!/system/bin/sh
# Boot-time config delivery for the KPM backend. The activator owns JSON ->
# protocol projection and the KernelPatch ctl0 transport.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"

apply_when_pm_ready() {
    while ! pm list packages -U 2>/dev/null | grep -q "^package:dev.okhsunrog.vpnhide "; do
        sleep 5
    done

    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kpm: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR"; then
        log -t vpnhide "kpm: activator applied config"
    else
        rc=$?
        log -t vpnhide "kpm: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_when_pm_ready &
exit 0
