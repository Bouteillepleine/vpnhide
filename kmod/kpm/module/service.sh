#!/system/bin/sh
# Late-boot config for the KPM backend: resolve target package names → UIDs and
# push a control snapshot to the KPM via the KernelPatch ctl0 supercall (the
# §4 wire format, docs/protocol.md §7). Also resolves the lsposed target list
# to /data/system/vpnhide_uids.txt — lsposed is the always-active Java layer
# (§1.5), so a KPM-only install still needs it fed.
#
# Keyless KPatch-Next (Magisk/KSU) only: APatch is superkey-required, so its
# config is applied by the app, not here (post-fs-data.sh set awaiting_superkey).

KPM_TARGETS="/data/adb/vpnhide_kpm/targets.txt"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"
# Canonical persistent debug-logging flag (the one the app writes); folded into
# the `debug` line of every config we emit (§4.3), same as the .ko/zygisk
# scripts. Absent ⇒ off. The KPM has no separate debug file.
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"

DBG="$(cat "$SS_DEBUG_LOGGING" 2>/dev/null)"
[ "$DBG" = 1 ] || DBG=0

# Full kernel-owned hook mask (data/hooks.toml: ids 0..9). Per-hook control is
# an app feature; the boot path enables all hooks for each target.
KMOD_HOOK_MASK=1023

find_kpatch() {
    for c in kpatch /data/adb/ksu/bin/kpatch /data/adb/ap/bin/kpatch; do
        if command -v "$c" >/dev/null 2>&1; then echo "$c"; return 0; fi
        [ -x "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

# Wait until PackageManager has indexed user-installed apps. `pm list packages`
# answers early in boot but returns only system packages for several seconds;
# resolving in that window silently drops user targets. Gate on our own package
# being visible, 60s budget. (Same as the .ko/zygisk service scripts.)
for i in $(seq 1 60); do
    if pm list packages -U 2>/dev/null | grep -q "^package:dev.okhsunrog.vpnhide "; then
        break
    fi
    sleep 1
done

# Migration: seed lsposed targets from the kpm list if absent.
if [ ! -f "$LSPOSED_TARGETS" ] && [ -f "$KPM_TARGETS" ]; then
    mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
    cp "$KPM_TARGETS" "$LSPOSED_TARGETS"
    log -t vpnhide "migrated kpm targets to lsposed targets"
fi

# All packages with UIDs across every profile in one call (`--user all` emits
# comma-separated UIDs per package so work-profile copies are targeted too).
ALL_PACKAGES="$(pm list packages -U --user all 2>/dev/null)"

# resolve_uids <targets_file> — prints one UID per line to stdout.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        # Literal match on field 1 — awk's `$1 == p` avoids treating dots in
        # the package name as regex wildcards.
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

# Emit a `vpnhide 1 config` snapshot (protocol §4) for a newline UID list on
# stdin's first argument. All hooks enabled per target.
build_config() {
    local uid_list="$1"
    printf 'vpnhide 1 config\n'
    printf 'debug %s\n' "$DBG"
    [ -z "$uid_list" ] && return
    echo "$uid_list" | while IFS= read -r uid; do
        [ -z "$uid" ] && continue
        printf 'target 0x%x 0x%x\n' "$uid" "$KMOD_HOOK_MASK"
    done
}

# --- apply KPM config via ctl0 (keyless KPatch-Next only) -------------------
KPATCH="$(find_kpatch || true)"
if [ -n "$KPATCH" ] && [ ! -d /data/adb/ap ] && \
   "$KPATCH" kpm list 2>/dev/null | grep -q vpnhide; then
    KPM_UIDS="$(resolve_uids "$KPM_TARGETS")"
    CONFIG="$(build_config "$KPM_UIDS")"
    if "$KPATCH" kpm ctl0 vpnhide "$CONFIG" >/dev/null 2>&1; then
        count="$(printf '%s' "$KPM_UIDS" | grep -c . || true)"
        log -t vpnhide "kpm: applied config for ${count:-0} UIDs"
    else
        log -t vpnhide "kpm: ctl0 config apply failed"
    fi
else
    log -t vpnhide "kpm: not loaded / APatch — skipping boot config apply"
fi

# --- resolve lsposed targets → /data/system/vpnhide_uids.txt ----------------
# Mode 0640 + group=system: system_server (group `system`) reads via the group
# bit; untrusted apps fall to "other" and get EACCES. (Same as .ko/zygisk.)
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    printf '%s\n' "$(build_config "$LSPOSED_UIDS")" > "$SS_UIDS_FILE"
    chmod 640 "$SS_UIDS_FILE"
    chown root:system "$SS_UIDS_FILE"
    chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null
    count="$(printf '%s\n' "$LSPOSED_UIDS" | grep -c .)"
    log -t vpnhide "lsposed: wrote config for $count UIDs to $SS_UIDS_FILE"
fi
