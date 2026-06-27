# Per-KMI image for the kmod QEMU test harness.
#
#   ghcr.io/ylarod/ddk-min:<kmi>   (toolchain + module-build headers)
#   + qemu-system-aarch64          (boot the kernel; TCG, no KVM needed)
#   + a prebuilt virtio GKI kernel that boots in `qemu -machine virt`
#   + the Alpine minirootfs tarball (so the test needs no download at run time)
#
# Stock GKI boot images can't boot in plain QEMU (no virtio/console drivers),
# so we build kernel/common@<kmi> with kmod/test/qemu.config merged onto
# gki_defconfig, using the DDK container's matching clang. The module itself is
# NOT baked — it's built per-PR by the kmod CI job and passed in via
# VPNHIDE_QEMU_KO (a kdir-built module loads fine here: same source, same
# vermagic). Built rarely by qemu-image.yml since kernels change seldom.
ARG KMI
FROM ghcr.io/ylarod/ddk-min:${KMI}-20260313
ARG KMI

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        qemu-system-arm cpio gzip curl ca-certificates git \
        bc bison flex libelf-dev libssl-dev \
        gcc-aarch64-linux-gnu && \
    rm -rf /var/lib/apt/lists/*

# Build the kernel and KEEP its tree: the test module must be built against the
# kernel it runs on (matching CFI, vermagic, struct layouts) — building against
# the GKI kdir and running on this separately-built kernel mismatches and panics
# on some KMIs (CFI / struct kretprobe on android16-6.12). Prune .cmd files
# (incremental-rebuild artifacts, ~half the tree, not needed for external
# modules) to keep the image smaller.
COPY kmod/test/qemu.config /tmp/qemu.config
RUN CLANG_BIN="$(ls -d /opt/ddk/clang/*/bin | head -1)" && export PATH="$CLANG_BIN:$PATH" && \
    mkdir -p /opt/qemu && \
    git clone --depth=1 -b "${KMI}" \
        https://android.googlesource.com/kernel/common /opt/qemu/linux && \
    cd /opt/qemu/linux && \
    make ARCH=arm64 LLVM=1 gki_defconfig && \
    ./scripts/kconfig/merge_config.sh -m .config /tmp/qemu.config && \
    make ARCH=arm64 LLVM=1 olddefconfig && \
    make ARCH=arm64 LLVM=1 -j"$(nproc)" Image && \
    make ARCH=arm64 LLVM=1 modules_prepare && \
    cp arch/arm64/boot/Image /opt/qemu/Image && \
    cp vmlinux.symvers Module.symvers && \
    rm -f /tmp/qemu.config && \
    find /opt/qemu/linux -name '*.cmd' -delete

RUN curl -fsSL \
        https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz \
        -o /opt/qemu/alpine-minirootfs.tar.gz

# KernelPatch host tool (kptools) + runtime (kpimg) for the KPM harness
# (run-kpm.sh) — baked at a pinned version so the per-PR kpm-qemu job is
# hermetic (no per-run release download). Same binaries for every KMI.
ARG KP_VER=0.13.1
RUN mkdir -p /opt/qemu/kp && \
    for a in kptools-linux kpimg-linux; do \
        curl -fsSL "https://github.com/bmax121/KernelPatch/releases/download/${KP_VER}/$a" \
            -o "/opt/qemu/kp/$a"; \
    done && chmod +x /opt/qemu/kp/kptools-linux

# run.sh / run-kpm.sh read these to use the baked artifacts instead of the local
# cache. VPNHIDE_QEMU_KSRC is the kernel tree to build the test module against;
# VPNHIDE_KP_BIN is the baked KernelPatch tool/runtime dir (KPM only). The
# harness's static getifaddrs probe builds with the apt aarch64-linux-gnu gcc.
ENV VPNHIDE_QEMU_IMAGE=/opt/qemu/Image
ENV VPNHIDE_QEMU_ROOTFS=/opt/qemu/alpine-minirootfs.tar.gz
ENV VPNHIDE_QEMU_KSRC=/opt/qemu/linux
ENV VPNHIDE_KP_BIN=/opt/qemu/kp
