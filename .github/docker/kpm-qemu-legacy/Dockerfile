# Image for the KPM harness's LEGACY (pre-GKI) targets: 4.14 / 4.19 / 5.4 —
# the kernels the .ko backend can't serve (no GKI, no DDK). Unlike ddk-qemu
# (per-KMI, DDK clang, kept kernel tree), this is ONE lightweight image:
#
#   debian + one Bootlin aarch64 gcc (fetched by build-source-kernel.sh)
#   + the 3 built virtio Images
#   + Alpine minirootfs + KernelPatch tool/runtime + clang (for `make kpm`)
#
# No DDK and no kernel tree: the .kpm is relocatable (built per-PR against the
# KernelPatch submodule), so nothing here is built against the kernel. Built
# rarely (qemu-image.yml) since these kernels are pinned.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        qemu-system-arm cpio gzip xz-utils bzip2 curl ca-certificates git make \
        bc bison flex libelf-dev libssl-dev \
        gcc g++ libc6-dev \
        clang gcc-aarch64-linux-gnu && \
    rm -rf /var/lib/apt/lists/*

# Build the 3 legacy kernels (Bootlin gcc, pinned sources, qemu.config + SW PAN)
# and keep only the Images — drop the fetched sources + toolchain afterwards.
COPY kmod/test /src/kmod/test
RUN cd /src && \
    for v in 4.14 4.19 5.4; do \
        kmod/test/build-source-kernel.sh "$v" "/opt/qemu/legacy/$v"; \
    done && \
    rm -rf /src/kmod/test/.cache

# Alpine rootfs + pinned KernelPatch tool/runtime (same as ddk-qemu).
RUN curl -fsSL \
        https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz \
        -o /opt/qemu/alpine-minirootfs.tar.gz
ARG KP_VER=0.13.1
RUN mkdir -p /opt/qemu/kp && \
    for a in kptools-linux kpimg-linux; do \
        curl -fsSL "https://github.com/bmax121/KernelPatch/releases/download/${KP_VER}/$a" \
            -o "/opt/qemu/kp/$a"; \
    done && chmod +x /opt/qemu/kp/kptools-linux

# run-kpm.sh reads these. The per-version Image lives at
# /opt/qemu/legacy/<ver>/Image — the CI job sets VPNHIDE_QEMU_IMAGE +
# VPNHIDE_QEMU_CPU=cortex-a57 per matrix entry.
ENV VPNHIDE_QEMU_ROOTFS=/opt/qemu/alpine-minirootfs.tar.gz
ENV VPNHIDE_KP_BIN=/opt/qemu/kp
