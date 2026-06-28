#!/system/bin/sh
# Installer hook run by KernelSU/Magisk. Called after the module files
# have been extracted to $MODPATH. Anything we write to $MODPATH persists
# into /data/adb/modules/<id>/ after the installer finishes.

SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPN Hide (Zygisk native) ${MOD_VER:-unknown}"
ui_print "- Installing to $MODPATH"

# Make the native library readable/executable by zygote
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0755

# ----------------------------------------------------------------------
#  Legacy target-list migration aid
# ----------------------------------------------------------------------
# New installs use /data/system/vpnhide_config.json as the source of truth.
# Keep copying an old in-module targets.txt to the legacy persistent path so
# the app can fold it into the canonical JSON on first launch after update.
PERSIST_DIR="/data/adb/vpnhide_zygisk"
PERSIST_TARGETS="$PERSIST_DIR/targets.txt"
LEGACY_TARGETS="/data/adb/modules/vpnhide_zygisk/targets.txt"

# One-shot migration: when the new persistent file does not exist yet
# but the legacy in-module file does, copy the user's existing list over
# before the staged install dir replaces /data/adb/modules/vpnhide_zygisk/.
# This works because customize.sh runs while the OLD module directory is
# still on disk — KSU/Magisk only swaps the staged dir into place after
# this script returns successfully.
if [ ! -f "$PERSIST_TARGETS" ] && [ -f "$LEGACY_TARGETS" ]; then
    mkdir -p "$PERSIST_DIR"
    set_perm "$PERSIST_DIR" 0 0 0755
    cp "$LEGACY_TARGETS" "$PERSIST_TARGETS"
    set_perm "$PERSIST_TARGETS" 0 0 0644
    ui_print "- Migrated existing targets list from previous install"
fi

ui_print "- Config: /data/system/vpnhide_config.json (managed by the app)"
ui_print "- Pick target apps via the VPN Hide app."
