#!/system/bin/sh
# Resolves package names → UIDs at boot and writes a `vpnhide 1 config` snapshot
# (docs/protocol.md) to each runtime channel — the same wire every backend
# speaks. zygisk config → module dir (read by the .so via get_module_dir() fd),
# lsposed config → /data/system/vpnhide_uids.txt. The debug flag is folded into
# the config now (§4.3), so there is no separate debug_logging file to copy.

ZYGISK_TARGETS="/data/adb/vpnhide_zygisk/targets.txt"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
MODULE_DIR="${0%/*}"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"
# Canonical persistent debug-logging flag; folded into each config's `debug`
# line (§4.3). Absent ⇒ off (stealth-first default).
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"

DBG="$(cat "$SS_DEBUG_LOGGING" 2>/dev/null)"
[ "$DBG" = 1 ] || DBG=0

# Emit a `vpnhide 1 config` snapshot to stdout for a newline-separated UID list
# (header + folded debug flag + one `target <uid> 0x3ff` line per UID). See the
# matching helper in kmod/module/service.sh.
emit_config() {
    _uids="$1"
    printf 'vpnhide 1 config\n'
    printf 'debug %s\n' "$DBG"
    [ -n "$_uids" ] && printf '%s\n' "$_uids" | while IFS= read -r u; do
        [ -z "$u" ] && continue
        printf 'target 0x%x 0x3ff\n' "$u"
    done
}

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

# Migration: if lsposed targets don't exist yet, seed from zygisk targets
if [ ! -f "$LSPOSED_TARGETS" ] && [ -f "$ZYGISK_TARGETS" ]; then
    mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
    cp "$ZYGISK_TARGETS" "$LSPOSED_TARGETS"
    log -t vpnhide "migrated zygisk targets to lsposed targets"
fi

# Get all packages with UIDs across every profile in one call.
# `--user all` emits comma-separated UIDs per package for apps present
# in multiple profiles (e.g. work profile) so each profile's copy ends
# up in vpnhide_uids.txt.
ALL_PACKAGES="$(pm list packages -U --user all 2>/dev/null)"

# resolve_uids <targets_file> — prints one UID per line to stdout,
# splitting comma-separated UIDs so every profile gets its own entry.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        # Literal match on $1 — grep would treat dots in `pkg` as regex
        # wildcards. awk's `$1 == p` compares fields literally.
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

# Resolve zygisk targets → config snapshot → module dir (read by the .so via
# the get_module_dir() fd). The persistent package list lives outside the module
# dir so it survives reinstall; this re-resolves it into the module-dir config
# after a reinstall, before the user opens the app.
if [ -f "$ZYGISK_TARGETS" ]; then
    ZYGISK_UIDS="$(resolve_uids "$ZYGISK_TARGETS")"
    printf '%s\n' "$(emit_config "$ZYGISK_UIDS")" > "$MODULE_DIR/targets.txt"
    count="$(printf '%s\n' "$ZYGISK_UIDS" | grep -c .)"
    log -t vpnhide "zygisk: applied config for $count target UIDs (debug=$DBG)"
fi

# Resolve lsposed targets → config snapshot → /data/system/vpnhide_uids.txt
# Create persist dir if needed (for first-time installs)
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    # Mode 0640 + group=system: system_server (UID 1000, in group `system`)
    # reads via the group bit; untrusted apps fall to "other" and get EACCES.
    # Default 0644 was a fingerprint vector — `/data/system/` itself is mode
    # 0775 traversable by untrusted, so any o+r file is enumerable + readable.
    printf '%s\n' "$(emit_config "$LSPOSED_UIDS")" > "$SS_UIDS_FILE"
    chmod 640 "$SS_UIDS_FILE"
    chown root:system "$SS_UIDS_FILE"
    chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null
    count="$(printf '%s\n' "$LSPOSED_UIDS" | grep -c .)"
    log -t vpnhide "zygisk: wrote lsposed config for $count UIDs to $SS_UIDS_FILE"
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
