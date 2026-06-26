#!/bin/sh
# In-VM driver (PID 1 / rdinit) for the vpnhide *KPM* QEMU harness.
#
# Unlike the .ko harness (init.sh), the KPM is already loaded at boot
# (embedded in the patched kernel image by KernelPatch), so there is no
# insmod. Target UIDs are set at load time via the embedded extra-args
# (`kptools -A "<uids>"`), so this driver is phase-agnostic: it fabricates a
# VPN-like `vpn0` interface and reports, for each detection vector, the count
# of `vpn0` hits as seen by *this* (root, uid 0) process. run-kpm.sh boots it
# twice — once with no target (root must SEE vpn0) and once with target=0
# (root must NOT see vpn0) — and diffs the counts.
set +e
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

mount -t proc proc /proc 2>/dev/null
mount -t sysfs sys /sys 2>/dev/null
mount -t devtmpfs dev /dev 2>/dev/null

echo "##### VPNHIDE-KPM-TEST START #####"
echo "KREL=$(uname -r)"

# Did KernelPatch load our KPM and install hooks?
if dmesg | grep -q "KPM hooks installed"; then echo "KPMLOAD=ok"; else echo "KPMLOAD=FAIL"; fi
echo "KVER=$(dmesg | grep -oE 'kver=0x[0-9a-f]+' | head -1)"

# user-mode net so apk can fetch iproute2 (busybox ip can't add dummy devs)
ip link set eth0 up 2>/dev/null
ip addr add 10.0.2.15/24 dev eth0 2>/dev/null
ip route add default via 10.0.2.2 2>/dev/null
echo "nameserver 10.0.2.3" > /etc/resolv.conf
echo "https://dl-cdn.alpinelinux.org/alpine/v3.21/main" > /etc/apk/repositories
if apk add --no-cache iproute2 >/dev/null 2>&1; then echo "IPROUTE2=ok"; else echo "IPROUTE2=FAIL"; fi

# fabricate a VPN-like interface + routes through it (v4 + v6)
ip link add vpn0 type dummy 2>/dev/null
ip link set vpn0 up 2>/dev/null
ip addr add 10.9.0.1/24 dev vpn0 2>/dev/null
ip route add 10.9.9.0/24 dev vpn0 2>/dev/null
ip -6 addr add fd00:9::1/64 dev vpn0 2>/dev/null
ip -6 route add fd00:99::/64 dev vpn0 2>/dev/null

# Vectors covered by the wired hooks. Count vpn0 hits as seen by root.
echo "VEC proc_route_v4=$(cat /proc/net/route 2>/dev/null | grep -c vpn0)"        # fib_route_seq_show
echo "VEC getifaddrs=$(ip addr show 2>/dev/null | grep -c 'vpn0')"                # rtnl_fill_ifinfo
echo "VEC proc_route_v6=$(cat /proc/net/ipv6_route 2>/dev/null | grep -c vpn0)"   # ipv6_route_seq_show
echo "VEC siocgifconf=$(ifconfig -a 2>/dev/null | grep -c vpn0)"                  # sock_ioctl
echo "VEC dev_ioctl=$(ifconfig vpn0 2>/dev/null | grep -c vpn0)"                  # dev_ioctl
echo "VEC netlink_route4=$(ip route show table all 2>/dev/null | grep -c vpn0)"     # fib_dump_info (#86)

PANIC=$(dmesg | grep -ci 'Unable to handle\|Internal error\|Oops\|BUG:\|Kernel panic')
echo "PANIC=$PANIC"
echo "##### VPNHIDE-KPM-TEST END #####"
poweroff -f
