# vpnhide — KPM backend (KernelPatch Module)

A third native backend alongside the kretprobe `.ko`. Same job — hide VPN
interfaces from selected UIDs at the kernel level — for the kernels the
`.ko` **can't** serve.

## Why a KPM at all

The `.ko` needs per-GKI kernel headers + `Module.symvers` (the DDK build
matrix) and a kernel that allows loading unsigned modules. That leaves a
real gap:

- **Non-GKI / old kernels** — e.g. **4.9 and 4.14** ([#33](https://github.com/okhsunrog/vpnhide/issues/33)): no GKI KMI, no DDK build.
- **Proprietary kernels with no source or headers** — e.g. **HyperOS 5.4**
  (the crash report on [#35](https://github.com/okhsunrog/vpnhide/issues/35)): can't build a `.ko`, can't patch the source.
- **Locked-down module signing** — `insmod` rejected; a KPM is loaded via
  the KernelPatch supercall, not the module loader.

A KPM is compiled against KernelPatch's *own* headers (`-nostdinc`), needs
**no kernel headers and no `Module.symvers`**, resolves its required hook targets
at load time via `kallsyms_lookup_name`, and installs **inline** hooks with
direct argument and return-value rewriting.

This is **additive, not a replacement.** Mainstream GKI users stay on the
QEMU-tested `.ko` (no extra dependency). The KPM extends reach to the
segment above, at the cost of a KernelPatch runtime dependency.

## Architecture

```
kmod/
  ../data/interfaces.toml        # single source of truth (4 languages)
  generated/iface_lists.h        # shared VPN-name matcher  (incl. if<N>, #86)
  shared/vpnhide_logic.h         # shared filtering algorithms (freestanding)
  vpnhide_kmod.c                 # backend A: kretprobes + bind entry redirect
  kpm/
    vpnhide_kpm.c                # backend C: KPM hooks, control, lifecycle
    kver_offsets.h               # runtime per-version struct-offset table
    README.md                    # this file
```

| Layer | Shared? | Notes |
|---|---|---|
| VPN-name matcher | ✅ as-is | `generated/iface_lists.h`, from `data/interfaces.toml` |
| Filtering algorithms | ✅ `shared/vpnhide_logic.h` | seq-buffer compaction, UID parse — freestanding, included by both backends |
| Hook glue / struct access / control | ❌ per-backend | incompatible include worlds (`<linux/*.h>` vs KernelPatch `-nostdinc`) |

The implementation relies on four design rules:

1. **One source + a runtime `kver` offset table** (`kver_offsets.h`) produces
   one binary for the supported Android kernel families: 4.9, 4.14, 4.19,
   5.4, 5.10, 5.15, 6.1, 6.6, and 6.12. Other minor families are rejected
   rather than guessed from a nearby layout.
2. **Per-call state via `fargs->local.dataN`** keeps before/after callback
   state attached to the invocation even if the task migrates between CPUs.
3. **Reuse the generated matcher** — gets the `if<N>` pattern (#86) the
   generated interface list defines; `rt_fill_info` remains unhooked because
   its argument/register mapping is not stable across tested kernels.
4. **Prefer the kernel's user-copy wrappers.** If a build inlines those
   wrappers and exposes only the raw architecture routines, the KPM uses the
   raw routines only on 4.9/4.14/4.19 (where they bracket user access themselves)
   or when the kernel reports hardware PAN support.

## KernelPatch API used

- Module ABI: `KPM_NAME/VERSION/LICENSE/AUTHOR/DESCRIPTION`, `KPM_INIT`,
  `KPM_CTL0`, `KPM_EXIT` (`<kpmodule.h>`).
- Hooking: `hook_wrap(func, argno, before, after, udata)` /
  `hook_unwrap(...)`; callbacks get `hook_fargsN_t *` with `arg0..argN`,
  writable `ret`, `skip_origin`, and `local.data0..7` (`<hook.h>`).
- Symbols: runtime `kallsyms_lookup_name`, with a bounded fallback for GCC
  `.isra.N` / `.constprop.N` clones (`<kallsyms.h>`).
- Control: userspace talks to the module via the supercall (`syscall 45`)
  → `sc_kpm_control` → the module's `KPM_CTL0` handler.

## Build

A KPM is a relocatable object built with clang against the KernelPatch
header tree — **no kernel source needed**. The default build uses the pinned
`kmod/third_party/KernelPatch` submodule:

```sh
git submodule update --init kmod/third_party/KernelPatch
make -C kmod kpm
```

To test against a different KernelPatch checkout, override `KP_DIR`:

```sh
make -C kmod kpm KP_DIR=/path/to/KernelPatch
```

The flashable module zip is built by:

```sh
python3 kmod/kpm/build.py
```

That script builds the Android `kpm` activator, builds `vpnhide.kpm`, stages
`kmod/kpm/module/`, stamps `module.prop`, and writes `vpnhide-kpm.zip` at the
repo root. CI uses the same submodule-backed build.

## Deploy

A KPM runs on **inline hooks**, which physically require the KernelPatch
runtime (`kpimg`) to be embedded in the kernel — there is **no** "KPM on a
stock kernel". The `boot.img` always gets patched; the only question is which
root solution drives that patch. The `.kpm` itself is the same binary
everywhere (target the upstream `kpm.h` ABI).

Supported runtimes — pick whichever matches the device's root:

- **APatch / FolkPatch** — KernelPatch is built in; the flashable zip installs
  as an APatch/APM module, and the vpnhide activator loads/configures
  `vpnhide.kpm` through direct KernelPatch supercalls with the saved APatch
  SuperKey or the runtime's trusted `su` token when available.
- **KernelSU-Next** — flash **KPatch-Next** (one module, no switch to APatch).
- **Magisk or stock KernelSU** — flash the standalone
  [KPatch-Next-Module](https://github.com/KernelSU-Next/KPatch-Next-Module).
  It bundles `kpimg` + `kptools` + `magiskboot`, patches `boot.img` from its
  WebUI to embed KernelPatch, then (`service.sh`) auto-loads every
  `/data/adb/kp-next/kpm/*.kpm` on each boot via `kpatch kpm load`. So plain
  Magisk / KernelSU users get KPM support without changing root solution —
  the kernel is still patched, it's just automated. (Conflicts with APatch,
  which already ships KernelPatch.)

Persistence: a one-shot runtime `sc_kpm_load` is **lost on reboot**. The
vpnhide KPM module therefore ships `vpnhide.kpm` plus boot scripts: KPatch-Next
loads via its runtime `kpatch` CLI, while APatch/FolkPatch defers to the
activator and uses the saved SuperKey or trusted `su` token when present.

Targeting / control plane: our target-UID set is delivered via the module's
own `KPM_CTL0` supercall + load-args (the shape the QEMU harness exercises) —
this is independent of KPatch-Next's generic `package_config` →
`kpatch exclude_set <uid>` mechanism. The app stores package roles in
`/data/system/vpnhide_config.json`; the KPM activator resolves that canonical
config and pushes the same text wire through APatch/FolkPatch direct supercalls
or KPatch-Next `kpatch kpm ctl0`.

## Safety — read before testing on a device

Inline hooks have **no kprobe safety net**: a wrong field offset in
`kver_offsets.h` can cause invalid kernel-memory access or data corruption, and
a mismatched hook ABI can panic or bootloop the kernel. The `.ko` avoids the
offset table but can still fail to register a probe. Therefore:

- **Every offset and every hook must pass the QEMU KPM harness first**
  (`../test/run-kpm.sh` — patches a KernelPatch kernel, boots it, runs the
  A/B vector assertions: target UID sees nothing, non-target sees `vpn0`, no
  panic). This is a repeatable pre-merge regression gate, not a substitute for
  testing a particular vendor kernel and device configuration.
- Test with ephemeral **Load** (lost on reboot) before **Embed**.
- Patch the **inactive A/B slot** so a bad build falls back to the
  unpatched kernel.

## Validation and support boundary

The KPM implements the same 11 logical kernel hooks as the `.ko`, including
pre-mutation denial of `SO_BINDTODEVICE` and `SO_BINDTOIFINDEX`. The bind probe
checks socket state from another UID so an errno-only override cannot pass.
Shared host tests cover the filtering and wire-format logic.

CI builds one relocatable `.kpm`, embeds it with KernelPatch, and boots these
reference images:

| Reference image | Source/build path |
|---|---|
| android10-4.9 | pinned AOSP common `deprecated/android-4.9-q`, Cuttlefish config |
| android10-4.14 | pinned AOSP common `deprecated/android-4.14-q`, Cuttlefish config |
| android10-4.19 | pinned AOSP common `deprecated/android-4.19-q`, Cuttlefish config |
| android11-5.4 | pinned AOSP common source, GKI config |
| android12-5.10 | Android DDK GKI |
| android13-5.10 | Android DDK GKI |
| android13-5.15 | Android DDK GKI |
| android14-5.15 | Android DDK GKI |
| android14-6.1 | Android DDK GKI |
| android15-6.6 | Android DDK GKI |
| android16-6.12 | Android DDK GKI |

For each image, the harness runs target/non-target A/B checks for interface,
address, route, policy-rule, ioctl, and socket-bind behavior and checks for a
kernel panic. The field offsets in `kver_offsets.h` come from the corresponding
kernel sources and are accepted only after this harness passes.

This matrix validates those reference kernel configurations under QEMU. Vendor
trees can change structure layouts, compiler-generated symbols, configs, or CFI
behavior without changing the reported kernel version. Therefore the version
selector is a compatibility policy, not certification of every device in that
range. Test a specific device with an ephemeral KPM load before embedding it.

The KernelPatch submodule makes `make -C kmod kpm` reproducible without an
external checkout. `kmod/kpm/build.py` packages the same artifact and the
activator as `vpnhide-kpm.zip`; CI uses those repository entry points.

## Credits

- [soranerai](https://github.com/soranerai/vpnhide) — earlier KPM prototype
  referenced during the initial backend design.
- [cyberc3dr](https://github.com/cyberc3dr/vpnhide-driver) — earlier in-tree
  kernel implementation referenced during the initial backend design.
- [bmax121/KernelPatch](https://github.com/bmax121/KernelPatch) and
  [KernelSU-Next/KPatch-Next](https://github.com/KernelSU-Next/KPatch-Next) —
  KernelPatch runtimes and module APIs used by this backend.
