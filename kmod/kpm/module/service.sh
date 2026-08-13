#!/system/bin/sh
# Boot-time config delivery for the KPM backend. The activator owns JSON ->
# protocol projection and the KernelPatch ctl0 transport.

MODDIR="${0%/*}"
ACTIVATOR="$MODDIR/activator"
KPM="$MODDIR/vpnhide.kpm"
STATUS_DIR="/data/adb/vpnhide_kpm"
STATUS_FILE="$STATUS_DIR/load_status"
FILESYSTEM_HIDING=""

sanitize() {
    printf '%s' "$1" | tr '\n' ' ' | cut -c 1-240
}

write_status() {
    mkdir -p "$STATUS_DIR"
    {
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null)"
        printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
        printf 'uname_r=%s\n' "$(uname -r 2>/dev/null)"
        printf 'runtime=%s\n' "$1"
        printf 'loaded=%s\n' "$2"
        printf 'filesystem_hiding=%s\n' "$FILESYSTEM_HIDING"
        printf 'reason=%s\n' "$3"
        printf 'detail=%s\n' "$(sanitize "$4")"
    } > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
    chmod 0644 "$STATUS_FILE" 2>/dev/null
}

filesystem_hiding_for_load() {
    current_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    status_boot_id="$(sed -n 's/^boot_id=//p' "$STATUS_FILE" 2>/dev/null | head -n 1)"
    status_loaded="$(sed -n 's/^loaded=//p' "$STATUS_FILE" 2>/dev/null | head -n 1)"
    status_feature="$(sed -n 's/^filesystem_hiding=//p' "$STATUS_FILE" 2>/dev/null | head -n 1)"
    if [ "$status_boot_id" = "$current_boot_id" ] && [ "$status_loaded" = 1 ]; then
        case "$status_feature" in
            0|1)
                printf '%s' "$status_feature"
                return
                ;;
        esac
    fi

    "$ACTIVATOR" boot-feature-enabled filesystem_iface_paths >/dev/null 2>&1
    case "$?" in
        0) printf '1' ;;
        1) printf '0' ;;
    esac
}

apply_at_boot() {
    # Single-active guard (§1.5), checked before the APatch superkey branch so
    # a co-installed .ko isn't masked as `awaiting_superkey`. Cheap, fail-safe,
    # ordering-independent floor; the activator re-checks (a superset, incl. a
    # live /proc/vpnhide_ctl) below and exits 3 if it still sees the .ko.
    if [ -d /data/adb/modules/vpnhide_kmod ] && \
       [ ! -f /data/adb/modules/vpnhide_kmod/disable ]; then
        log -t vpnhide "kpm: .ko backend present — not configuring KPM (single-active)"
        write_status conflict 0 conflicting_backend "vpnhide_kmod present"
        return 0
    fi
    if [ ! -x "$ACTIVATOR" ]; then
        log -t vpnhide "kpm: activator missing at $ACTIVATOR"
        write_status activator 0 missing_activator "activator missing at $ACTIVATOR"
        return 1
    fi
    if [ ! -f "$KPM" ]; then
        log -t vpnhide "kpm: KPM missing at $KPM"
        write_status activator 0 missing_kpm "vpnhide.kpm missing at $KPM"
        return 1
    fi

    FILESYSTEM_HIDING="$(filesystem_hiding_for_load)"

    out="$("$ACTIVATOR" --boot-wait 2>&1)"
    rc=$?
    case "$rc" in
        0)
            log -t vpnhide "kpm: activator finished boot config"
            write_status activator 1 ok configured
            ;;
        3)
            # EXIT_DEFERRED_CONFLICT: the activator found the .ko present (e.g.
            # it loaded during the PackageManager wait) and stood down. Record
            # the truthful conflict status rather than a false `configured`.
            log -t vpnhide "kpm: activator deferred to .ko (single-active)"
            write_status conflict 0 conflicting_backend "vpnhide_kmod present"
            ;;
        4)
            # EXIT_AWAITING_AUTHENTICATION: APatch/FolkPatch is available but
            # no saved superkey or trusted su grant authenticated yet.
            log -t vpnhide "kpm: awaiting APatch authentication"
            write_status apatch 0 awaiting_superkey awaiting_superkey
            return 0
            ;;
        5)
            log -t vpnhide "kpm: unsupported kernel $(uname -r 2>/dev/null)"
            write_status activator 0 unsupported_kernel "unsupported kernel $(uname -r 2>/dev/null)"
            return 0
            ;;
        *)
            log -t vpnhide "kpm: activator failed rc=$rc"
            write_status activator 0 activation_failed "rc=$rc $out"
            return "$rc"
            ;;
    esac
}

apply_at_boot &
exit 0
