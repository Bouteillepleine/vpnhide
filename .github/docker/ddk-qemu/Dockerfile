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
        bc bison flex libelf-dev libssl-dev && \
    rm -rf /var/lib/apt/lists/*

COPY kmod/test/qemu.config /tmp/qemu.config
RUN CLANG_BIN="$(ls -d /opt/ddk/clang/*/bin | head -1)" && export PATH="$CLANG_BIN:$PATH" && \
    git clone --depth=1 -b "${KMI}" \
        https://android.googlesource.com/kernel/common /tmp/linux && \
    cd /tmp/linux && \
    make ARCH=arm64 LLVM=1 gki_defconfig && \
    ./scripts/kconfig/merge_config.sh -m .config /tmp/qemu.config && \
    make ARCH=arm64 LLVM=1 olddefconfig && \
    make ARCH=arm64 LLVM=1 -j"$(nproc)" Image && \
    mkdir -p /opt/qemu && \
    cp arch/arm64/boot/Image /opt/qemu/Image && \
    cd / && rm -rf /tmp/linux /tmp/qemu.config

RUN curl -fsSL \
        https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz \
        -o /opt/qemu/alpine-minirootfs.tar.gz

# run.sh reads these to use the baked kernel + rootfs instead of the local cache.
ENV VPNHIDE_QEMU_IMAGE=/opt/qemu/Image
ENV VPNHIDE_QEMU_ROOTFS=/opt/qemu/alpine-minirootfs.tar.gz
