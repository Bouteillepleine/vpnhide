#!/system/bin/sh
# Module-specific persistent state. Do not remove the canonical config here:
# users may uninstall one backend while keeping the app or another backend.

PERSIST_DIR="/data/adb/vpnhide_kpm"

rm -f "$PERSIST_DIR/load_status" "$PERSIST_DIR/targets.txt" "$PERSIST_DIR/ctl.lock"
rmdir "$PERSIST_DIR" 2>/dev/null || true

log -t vpnhide "kpm: persistent module state removed"
