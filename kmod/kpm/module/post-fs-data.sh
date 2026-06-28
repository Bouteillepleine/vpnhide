#!/system/bin/sh
# Loads the vpnhide KPM via the KernelPatch runtime, early in boot (before
# apps start), and records load_status so the app can explain *why* the module
# didn't come up without guessing. Targets are applied later, in service.sh,
# once PackageManager is up (mirrors the .ko: post-fs-data loads, service
# resolves UIDs). See docs/protocol.md §7.4.
#
# Runtime split (protocol §7.4):
#   - KPatch-Next (Magisk / KSU), keyless (d05): load here, fully automatic.
#   - APatch, superkey-required (c02): post-fs-data has no superkey, so it
#     records `awaiting_superkey`; service.sh can load/configure later through
#     the activator's direct supercall path if the app saved
#     /data/adb/vpnhide/superkey.
#
# Single-active guard (protocol §1.5): if the .ko backend is installed, do NOT
# load the KPM. They wrap the same kernel functions and co-residence freezes
# the kernel — this is the userspace half of the guard (the kernel half is the
# in-module conflict check).

MODDIR="${0%/*}"
KPM="$MODDIR/vpnhide.kpm"
STATUS_DIR="/data/adb/vpnhide_kpm"
STATUS_FILE="$STATUS_DIR/load_status"

mkdir -p "$STATUS_DIR"

# Collapse newlines/tabs so the app's key=value parser stays line-based.
sanitize() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/  */ /g'
}

# runtime, loaded(0/1), detail
write_status() {
    {
        printf 'timestamp=%s\n' "$(date +%s 2>/dev/null)"
        printf 'uname_r=%s\n' "$(uname -r 2>/dev/null)"
        printf 'runtime=%s\n' "$1"
        printf 'loaded=%s\n' "$2"
        printf 'detail=%s\n' "$(sanitize "$3")"
    } > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
    chmod 0644 "$STATUS_FILE" 2>/dev/null
}

# Locate the KernelPatch-Next CLI (`kpatch`). Normally on PATH for boot scripts;
# fall back to the KernelSU-Next bin dir.
find_kpatch() {
    for c in \
        kpatch \
        /data/adb/ksu/bin/kpatch \
        /data/adb/modules/KPatch-Next/bin/kpatch \
        /data/adb/modules/kpatch-next/bin/kpatch
    do
        if command -v "$c" >/dev/null 2>&1; then echo "$c"; return 0; fi
        [ -x "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

# --- single-active guard (§1.5): defer to the .ko if it's installed+enabled --
if [ -d /data/adb/modules/vpnhide_kmod ] && \
   [ ! -f /data/adb/modules/vpnhide_kmod/disable ]; then
    log -t vpnhide "kpm: .ko backend present — not loading KPM (single-active)"
    write_status conflict 0 "vpnhide_kmod present"
    exit 0
fi

if [ ! -f "$KPM" ]; then
    write_status unknown 0 "vpnhide.kpm not found at $KPM"
    exit 1
fi

# --- APatch (c02): superkey-required, service activator owns load/config ------
if [ -d /data/adb/ap ]; then
    log -t vpnhide "kpm: APatch runtime — deferring load to service activator (superkey)"
    write_status apatch 0 awaiting_superkey
    exit 0
fi

KPATCH="$(find_kpatch)" || {
    log -t vpnhide "kpm: kpatch CLI not found — cannot load"
    write_status unknown 0 "kpatch CLI not found"
    exit 1
}

# --- KPatch-Next (Magisk / KSU): keyless, load now --------------------------
# Keyless: the superkey argument is omitted (protocol §7.3).
LOAD_OUT="$("$KPATCH" kpm load "$KPM" 2>&1)"
if "$KPATCH" kpm list 2>/dev/null | grep -q vpnhide; then
    log -t vpnhide "kpm: loaded (kpatch-next)"
    write_status kpatch-next 1 "$LOAD_OUT"
    exit 0
fi
log -t vpnhide "kpm: load failed: $LOAD_OUT"
write_status kpatch-next 0 "$LOAD_OUT"
exit 1
