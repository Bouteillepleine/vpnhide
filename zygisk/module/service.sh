#!/system/bin/sh
MODDIR="${0%/*}"
exec "$MODDIR/activator" boot-service
