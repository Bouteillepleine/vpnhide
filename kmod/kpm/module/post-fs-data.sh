#!/system/bin/sh
# Loads the vpnhide KPM via the KernelPatch runtime, early in boot (before
# apps start), and records load_status so the app can explain *why* the module
# didn't come up without guessing. Targets are applied later, in service.sh,
# once PackageManager is up (mirrors the .ko: post-fs-data loads, service
# resolves UIDs). See docs/protocol.md §7.4.
#
# Runtime split (protocol §7.4):
#   - KPatch-Next (Magisk / KSU), keyless (d05): the activator validates the
#     running kernel and loads here, without waiting for PackageManager.
#   - APatch/FolkPatch: post-fs-data records a deferred status; service.sh
#     can load/configure later through the activator's direct supercall path
#     with a saved /data/adb/vpnhide/superkey or the runtime's trusted `su`
#     supercall grant.
#
# Single-active guard (protocol §1.5): if the .ko backend is installed, do NOT
# load the KPM. They wrap the same kernel functions and co-residence freezes
# the kernel. The guard is layered in userspace, fail-safe at every step:
#   1. here (post-fs-data): defer before loading the .kpm at all;
#   2. service.sh: re-checks before configuring, and honours the activator's
#      EXIT_DEFERRED_CONFLICT (3);
#   3. the activator's kmod_backend_present() — a superset that also catches a
#      live /proc/vpnhide_ctl — gates the config-delivery path.
# There is deliberately NO kernel-side mutual exclusion: the two modules load
# in the same post-fs-data window with no ordering guarantee, so an in-kernel
# check could itself race into the freeze it means to prevent. Detection-by-
# installation (a directory check, not a load check) is ordering-independent
# and keeps the decision in userspace where it can fail safe.

MODDIR="${0%/*}"
KPM="$MODDIR/vpnhide.kpm"
ACTIVATOR="$MODDIR/activator"
STATUS_DIR="/data/adb/vpnhide_kpm"
STATUS_FILE="$STATUS_DIR/load_status"

mkdir -p "$STATUS_DIR"

# Collapse newlines/tabs so the app's key=value parser stays line-based.
sanitize() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/  */ /g'
}

# runtime, loaded(0/1), reason, detail
write_status() {
    {
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null)"
        printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
        printf 'uname_r=%s\n' "$(uname -r 2>/dev/null)"
        printf 'runtime=%s\n' "$1"
        printf 'loaded=%s\n' "$2"
        printf 'reason=%s\n' "$3"
        printf 'detail=%s\n' "$(sanitize "$4")"
    } > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
    chmod 0644 "$STATUS_FILE" 2>/dev/null
}

# --- single-active guard (§1.5): defer to the .ko if it's installed+enabled --
if [ -d /data/adb/modules/vpnhide_kmod ] && \
   [ ! -f /data/adb/modules/vpnhide_kmod/disable ]; then
    log -t vpnhide "kpm: .ko backend present — not loading KPM (single-active)"
    write_status conflict 0 conflicting_backend "vpnhide_kmod present"
    exit 0
fi

if [ ! -f "$KPM" ]; then
    write_status unknown 0 missing_kpm "vpnhide.kpm not found at $KPM"
    exit 1
fi

if [ ! -x "$ACTIVATOR" ]; then
    write_status activator 0 missing_activator "activator missing at $ACTIVATOR"
    exit 1
fi

# --- APatch/FolkPatch: service activator owns load/config -------------------
if [ -d /data/adb/ap ]; then
    log -t vpnhide "kpm: APatch/FolkPatch runtime — deferring load to service activator"
    write_status apatch 0 awaiting_superkey awaiting_superkey
    exit 0
fi

# --- KPatch-Next (Magisk / KSU): keyless, load now --------------------------
LOAD_OUT="$("$ACTIVATOR" --load-only 2>&1)"
rc=$?
case "$rc" in
    0)
        log -t vpnhide "kpm: loaded (kpatch-next)"
        write_status kpatch-next 1 ok "$LOAD_OUT"
        ;;
    3)
        log -t vpnhide "kpm: activator deferred to .ko (single-active)"
        write_status conflict 0 conflicting_backend "vpnhide_kmod present"
        ;;
    5)
        log -t vpnhide "kpm: unsupported kernel $(uname -r 2>/dev/null)"
        write_status kpatch-next 0 unsupported_kernel "unsupported kernel $(uname -r 2>/dev/null)"
        ;;
    *)
        log -t vpnhide "kpm: load failed rc=$rc: $LOAD_OUT"
        write_status kpatch-next 0 load_failed "rc=$rc $LOAD_OUT"
        exit "$rc"
        ;;
esac
exit 0
