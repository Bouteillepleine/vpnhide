#!/usr/bin/env bash
# Boot the vpnhide *KPM* in QEMU (TCG) and validate it hides a fabricated
# VPN interface from a target UID. Mirrors run.sh but for the KernelPatch
# Module backend instead of the .ko:
#
#   1. build vpnhide.kpm (`make kpm`)
#   2. download KernelPatch's kptools-linux + kpimg-linux (cached)
#   3. patch the cached GKI Image TWICE, embedding the .kpm:
#        - phase "notarget": no target UID  -> root must SEE vpn0
#        - phase "target"  : target = uid 0 -> root must NOT see vpn0
#   4. boot each (init-kpm.sh) and diff the per-vector vpn0 counts
#
# A vector PASSes iff notarget>0 and target==0, with no panic in either boot.
# This needs no /proc (targets come via the embedded extra-args), so it
# validates the inline hooks + per-kver offsets independently of the procfs
# control plane.
#
# Usage:  kmod/test/run-kpm.sh [kmi]      (default: android12-5.10)
set -euo pipefail

KMI="${1:-android12-5.10}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KMOD="$(cd "$HERE/.." && pwd)"
CACHE="$HERE/.cache"
KDIR="$CACHE/$KMI"
KPBIN="$CACHE/kp"

IMAGE="${VPNHIDE_QEMU_IMAGE:-$KDIR/Image}"
KPM="${VPNHIDE_KPM:-$KMOD/vpnhide.kpm}"
ALPINE_VER="3.21.2"
ALPINE_TAR="${VPNHIDE_QEMU_ROOTFS:-$CACHE/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz}"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz"
# Prebuilt KernelPatch host tool + generic runtime (no need to build them).
KP_RELEASE="${VPNHIDE_KP_RELEASE:-}" # empty = latest
SKEY="vpnhide-qemu-test"

command -v qemu-system-aarch64 >/dev/null || { echo "ERROR: qemu-system-aarch64 not installed"; exit 2; }
[ -f "$IMAGE" ] || { echo "ERROR: kernel missing: $IMAGE — run: $HERE/build-kernel.sh $KMI"; exit 2; }

mkdir -p "$CACHE" "$KPBIN"
[ -f "$ALPINE_TAR" ] || { echo "[run-kpm] fetching Alpine minirootfs…"; curl -fsSL "$ALPINE_URL" -o "$ALPINE_TAR"; }

# --- KernelPatch prebuilts (kptools-linux, kpimg-linux) ----------------------
for a in kptools-linux kpimg-linux; do
	if [ ! -f "$KPBIN/$a" ]; then
		echo "[run-kpm] fetching KernelPatch $a…"
		gh release download ${KP_RELEASE:+"$KP_RELEASE"} --repo bmax121/KernelPatch \
			--pattern "$a" -O "$KPBIN/$a" --clobber
	fi
done
chmod +x "$KPBIN/kptools-linux"

# --- build the .kpm if not provided -----------------------------------------
if [ ! -f "$KPM" ]; then
	echo "[run-kpm] building vpnhide.kpm…"
	make -C "$KMOD" kpm >/dev/null
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# boot_phase <args> -> echoes the serial log path; sets globals via files
boot_phase() {
	local args="$1" tag="$2"
	local patched="$WORK/Image.$tag" log="$WORK/serial.$tag.log"

	"$KPBIN/kptools-linux" -p -i "$IMAGE" -k "$KPBIN/kpimg-linux" -S "$SKEY" \
		-M "$KPM" -T kpm ${args:+-A "$args"} -o "$patched" >"$WORK/patch.$tag.log" 2>&1

	local rfs="$WORK/rootfs.$tag"
	mkdir -p "$rfs"
	tar xzf "$ALPINE_TAR" -C "$rfs"
	cp "$HERE/init-kpm.sh" "$rfs/init"
	chmod +x "$rfs/init"
	( cd "$rfs" && find . | cpio -o -H newc 2>/dev/null | gzip > "$WORK/initramfs.$tag.gz" )

	echo "[run-kpm] $KMI: booting phase '$tag' (args='${args}')…" >&2
	timeout 300 qemu-system-aarch64 \
		-machine virt -cpu max -accel tcg,thread=multi,tb-size=1024 \
		-smp 4 -m 2G \
		-kernel "$patched" -initrd "$WORK/initramfs.$tag.gz" \
		-append "console=ttyAMA0 panic=-1 rdinit=/init" \
		-netdev user,id=n0 -device virtio-net-pci,netdev=n0,romfile= \
		-display none -no-reboot -serial "file:$log" >/dev/null 2>&1 || true
	echo "$log"
}

NT_LOG="$(boot_phase "" notarget)"
TG_LOG="$(boot_phase "0" target)"

vec_count() { grep -oE "VEC $1=[0-9]+" "$2" | head -1 | grep -oE '[0-9]+$' || echo "-1"; }
panic_count() { grep -oE 'PANIC=[0-9]+' "$1" | head -1 | grep -oE '[0-9]+$' || echo "1"; }
kpmload() { grep -q 'KPMLOAD=ok' "$1" && echo ok || echo FAIL; }

echo "------------------------- KPM test output -------------------------"
for log in "$NT_LOG" "$TG_LOG"; do
	grep -E 'KREL|KPMLOAD|KVER|IPROUTE2|VEC |PANIC' "$log" 2>/dev/null | sed "s|^|[$(basename "$log")] |" || true
done
echo "-------------------------------------------------------------------"

[ "$(kpmload "$NT_LOG")" = ok ] || { echo "ERROR: KPM did not load (notarget boot)"; tail -20 "$NT_LOG"; exit 1; }
[ "$(kpmload "$TG_LOG")" = ok ] || { echo "ERROR: KPM did not load (target boot)"; tail -20 "$TG_LOG"; exit 1; }

PASS=0; FAIL=0
for vec in proc_route_v4 getifaddrs; do
	nt="$(vec_count "$vec" "$NT_LOG")"
	tg="$(vec_count "$vec" "$TG_LOG")"
	if [ "$nt" -gt 0 ] && [ "$tg" -eq 0 ]; then
		echo "RESULT $vec=PASS (notarget=$nt target=$tg)"; PASS=$((PASS+1))
	else
		echo "RESULT $vec=FAIL (notarget=$nt target=$tg)"; FAIL=$((FAIL+1))
	fi
done

PANIC=$(( $(panic_count "$NT_LOG") + $(panic_count "$TG_LOG") ))
echo "SUMMARY pass=$PASS fail=$FAIL panic=$PANIC"
[ "$FAIL" -eq 0 ] && [ "$PANIC" -eq 0 ] && { echo "[run-kpm] $KMI: PASS"; exit 0; }
echo "[run-kpm] $KMI: FAIL"; exit 1
