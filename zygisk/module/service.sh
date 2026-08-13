#!/system/bin/sh
MODDIR="${0%/*}"
"$MODDIR/activator" boot-service &
exit 0
