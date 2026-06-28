#!/usr/bin/env bash
# Build a QEMU-bootable, from-source kernel for the KPM harness's pre-GKI /
# legacy targets — the kernels the `.ko` backend can NOT serve (no GKI, no DDK):
#
#   4.14  (vanilla)         — issue #33; oldest target, rt6_info IPv6 model
#   4.19  (vanilla)         — pre-nexthop, struct fib6_info
#   5.4   (AOSP common)     — issue #35 (HyperOS) target
#
# Unlike build-kernel.sh (GKI built with the per-KMI DDK clang container), all
# three legacy kernels build with ONE pinned Bootlin aarch64 gcc — the compiler
# version doesn't affect struct layout (that's source+config), it's just old
# enough to compile these kernels (a modern gcc/clang trips on -Werror). The
# config is `defconfig` + qemu.config (virtio/console/initrd + software PAN) +
# the IPv6/policy-routing bits the harness's route/rule vectors need, matching
# the offsets in ../kpm/kver_offsets.h.
#
# Output is just an `Image` — the KPM never builds against the kernel tree
# (the `.kpm` is relocatable against KernelPatch headers), so unlike the `.ko`
# harness there is no tree/symvers to keep.
#
# Usage:  build-source-kernel.sh <4.14|4.19|5.4> [outdir]
# Output: <outdir>/Image            (default: .cache/legacy/<ver>/Image)
#
# Boot these with `-cpu cortex-a57` (they fault on `-cpu max`'s newer features
# before the console) and `rodata=off` — run-kpm.sh already does both.
set -euo pipefail

VER="${1:?usage: build-source-kernel.sh <4.14|4.19|5.4> [outdir]}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="$HERE/.cache/legacy"
OUT="${2:-$CACHE/$VER}"
SRCROOT="$CACHE/src"
TCROOT="$CACHE/toolchain"
FRAG="$HERE/qemu.config"
JOBS="$(nproc)"
mkdir -p "$OUT" "$SRCROOT" "$TCROOT"

# --- one Bootlin aarch64 gcc 7.3 for every legacy kernel ----------------------
# Pinned release; the absolute CROSS_COMPILE prefix is used directly (NOT put on
# PATH — the toolchain ships a `bison`/`m4` that expects /opt and shadows the
# host kconfig tools otherwise).
TC="aarch64--glibc--stable-2018.11-1"
TC_URL="https://toolchains.bootlin.com/downloads/releases/toolchains/aarch64/tarballs/${TC}.tar.bz2"
CROSS="$TCROOT/$TC/bin/aarch64-buildroot-linux-gnu-"
if [ ! -x "${CROSS}gcc" ]; then
	echo "[legacy] fetching Bootlin gcc 7.3 ($TC)…"
	curl -fsSL "$TC_URL" | tar xj -C "$TCROOT"
fi

# --- pinned source ------------------------------------------------------------
KORG="https://cdn.kernel.org/pub/linux/kernel/v4.x"
AOSP="https://android.googlesource.com/kernel/common"
case "$VER" in
4.14)
	SRC="$SRCROOT/linux-4.14.336"
	[ -d "$SRC" ] || { echo "[legacy] fetching linux-4.14.336…"; curl -fsSL "$KORG/linux-4.14.336.tar.xz" | tar xJ -C "$SRCROOT"; }
	;;
4.19)
	SRC="$SRCROOT/linux-4.19.325"
	[ -d "$SRC" ] || { echo "[legacy] fetching linux-4.19.325…"; curl -fsSL "$KORG/linux-4.19.325.tar.xz" | tar xJ -C "$SRCROOT"; }
	;;
5.4)
	# AOSP common is a moving branch — pin the exact commit we derived offsets
	# against (android11-5.4.302_r00).
	SRC="$SRCROOT/aosp-5.4"
	AOSP_SHA="91d385eb2a413918a037a93089f8e29910fdf707"
	if [ ! -d "$SRC" ]; then
		echo "[legacy] cloning AOSP common android11-5.4 @ $AOSP_SHA…"
		git clone --depth=1 -b android11-5.4 "$AOSP" "$SRC"
		if git -C "$SRC" fetch --depth=1 origin "$AOSP_SHA"; then
			git -C "$SRC" checkout -q "$AOSP_SHA" || true
		fi
	fi
	;;
*)
	echo "ERROR: unknown version '$VER' (expected 4.14 | 4.19 | 5.4)"; exit 2 ;;
esac

cd "$SRC"
mk() { make ARCH=arm64 CROSS_COMPILE="$CROSS" "$@"; }

# --- config: defconfig + qemu.config + IPv6/policy, per-version tweaks ---------
mk defconfig >/dev/null
./scripts/kconfig/merge_config.sh -m .config "$FRAG" >/dev/null 2>&1

# IPv6 + policy routing built-in so the v6-route / policy-rule vectors have
# something to hide (the generic defconfig ships IPv6 as a module / off).
scripts/config --enable IPV6 \
	--enable IP_ADVANCED_ROUTER --enable IP_MULTIPLE_TABLES \
	--enable IPV6_MULTIPLE_TABLES --enable IPV6_SUBTREES \
	--disable MODULE_SIG --disable TRIM_UNUSED_KSYMS --disable DEBUG_INFO_BTF

# 4.19 only: fib6_info has a CONFIG_IPV6_ROUTER_PREF-gated `last_probe` field,
# and kver_offsets.h pins fib6_nh@176 (the ROUTER_PREF=y, Android-common shape).
[ "$VER" = "4.19" ] && scripts/config --enable IPV6_ROUTER_PREF --enable IPV6_ROUTE_INFO

mk olddefconfig >/dev/null
echo "[legacy] $VER config: $(grep -hE '^CONFIG_(NF_CONNTRACK[=_]|IPV6=|IP_MULTIPLE_TABLES=|IPV6_ROUTER_PREF=|ARM64_SW_TTBR0_PAN=)' .config | tr '\n' ' ')"

# --- build ---
echo "[legacy] $VER: building Image (gcc, -j$JOBS)…"
mk -j"$JOBS" Image
cp arch/arm64/boot/Image "$OUT/Image"
echo "[legacy] $VER done -> $OUT/Image"
