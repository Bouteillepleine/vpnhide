#!/system/bin/sh
# Module-specific legacy migration state. Do not remove the canonical config
# here: users may uninstall one backend while keeping the app or another backend.

PERSIST_DIR="/data/adb/vpnhide_zygisk"

rm -f "$PERSIST_DIR/targets.txt"
rmdir "$PERSIST_DIR" 2>/dev/null || true

log -t vpnhide "zygisk: persistent module state removed"
