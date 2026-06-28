#!/system/bin/sh
# Boot-time config delivery for the KPM backend. The activator owns JSON ->
# protocol projection and the KernelPatch ctl0 transport.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"
STATUS_DIR="/data/adb/vpnhide_kpm"
STATUS_FILE="$STATUS_DIR/load_status"
SUPERKEY_FILE="/data/adb/vpnhide/superkey"

sanitize() {
    printf '%s' "$1" | tr '\n' ' ' | cut -c 1-240
}

write_status() {
    mkdir -p "$STATUS_DIR"
    {
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null)"
        printf 'uname_r=%s\n' "$(uname -r 2>/dev/null)"
        printf 'runtime=%s\n' "$1"
        printf 'loaded=%s\n' "$2"
        printf 'detail=%s\n' "$(sanitize "$3")"
    } > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
    chmod 0644 "$STATUS_FILE" 2>/dev/null
}

apply_at_boot() {
    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kpm: activator missing at $ACTIVATOR"
        write_status activator 0 "activator missing at $ACTIVATOR"
        return 1
    fi
    if [ -d /data/adb/ap ] && [ ! -s "$SUPERKEY_FILE" ]; then
        log -t vpnhide "kpm: APatch runtime — awaiting saved superkey"
        write_status apatch 0 awaiting_superkey
        return 0
    fi

    out="$("$ACTIVATOR" --boot-wait 2>&1)"
    rc=$?
    if [ "$rc" -eq 0 ]; then
        log -t vpnhide "kpm: activator finished boot config"
        write_status activator 1 configured
    else
        log -t vpnhide "kpm: activator failed rc=$rc"
        write_status activator 0 "rc=$rc $out"
        return "$rc"
    fi
}

apply_at_boot &
exit 0
