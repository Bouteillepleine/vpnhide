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
register bug was found — see Design decisions.)

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

## Usage

```sh
# 1. Build a bootable kernel + a matching module for a KMI (slow, ~15-40 min;
#    clones kernel/common, builds Image with the DDK container's clang, builds
#    the module against that tree, caches under .cache/<kmi>/).
kmod/test/build-kernel.sh android12-5.10

# 2. Boot it in QEMU and run the vector tests.
kmod/test/run.sh android12-5.10
```

`run.sh` exits 0 only if every vector passes and there was no panic. Artifacts
(kernels, modules, the Alpine rootfs) are cached under `.cache/` (gitignored).
`run.sh` honors `VPNHIDE_QEMU_IMAGE` / `VPNHIDE_QEMU_ROOTFS` / `VPNHIDE_QEMU_KO`
to use prebuilt artifacts (CI) instead of the local cache.

Requirements: `docker` (for build-kernel.sh), `qemu-system-aarch64`, `cpio`,
`curl`.

## How CI uses it

Building a virtio GKI kernel takes ~15-40 min, so it must not run per-PR:

- **`.github/workflows/qemu-image.yml`** bakes per-KMI images
  `ghcr.io/<owner>/vpnhide/ddk-qemu:<kmi>` = `FROM ddk-min:<kmi>` + qemu + the
  built kernel `Image` + **its build tree** (`/opt/qemu/linux`, for module
  builds) + the Alpine rootfs. Matrix over the 7 KMIs; runs only on
  `qemu.config`/Dockerfile changes, monthly, or manual dispatch. Full-LTO
  generations are heavy — the workflow frees disk + adds swap.
- **`ci.yml` `kmod-qemu` job** (per KMI) boots the baked kernel and runs the
  vectors. It builds the module **against the baked kernel tree**
  (`VPNHIDE_QEMU_KSRC`), not the GKI kdir — see Design decisions. The image is
  repo-owned (private), so the job pulls it with `credentials:` and a
  lowercased name from the `setup` job output, like the other image jobs.

## Design decisions

Each of these was forced by a concrete failure; they are easy to "simplify"
back into a regression.

1. **Build the module against the baked kernel tree, not the GKI kdir.**
   A module must match the kernel it runs on (CFI tags, vermagic, the
   `struct kretprobe` layout). The kdir (what ships to devices) and our
   separately-built QEMU kernel are *different builds*; on `android16-6.12`
   their configs diverge enough to be fatal: with CFI on, insmod hits a CFI
   type-id mismatch panic; with CFI off, a `struct kretprobe` mismatch crashes
   `pre_handler_kretprobe` (NULL deref). 5.10/6.1/6.6 only matched by luck.
   So the image keeps the kernel tree and the module is built against it.

2. **Make the baked tree a complete external-module build environment.**
   `make Image` alone is not enough. Also run `make modules_prepare` (generates
   `scripts/module.lds`, else modfinal: "No rule to make target …ko") and
   `cp vmlinux.symvers Module.symvers` (else modpost reports every kernel symbol
   undefined — `make Image` emits only `vmlinux.symvers`, and our module
   references only vmlinux symbols). `.cmd` files are pruned to shrink the image.

3. **Do not force LTO — inherit it from each KMI's `gki_defconfig`.**
   That is the device-faithful setting and it varies: android12/13-5.10 + 5.15
   ship **full LTO + CFI** (5.10's CFI *requires* LTO), android14-6.1+ ship
   **LTO_NONE + CFI via KCFI** (confirmed against the shipped Pixel 8 Pro
   factory image: `CONFIG_LTO_NONE=y`, `CONFIG_CFI_CLANG=y`). Forcing one mode
   breaks both fidelity and the build (e.g. forcing `LTO_NONE` on 5.10 drops CFI
   as an unmet dependency). LTO barely affects the harness anyway — every hooked
   function is global, address-taken, or too large to inline.

4. **CFI stays on.** With the module built against the baked tree (decision 1)
   it gets matching KCFI and loads fine. Disabling CFI was a wrong turn — it
   only traded the CFI panic for the kretprobe-struct panic.

5. **Disable BTF (`CONFIG_DEBUG_INFO_BTF` off).** The BTFIDS step
   (`resolve_btfids` over vmlinux) fails `Error 255` on some GKI tips with the
   DDK container's pahole. BTF isn't needed for these tests, so dropping it
   removes a flaky build step and lightens the build.

6. **`rt_fill_info` is intentionally not hooked.** It is a `static`,
   directly-called function with no stable arg→register ABI (verified in QEMU:
   `regs[3]` held `table_id`, not the `rtable*`). Route *enumeration* (what
   detection apps use) goes through the global `fib_dump_info`; single lookups
   respect the caller's routing, which is physical under split-tunnel. See
   `docs/ROADMAP.md` for the `rtnl_unicast`-based alternative if ever needed.

7. **iproute2 over QEMU user-net.** `init.sh` apk-adds iproute2 at boot (the
   runner has internet via QEMU's slirp NAT). Prebaking it into the rootfs is a
   possible optimization if the Alpine CDN proves flaky.
