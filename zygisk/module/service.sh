#!/system/bin/sh
# Boot-time config delivery for the Zygisk backend. The activator owns JSON ->
# protocol projection and writes the module-dir config read by the .so.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"

apply_at_boot() {
    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "zygisk: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR" --boot-wait; then
        log -t vpnhide "zygisk: activator finished boot config"
    else
        rc=$?
        log -t vpnhide "zygisk: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_at_boot &
exit 0
