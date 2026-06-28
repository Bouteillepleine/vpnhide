#!/system/bin/sh
# Boot-time config delivery for the Zygisk backend. The activator owns JSON ->
# protocol projection and writes the module-dir config read by the .so.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"

apply_when_pm_ready() {
    while ! pm list packages -U 2>/dev/null | grep -q "^package:dev.okhsunrog.vpnhide "; do
        sleep 5
    done

    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "zygisk: activator missing at $ACTIVATOR"
        return 1
    fi

    if "$ACTIVATOR"; then
        log -t vpnhide "zygisk: activator applied config"
    else
        rc=$?
        log -t vpnhide "zygisk: activator failed rc=$rc"
        return "$rc"
    fi
}

apply_when_pm_ready &
exit 0
