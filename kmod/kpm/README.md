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

- **Every offset and every hook must pass the QEMU KPM harness first.**
  TODO: extend `kmod/test/` to boot a KernelPatch-patched kernel and run
  the same A/B vector assertions (target UID sees nothing, non-target sees
  `vpn0`, no panic). This harness is the whole reason the `.ko` is trusted;
  the KPM needs the equivalent before it touches a daily-driver.
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
- [x] Runtime kver offset table (`kver_offsets.h`) — **5.x + 6.1**; 4.14 TODO
- [x] **5/10 hooks ported + QEMU-validated** (android12-5.10, no panic): the
      offset-safe set — `fib_route_seq_show`, `rtnl_fill_ifinfo`,
      `ipv6_route_seq_show`, `dev_ioctl`, `sock_ioctl`
- [ ] proc_ops vs file_operations mock per kver (the HyperOS-5.4 crash class) — A/B currently uses load-args, so proc isn't on the critical path
- [ ] Remaining 5 hooks — `inet_fill_ifaddr`, `inet6_fill_ifaddr`,
      `fib_dump_info` (#86), `rt6_fill_node`, `fib_nl_fill_rule` — these
      dereference version-specific kernel structs, so each needs a per-kver
      offset (derive from GKI source, tune against the harness)
- [ ] Confirm 4.14 + deeper struct offsets (with soranerai & cyberc3dr — closed-kernel devices)
- [ ] `build.py` KPM path + wire `run-kpm.sh` into CI (qemu-image job)
- [ ] Wire the `.ko` to `../shared/vpnhide_logic.h` (mechanical; gate on a local `.ko` harness run)

## Credits

- [soranerai](https://github.com/soranerai/vpnhide) — first working KPM ports (4.14/5.4/6.1) + on-device testing.
- [cyberc3dr](https://github.com/cyberc3dr/vpnhide-driver) — in-tree kernel patches + a real proprietary-kernel (HyperOS) test device.
- [bmax121/KernelPatch](https://github.com/bmax121/KernelPatch) & [KernelSU-Next/KPatch-Next](https://github.com/KernelSU-Next/KPatch-Next) — the runtime.
