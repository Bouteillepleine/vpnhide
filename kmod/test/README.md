# kmod QEMU test harness

Boots a real GKI kernel under `qemu-system-aarch64` and runs the vpnhide
kernel module against fabricated VPN state, asserting that every detection
vector is hidden from a *target* UID but still visible to a *non-target* UID.

This exists because compilation and source-level signature checks are **not
sufficient** for this module: it reads syscall/netlink arguments straight out
of `pt_regs` by register index, so correctness depends on the *actual* argument
→ register mapping in the built kernel — which a compiler can change (notably
for `static`, directly-called functions). Only running the module on the real
kernel proves the registers are right. (This is exactly how the `rt_fill_info`
register bug was found.)

## What it checks (and what it can't)

Per kernel version, the harness validates:

- the module **loads** (symbol resolution against that kernel),
- all hooks **register**,
- each detection vector is actually **filtered** for a target UID (A/B: a
  non-target sees the fabricated `vpn0`, a target does not),
- **no kernel panic** across the whole hook set.

Vectors exercised (`init.sh`):

| Vector | Trigger | Hook |
|---|---|---|
| getifaddrs | `ip addr show` | `rtnl_fill_ifinfo` + `inet*_fill_ifaddr` |
| SIOCGIFCONF | `ifconfig -a` | `sock_ioctl` |
| /proc/net/route | `cat /proc/net/route` | `fib_route_seq_show` |
| /proc/net/ipv6_route | `cat /proc/net/ipv6_route` | `ipv6_route_seq_show` |
| netlink route dump v4 | `ip route show table all` | `fib_dump_info` |
| netlink route dump v6 | `ip -6 route show table all` | `rt6_fill_node` |
| policy rules | `ip rule show` | `fib_nl_fill_rule` |

**Limits:** GitHub/QEMU runners have no KVM, so the VM runs under TCG (software
emulation) — correct, just slow. The test kernel is *our* `kernel/common` build
with `qemu.config` merged, not a byte-identical vendor GKI release, so it does
not cover vendor patches. A device smoke-test is still the final word — but the
gate is far tighter than "it compiled".

LTO is left to each KMI's `gki_defconfig` (not pinned in `qemu.config`), which
is the device-faithful setting and varies by generation: android12/13-5.10 +
5.15 ship **full LTO + CFI** (5.10's CFI requires LTO), while android14-6.1+
ship **LTO_NONE + CFI via KCFI** (confirmed against the shipped Pixel 8 Pro
factory image). Forcing one mode breaks both fidelity and the build — e.g.
forcing `LTO_NONE` on 5.10 also drops CFI (unmet dependency), yielding a kernel
no device runs and on which a GKI-built module won't register its kretprobes.
The full-LTO generations are heavier to build; `qemu-image.yml` frees disk and
adds swap to fit them.

## Usage

```sh
# 1. Build a bootable kernel + module for a KMI (slow; clones kernel/common,
#    builds Image with the DDK container's clang, caches under .cache/<kmi>/).
kmod/test/build-kernel.sh android12-5.10

# 2. Boot it in QEMU and run the vector tests.
kmod/test/run.sh android12-5.10
```

`run.sh` exits 0 only if every vector passes and there was no panic. Artifacts
(kernels, modules, the Alpine rootfs) are cached under `.cache/` (gitignored).

Requirements: `docker` (for build-kernel.sh), `qemu-system-aarch64`, `cpio`,
`curl`.

## CI

Building a virtio GKI kernel takes ~30 min, so it must not run per-PR. Instead
the bootable kernel + Alpine rootfs are **baked** into per-KMI images
(`.github/docker/ddk-qemu/Dockerfile` = `FROM ddk-min:<kmi>` + qemu + Image +
rootfs), built rarely by `.github/workflows/qemu-image.yml` (matrix over the 7
KMIs; triggers on `qemu.config`/Dockerfile changes, monthly, or manually).

The module is **not** baked — a `kdir`-built module (what the `kmod` job already
produces for devices) loads on the baked virtio kernel (same source → same
vermagic), so CI reuses that artifact and passes it in via `VPNHIDE_QEMU_KO`.
`run.sh` reads `VPNHIDE_QEMU_IMAGE` / `VPNHIDE_QEMU_ROOTFS` / `VPNHIDE_QEMU_KO`
to use the baked artifacts instead of the local cache.

GitHub runners have no KVM, so QEMU runs under TCG — slow but fine (~1-3 min per
job, matrix runs in parallel). `init.sh` apk-adds iproute2 over QEMU user-mode
networking (the runner has internet); prebaking iproute2 into the rootfs is a
possible later optimization if the Alpine CDN proves flaky.

### Rollout (two steps — images must exist before the gate)

1. Merge this (Dockerfile + workflow + harness), then run **qemu-image.yml**
   once (it triggers on the Dockerfile/`qemu.config` paths, or dispatch it
   manually) so `ghcr.io/<owner>/vpnhide/ddk-qemu:<kmi>` exist for all 7 KMIs.
2. Add the gate job to `ci.yml` (so PRs don't reference images that don't exist
   yet):

   ```yaml
   kmod-qemu:
     needs: kmod
     runs-on: ubuntu-latest
     strategy:
       fail-fast: false
       matrix:
         kmi: [android12-5.10, android13-5.10, android13-5.15,
               android14-5.15, android14-6.1, android15-6.6, android16-6.12]
     container:
       image: ghcr.io/${{ github.repository }}/ddk-qemu:${{ matrix.kmi }}
     steps:
       - uses: actions/checkout@v7
       - uses: actions/download-artifact@v7
         with:
           name: vpnhide-kmod-${{ matrix.kmi }}
       - name: Extract module
         run: unzip -p vpnhide-kmod-${{ matrix.kmi }}.zip vpnhide_kmod.ko > /tmp/vpnhide_kmod.ko
       - name: QEMU runtime test
         env:
           VPNHIDE_QEMU_KO: /tmp/vpnhide_kmod.ko
         run: kmod/test/run.sh ${{ matrix.kmi }}
   ```

   (`VPNHIDE_QEMU_IMAGE`/`VPNHIDE_QEMU_ROOTFS` come from the image's `ENV`.)
