#!/system/bin/sh
# shellcheck disable=SC2034
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPN Hide (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

# Status directory (survives module updates)
PERSIST_DIR="/data/adb/vpnhide_kmod"

mkdir -p "$PERSIST_DIR"
set_perm "$PERSIST_DIR" 0 0 0755

set_perm "$MODPATH/activator" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Config: /data/system/vpnhide_config.json (managed by the app)"
ui_print "- Pick target apps via the VPN Hide app."
