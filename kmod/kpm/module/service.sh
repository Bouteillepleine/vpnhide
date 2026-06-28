#!/system/bin/sh
# Boot-time config delivery for the KPM backend. The activator owns JSON ->
# protocol projection and the KernelPatch ctl0 transport.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"

apply_at_boot() {
    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kpm: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR" --boot-wait; then
        log -t vpnhide "kpm: activator finished boot config"
    else
        rc=$?
        log -t vpnhide "kpm: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_at_boot &
exit 0
