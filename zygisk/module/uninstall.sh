#!/system/bin/sh
# Zygisk keeps no backend-owned persistent state. The canonical config belongs
# to the app and may still be used by another backend after this uninstall.
log -t vpnhide "zygisk: module removed"
