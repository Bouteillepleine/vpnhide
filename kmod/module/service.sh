#!/system/bin/sh
# Resolves package names → UIDs at boot and writes a `vpnhide 1 config` snapshot
# (docs/protocol.md) to each runtime channel — the same wire every backend
# speaks. kmod config → /proc/vpnhide_ctl, lsposed config → vpnhide_uids.txt.

KMOD_TARGETS="/data/adb/vpnhide_kmod/targets.txt"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
PROC_CTL="/proc/vpnhide_ctl"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"
# Canonical persistent debug-logging flag; folded into the `debug` line of every
# config we emit (§4.3). Absent ⇒ off (stealth-first default).
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"

DBG="$(cat "$SS_DEBUG_LOGGING" 2>/dev/null)"
[ "$DBG" = 1 ] || DBG=0

# Emit a `vpnhide 1 config` snapshot to stdout for a newline-separated UID list:
# the header, the folded debug flag, and one `target <uid> 0x3ff` line per UID
# (0x3ff = the full kernel hook mask). The whole snapshot must reach a backend
# in ONE write (the /proc node parses per write), so callers capture this in a
# variable and write it with a single redirect.
emit_config() {
    _uids="$1"
    printf 'vpnhide 1 config\n'
    printf 'debug %s\n' "$DBG"
    [ -n "$_uids" ] && printf '%s\n' "$_uids" | while IFS= read -r u; do
        [ -z "$u" ] && continue
        printf 'target 0x%x 0x3ff\n' "$u"
    done
}

# Wait for the proc entry (kernel module must be loaded)
for i in 1 2 3 4 5 6 7 8 9 10; do
    [ -e "$PROC_CTL" ] && break
    sleep 1
done

# Wait until PackageManager has actually indexed user-installed apps.
# `pm list packages` starts responding very early in boot but returns
# only system packages for several more seconds — if we resolve during
# that window, `dev.okhsunrog.vpnhide` (and any other user-installed
# target) silently drops from the UID file and the LSPosed hook caches
# an empty target set for the rest of the session. Gate on our own
# package being visible, with a 60s budget.
for i in $(seq 1 60); do
    if pm list packages -U 2>/dev/null | grep -q "^package:dev.okhsunrog.vpnhide "; then
        break
    fi
    sleep 1
done

if [ ! -e "$PROC_CTL" ]; then
    log -t vpnhide "kernel module not loaded, skipping kmod config"
fi

# Migration: if lsposed targets don't exist yet, seed from kmod targets
if [ ! -f "$LSPOSED_TARGETS" ] && [ -f "$KMOD_TARGETS" ]; then
    cp "$KMOD_TARGETS" "$LSPOSED_TARGETS"
    log -t vpnhide "migrated kmod targets to lsposed targets"
fi

# Get all packages with UIDs across every profile in one call.
# `--user all` emits comma-separated UIDs per package line for apps
# present in multiple profiles, e.g.
#   package:com.android.chrome uid:10187,1010187
# so work-profile / secondary-user installs get targeted too.
ALL_PACKAGES="$(pm list packages -U --user all 2>/dev/null)"

# resolve_uids <targets_file> — prints one UID per line to stdout.
# Splits the comma-separated UID list so every profile's copy of the
# target package becomes its own `target` line in the emitted config.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        # Literal match on $1 — grep would treat dots in `pkg` as regex
        # wildcards (e.g. `com.x.y` matching `comXxXy` if such a package
        # ever existed). awk's `$1 == p` compares fields literally.
        uid_csv="$(echo "$ALL_PACKAGES" | awk -v p="package:${pkg}" '
            $1 == p {
                sub(/uid:/, "", $2)
                n = split($2, ids, ",")
                for (i = 1; i <= n; i++) print ids[i]
            }')"
        if [ -n "$uid_csv" ]; then
            expanded="$(echo "$uid_csv" | tr ',' '\n')"
            if [ -z "$uids" ]; then uids="$expanded"; else uids="${uids}
${expanded}"; fi
        else
            log -t vpnhide "package not found: $pkg"
        fi
    done < "$targets_file"
    [ -n "$uids" ] && echo "$uids"
}

# Resolve kmod targets → config snapshot → /proc/vpnhide_ctl (one write).
if [ -e "$PROC_CTL" ] && [ -f "$KMOD_TARGETS" ]; then
    KMOD_UIDS="$(resolve_uids "$KMOD_TARGETS")"
    KMOD_CFG="$(emit_config "$KMOD_UIDS")"
    printf '%s\n' "$KMOD_CFG" > "$PROC_CTL"
    count="$(printf '%s\n' "$KMOD_UIDS" | grep -c .)"
    log -t vpnhide "kmod: applied config for $count target UIDs (debug=$DBG)"
fi

# Resolve lsposed targets → config snapshot → /data/system/vpnhide_uids.txt
# Create persist dir if needed (for first-time installs)
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
# Mode 0640 + group=system: system_server (UID 1000, in group `system`)
# reads via the group bit; untrusted apps fall to "other" and get EACCES.
# Default 0644 was a fingerprint vector — `/data/system/` itself is mode
# 0775 traversable by untrusted, so any o+r file is enumerable + readable.
if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    printf '%s\n' "$(emit_config "$LSPOSED_UIDS")" > "$SS_UIDS_FILE"
    chmod 640 "$SS_UIDS_FILE"
    chown root:system "$SS_UIDS_FILE"
    chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null
    count="$(printf '%s\n' "$LSPOSED_UIDS" | grep -c .)"
    log -t vpnhide "lsposed: wrote config for $count UIDs to $SS_UIDS_FILE"
fi

# Migrate pre-PR files written by older versions with mode 0644: any
# vpnhide_*.txt the lsposed app may have left in /data/system/. Touch
# only files that already exist; don't create new ones here.
for f in "$SS_UIDS_FILE" \
         /data/system/vpnhide_hidden_pkgs.txt \
         /data/system/vpnhide_observer_uids.txt; do
    if [ -f "$f" ]; then
        chmod 640 "$f"
        chown root:system "$f"
        chcon u:object_r:system_data_file:s0 "$f" 2>/dev/null
    fi
done

# The kmod debug flag is no longer a separate /proc node — it is the `debug`
# line of the config snapshot written to /proc/vpnhide_ctl above (folded per
# docs/protocol.md §4.3), sourced from $SS_DEBUG_LOGGING at the top of this
# script. So there is nothing extra to re-seed here.
