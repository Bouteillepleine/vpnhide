#!/system/bin/sh
# Boot-time config delivery for the kmod backend. The activator owns JSON ->
# protocol projection and waits for backend/PackageManager readiness.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"

apply_at_boot() {
    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kmod: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR" --boot-wait; then
        log -t vpnhide "kmod: activator finished boot config"
    else
        rc=$?
        log -t vpnhide "kmod: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_at_boot &
exit 0
