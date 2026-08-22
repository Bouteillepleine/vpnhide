# Building vpnhide-kmod

Most users should download pre-built modules from [Releases](https://github.com/okhsunrog/vpnhide/releases) — builds are provided for all supported GKI generations. This guide is for contributors or users who need to build from source.

## Quick build

One command — same script CI runs, no container invocation to memorize:

```bash
./kmod/build.py --kmi android14-6.1     # one variant
./kmod/build.py --all                   # every supported GKI
```

The script auto-detects whether to build natively (you're already inside the DDK image, or you've pointed `--kdir` at a kernel source tree) or to spawn a `ghcr.io/ylarod/ddk-min:<kmi>-<TAG>` container via Docker or podman. Docker is preferred when both are installed, matching CI and device-test workflows. On rootless podman (Fedora etc) it adds `--userns=keep-id` and `:Z` automatically. The output is `vpnhide-kmod-<kmi>.zip` at the repo root.

Requires `docker` or `podman`. The container image weighs ~1 GB per GKI variant on first pull.

### Identifying your GKI generation

```bash
adb shell uname -r
```

The output looks like `6.1.75-android14-11-g...` — the generation is `android14-6.1`.

> **Note:** the `android14` part is NOT your Android version — it's the kernel generation. All Pixels from 6 to 9a share the same `android14-6.1` kernel. Pixel 10 series moves to `android16-6.12`.

| `uname -r` pattern | GKI generation |
|---|---|
| `5.10.xxx-android12-...` | android12-5.10 |
| `5.10.xxx-android13-...` | android13-5.10 |
| `5.15.xxx-android13-...` | android13-5.15 |
| `5.15.xxx-android14-...` | android14-5.15 |
| `6.1.xxx-android14-...` | android14-6.1 |
| `6.6.xxx-android15-...` | android15-6.6 |
| `6.12.xxx-android16-...` | android16-6.12 |

## Local build with kernel source

If you prefer building against a local kernel source tree (e.g. for development or debugging), point `--kdir` at it. The script then runs natively without spinning up a container:

```bash
./kmod/build.py --kdir ~/kernels/android14-6.1 --kmi android14-6.1
```

You can also drop a `kmod/.env` file with `KDIR=` / `KERNEL_SRC=` / `CLANG_DIR=` (see `.env.example`) and use [`direnv`](https://direnv.net/) to load it automatically. The script picks those up via env, no flag needed.

## Build against a custom kernel

A `.ko` only loads on the kernel it was built against — the vermagic string and the symbol CRCs both have to match. A kernel with its own `LOCALVERSION`, or one carrying out-of-tree patches, therefore can't use the published prebuilt for its KMI: `insmod` fails with `Exec format error` or `Invalid module format`. The fix is to build the module from that kernel's own tree.

Most kernel builders configure and build out-of-tree (`make O=out ...`), which leaves `.config`, `Module.symvers` and the generated headers under the output directory rather than in the source. Kbuild needs the same `O=` for the external-module build, so pass it as `--kout`:

```bash
./kmod/build.py --kmi android16-6.12 \
  --kdir  /path/to/kernel/common \
  --kout  /path/to/kernel/common/out \
  --clang-dir /path/to/clang/bin \
  --update-json none
```

`--kout` must be the output directory of an already-configured, already-built tree, and it requires `--kdir`. It's equally settable as `KERNEL_OUT` (or `KBUILD_OUTPUT`) in the environment. Leave it out for a tree built in place, including the DDK images — the build is then exactly as before.

`--update-json none` (or `UPDATE_JSON_URL=none`) drops the `updateJson` line from `module.prop`. Use it for these builds: the default URL advertises the upstream prebuilt for that KMI, so a root manager would otherwise offer an "update" that replaces your matching module with one that can't load.

Two config symbols are checked against the target kernel's `.config` before anything is compiled, because getting either wrong produces a module that installs cleanly and then does nothing:

- `CONFIG_MODULES=y` — the backend ships as a loadable module.
- `CONFIG_KPROBES=y` — every hook is a kprobe or kretprobe.

A missing `CONFIG_KRETPROBES=y` is reported as a warning (most hooks are return probes). If the tree exposes no `.config` at all, the check is skipped with a warning rather than failing.

To reuse a prebuilt activator instead of having the script build it with cargo-ndk — worth doing in CI, where the Rust toolchain may not be present — point `VPNHIDE_KMOD_ACTIVATOR` at the binary.

## Install and test

```bash
adb push vpnhide-kmod-<kmi>.zip /sdcard/Download/
# Install via KernelSU-Next manager -> Modules -> Install from storage
# Reboot
```

Verify after reboot:

```bash
adb shell "su -c 'lsmod | grep vpnhide'"
adb shell "su -c 'dmesg | grep vpnhide'"
adb shell "su -c 'cat /proc/vpnhide_ctl'"
```

The optional `.ko` filesystem-hiding probes are controlled from the app's
Settings screen and applied on the next reboot. For direct development loads,
pass `filesystem_hiding=1` to `insmod`; omitting it leaves the global VFS hooks
unregistered.

## Troubleshooting

**`insmod: Exec format error`** — symvers CRC mismatch. Rebuild via the DDK container (`./kmod/build.py --kmi <kmi>`); the container image carries matched symvers.

**`insmod: File exists`** — module already loaded. The module is intentionally
non-unloadable because root module managers replace or remove it across a
reboot; reboot before loading a different build.

**kretprobe not firing** — check `dmesg | grep vpnhide` for registration messages and `/proc/vpnhide_ctl` for correct UIDs. Target app UIDs change on reinstall — re-resolve via the VPN Hide app.

**`./kmod/build.py` says "neither podman nor docker found"** — install one (`apt install docker.io` / `dnf install podman`), or build natively against a local kernel source via `--kdir`.

**Bumping the DDK image tag** — single source of truth is `DDK_IMAGE_TAG` in `kmod/build.py`. Both this script and `.github/workflows/ci.yml`'s kmod matrix pin to the same value, so update both together.
