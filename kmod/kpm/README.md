# vpnhide — KPM backend (KernelPatch Module) · **WIP skeleton**

A third native backend alongside the kretprobe `.ko`. Same job — hide VPN
interfaces from selected UIDs at the kernel level — for the kernels the
`.ko` **can't** serve.

## Why a KPM at all

The `.ko` needs per-GKI kernel headers + `Module.symvers` (the DDK build
matrix) and a kernel that allows loading unsigned modules. That leaves a
real gap:

- **Non-GKI / old kernels** — e.g. **4.14** ([#33](https://github.com/okhsunrog/vpnhide/issues/33)): no GKI KMI, no DDK build.
- **Proprietary kernels with no source or headers** — e.g. **HyperOS 5.4**
  (the crash report on [#35](https://github.com/okhsunrog/vpnhide/issues/35)): can't build a `.ko`, can't patch the source.
- **Locked-down module signing** — `insmod` rejected; a KPM is loaded via
  the KernelPatch supercall, not the module loader.

A KPM is compiled against KernelPatch's *own* headers (`-nostdinc`), needs
**no kernel headers and no `Module.symvers`**, resolves every kernel symbol
at load time via `kallsyms_lookup_name`, and installs **inline** hooks
(cheaper than kretprobe traps, with first-class arg/return rewriting).

This is **additive, not a replacement.** Mainstream GKI users stay on the
QEMU-tested `.ko` (no extra dependency). The KPM extends reach to the
segment above, at the cost of a KernelPatch runtime dependency.

## Architecture (and how it differs from the community prototypes)

```
kmod/
  ../data/interfaces.toml        # single source of truth (4 languages)
  generated/iface_lists.h        # shared VPN-name matcher  (incl. if<N>, #86)
  shared/vpnhide_logic.h         # shared filtering algorithms (freestanding)
  vpnhide_kmod.c                 # backend A: kretprobe .ko (unchanged)
  kpm/
    vpnhide_kpm.c                # backend C: KPM glue (hooks, proc, lifecycle)
    kver_offsets.h               # runtime per-version struct-offset table
    README.md                    # this file
```

| Layer | Shared? | Notes |
|---|---|---|
| VPN-name matcher | ✅ as-is | `generated/iface_lists.h`, from `data/interfaces.toml` |
| Filtering algorithms | ✅ `shared/vpnhide_logic.h` | seq-buffer compaction, UID parse — freestanding, included by both backends |
| Hook glue / struct access / proc | ❌ per-backend | incompatible include worlds (`<linux/*.h>` vs KernelPatch `-nostdinc`) |

Three deliberate improvements over [soranerai's prototype](https://github.com/soranerai/vpnhide/tree/kernelpatch-(outdated)) (which did the
legwork and tested on-device — credit due):

1. **One source + a runtime `kver` offset table** (`kver_offsets.h`), not
   three near-identical per-version files → one binary for 4.x/5.x/6.x.
2. **Per-call state via `fargs->local.dataN`**, not a per-CPU MPIDR stash
   (which races when a thread migrates between the before/after callback).
3. **Reuse the generated matcher** — gets the `if<N>` pattern (#86) the
   hardcoded community lists miss; and **don't hook `rt_fill_info`** (our
   QEMU harness proved its arg→register ABI is unstable).

## KernelPatch API used

- Module ABI: `KPM_NAME/VERSION/LICENSE/AUTHOR/DESCRIPTION`, `KPM_INIT`,
  `KPM_CTL0`, `KPM_EXIT` (`<kpmodule.h>`).
- Hooking: `hook_wrap(func, argno, before, after, udata)` /
  `hook_unwrap(...)`; callbacks get `hook_fargsN_t *` with `arg0..argN`,
  writable `ret`, `skip_origin`, and `local.data0..7` (`<hook.h>`).
- Symbols: runtime `kallsyms_lookup_name` (incl. static functions);
  `kfunc_match_cfi` for CFI jump tables (`<kallsyms.h>`, `<ksyms.h>`).
- Control: userspace talks to the module via the supercall (`syscall 45`)
  → `sc_kpm_control` → the module's `KPM_CTL0` handler.

## Build

A KPM is a relocatable object built with clang against the KernelPatch
header tree — **no kernel source needed**:

```sh
git clone --depth 1 https://github.com/bmax121/KernelPatch
make -C kmod kpm KP_DIR=$PWD/KernelPatch
```

This **works today** — `vpnhide.kpm` builds against `bmax121/KernelPatch`
and emits a valid KPM ELF (`.kpm.info` / `.kpm.init` / `.kpm.ctl0` /
`.kpm.exit` sections, metadata populated). See `../Makefile` (`kpm*`
targets). Include dirs mirror KernelPatch's own `kpms/*/Makefile`.

**TODO**: vendor the KernelPatch headers as a submodule / `third_party/`
drop + a `build.py` KPM path, so CI builds the `.kpm` reproducibly (like
`zygisk/third_party/`). Building != working — see Status.

## Deploy

- Runtime: **KPatch-Next on KernelSU-Next** (so existing KSU-Next users
  add KPM support by flashing one module — no switch to APatch). The same
  `.kpm` also loads under APatch. Target the upstream `kpm.h` ABI.
- Persistence: a runtime `sc_kpm_load` is **lost on reboot**. Either
  **embed** the `.kpm` in the patched `boot.img`, or re-`sc_kpm_load` each
  boot from `post-fs-data.sh` (same shape as the `.ko`'s `insmod`). The
  control plane (`targets.txt` → `/proc/vpnhide_targets` → live reload) is
  identical to the `.ko`.

## Safety — read before testing on a device

Inline hooks have **no kprobe safety net**: a wrong offset in
`kver_offsets.h` or a mismatched kernel **corrupts kernel text → panic /
bootloop**, where the `.ko`'s kretprobe would just fail to register. So:

- **Every offset and every hook must pass the QEMU KPM harness first**
  (`../test/run-kpm.sh` — patches a KernelPatch kernel, boots it, runs the
  A/B vector assertions: target UID sees nothing, non-target sees `vpn0`, no
  panic). This harness is the whole reason the `.ko` is trusted; the KPM has
  the equivalent now, and a new offset table for a version is only as
  trustworthy as a green harness run on that version.
- Test with ephemeral **Load** (lost on reboot) before **Embed**.
- Patch the **inactive A/B slot** so a bad build falls back to the
  unpatched kernel.

## Status & backlog

- [x] Shared filtering logic extracted (`../shared/vpnhide_logic.h`)
- [x] KPM skeleton + 2 PoC hooks (`fib_route_seq_show`, `rtnl_fill_ifinfo`)
- [x] **Compiles against `bmax121/KernelPatch` → valid `.kpm` ELF** (self-contained matcher; `hook_fargs12_t` is the max bucket — rtnl reads arg0/arg1 only)
- [x] **Runtime `kver` detection** from KernelPatch's `kver` (common.h)
- [x] Target UIDs via load-args / `ctl0` supercall (reuses the shared parser)
- [x] **QEMU KPM harness** (`../test/run-kpm.sh`) — patches a GKI Image with
      KernelPatch, embeds the `.kpm`, boots under QEMU, two-boot A/B. **Both
      PoC hooks PASS on android12-5.10**: root sees `vpn0` when not targeted,
      not when targeted; no panic. Validates the inline hooks + the 5.x
      offsets (skb.len=104) + `fargs->local` state passing on a real kernel.
- [x] **All 10 hooks ported + QEMU-validated** on android12-5.10 (no panic),
      full native-vector parity with the `.ko`: `fib_route_seq_show`,
      `ipv6_route_seq_show`, `rtnl_fill_ifinfo`, `inet_fill_ifaddr`,
      `inet6_fill_ifaddr`, `dev_ioctl`, `sock_ioctl`, `fib_dump_info` (#86),
      `rt6_fill_node`, `fib_nl_fill_rule`. The deep-struct ones use 5.10
      offsets derived from source (`fib_info`, `fib6_info`, `inet6_ifaddr`,
      `fib_rule`); a static `getifaddrs()` probe (`gai-probe.c`) proves the
      address path is closed (target getifaddrs vpn0: 3 → 0).
- [x] Runtime kver offset table (`kver_offsets.h`) — **5.10 + 5.4 + 4.19 + 4.14 (all full)**
- [x] **4.14 now full parity, QEMU-validated 9/9** (was 7). The oldest/most
      divergent target: IPv4 route dump via the legacy arg-9 fib_info (no
      nexthop objects); IPv6 route dump hooks `rt6_fill_node(struct rt6_info*)`
      — pre-`fib6_info`, so the dev is read straight from the embedded
      `dst_entry` (`rt6_via_dst`, dev@0); policy rules resolve through the
      `.isra` fuzzy fallback. `skb.len`@104 (older sk_buff head).
- [x] **4.19 (vanilla 4.19.325) — full parity, QEMU-validated 9/9.** Pre-nexthop
      kernel: fib_info/fib6_info have no `struct nexthop` field (the nh guards
      skip), fib_dump_info uses the legacy `<5.6` prototype (fib_info* at arg 9,
      shared with 5.4). Already has the `struct fib6_info` IPv6 model (4.14 does
      not). One config-sensitive offset: `fib6_nh.nh_dev` sits at +16 in
      `fib6_nh` (pre-`fib_nh_common`), and `CONFIG_IPV6_ROUTER_PREF` (Android-
      common, validated =y) shifts `fib6_nh` to @160 → dev@176.
- [x] **5.4 (android11-5.4) — full parity, QEMU-validated 9/9** on a from-source
      `5.4.302` Image. All vectors pass incl. both route dumps and policy rules.
      Notable per-version work that landed here:
      - `fib_dump_info` is the legacy `<5.6` prototype on 5.4 (fib_info passed
        *directly* at arg 9, not via `fib_rt_info`) — the hook is now
        table-driven (`fib_dump_fi_arg` / `fib_dump_fi_via_fri`) and unified on
        a 12-arg frame, so one callback serves 5.4 and 5.10.
      - `struct fib6_info` has no `ANDROID_KABI_RESERVE` here → `fib6_nh[]`@160
        (5.10 is 168); `inet6_ifaddr.idev`@168; `skb.len`@112 (conntrack on).
      - **Fuzzy symbol resolution**: gcc renames static fns to `name.isra.N` /
        `name.constprop.N`, which `kallsyms_lookup_name` misses. Hook lookups
        now fall back to the `name.`-prefixed clone (`kallsyms_on_each_symbol`),
        so e.g. `fib_nl_fill_rule.isra.21` is hooked. Clang device kernels keep
        the plain name (exact match wins first); this just hardens gcc kernels.
- [ ] proc_ops vs file_operations mock per kver (the HyperOS-5.4 crash class) — A/B currently uses load-args, so proc isn't on the critical path
- [ ] **Offset table for 6.1** — soranerai has on-device values; complete the
      route-dump (fib_info/fib6_info) entries + validate via the harness.
- [ ] Confirm offsets on the closed-kernel targets with soranerai & cyberc3dr (real devices)
- [ ] `build.py` KPM path + wire `run-kpm.sh` into CI (qemu-image job)
- [ ] Wire the `.ko` to `../shared/vpnhide_logic.h` (mechanical; gate on a local `.ko` harness run)

## Credits

- [soranerai](https://github.com/soranerai/vpnhide) — first working KPM ports (4.14/5.4/6.1) + on-device testing.
- [cyberc3dr](https://github.com/cyberc3dr/vpnhide-driver) — in-tree kernel patches + a real proprietary-kernel (HyperOS) test device.
- [bmax121/KernelPatch](https://github.com/bmax121/KernelPatch) & [KernelSU-Next/KPatch-Next](https://github.com/KernelSU-Next/KPatch-Next) — the runtime.
