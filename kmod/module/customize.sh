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

# WebUI: the root manager serves webroot/ to a WebView, so it has to be
# world-readable and its directory traversable — otherwise the manager shows an
# empty page with no explanation.
if [ -d "$MODPATH/webroot" ]; then
    set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
    ui_print "- WebUI: open this module in your root manager to pick target apps"
fi

ui_print "- Config: /data/system/vpnhide_config.json"
ui_print "- Pick target apps in the WebUI, or in the VPN Hide app."
ui_print "- The app additionally hides the VPN from Java network APIs;"
ui_print "  this module covers the kernel level only."
