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
gate is far tighter than "it compiled". With LTO left on (the gki_defconfig
default), inlining and static-function calling conventions match real devices.

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

Building a virtio GKI kernel takes ~30 min, so it must not run per-PR. The plan
(next change) is to **bake** the per-KMI bootable kernel + a ready rootfs into
per-KMI images (`FROM ghcr.io/ylarod/ddk-min:<kmi>` + qemu + Image + rootfs),
rebuilt rarely like `ci-image.yml`. The kmod CI matrix then, per KMI, builds the
module and boots QEMU with the baked kernel — turning the build-only matrix into
a real per-version runtime gate.
