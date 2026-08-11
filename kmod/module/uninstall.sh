#!/system/bin/sh
# Module-specific persistent state. Do not remove the canonical config here:
# users may uninstall one backend while keeping the app or another backend.

PERSIST_DIR="/data/adb/vpnhide_kmod"

rm -f "$PERSIST_DIR/load_status" "$PERSIST_DIR/load_dmesg"
rmdir "$PERSIST_DIR" 2>/dev/null || true

log -t vpnhide "kmod: persistent module state removed"
