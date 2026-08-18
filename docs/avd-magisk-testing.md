# Testing vpnhide on a rooted x86_64 AVD (Magisk + Zygisk + LSPosed)

How to stand up a **rooted Android emulator** to test the vpnhide LSPosed/Zygisk
layers — including **Android 9 (API 28)**, which the stock emulator cannot root
out of the box. This cost hours to work out; follow it and skip the dead ends.

The native kernel/KPM backends **cannot** be tested this way (the emulator runs
a goldfish/ranchu kernel, not GKI; zygisk's shadowhook has no x86 backend). This
setup verifies the **Java/LSPosed layer** and Zygisk module *loading*.

## TL;DR

- Host is **x86_64** → only **x86_64** system images run (`emulator` 36.x refuses
  arm64 on x86: `FATAL | Avd's CPU Architecture 'arm64' is not supported ... on
  x86_64 host`). No arm64 emulation here.
- **API 30+**: `rootAVD` + Magisk works normally. Just root, enable Zygisk,
  flash LSPosed, done.
- **API 28 (Android 9)**: the emulator kernel cmdline hard-codes
  `skip_initramfs`, so Magisk's ramdisk `magiskinit` is **never executed** and
  rootAVD's patch has no effect. Fix = boot a **custom kernel** with the
  `skip_initramfs` handler neutered (below).

## Prereqs

- `~/Android/Sdk` with `cmdline-tools`, `emulator`, `adb`.
- A **non-Play** system image (Play images can't be rooted):
  `sdkmanager "system-images;android-28;google_apis;x86_64"`.
- AVD: `avdmanager create avd -n <name> -k "...;x86_64" --abi x86_64`.
  Note: modern `avdmanager` writes AVDs under `$XDG_CONFIG_HOME/.android/avd`
  (`~/.config/.android/avd`), while `emulator -list-avds` reads `~/.android/avd`.
  Export `ANDROID_AVD_HOME` to the same path for both.

## Rooting API 30+ (the easy path)

1. Boot the AVD once (`-writable-system -no-snapshot`).
2. `rootAVD` (GitLab, maintained): `git clone https://gitlab.com/newbit/rootAVD`
   then `./rootAVD.sh system-images/android-XX/google_apis/x86_64/ramdisk.img`.
   Feed input with `printf '\n%.0s' {1..40} | ./rootAVD.sh ...` — **not** `yes ""`
   (an unbounded pipe breaks rootAVD's `adb shell` probe → "no ADB connection").
3. Cold-boot; open Magisk; enable Zygisk; reboot.

## Rooting API 28 (Android 9) — custom kernel

The stock kernel cmdline is `... skip_initramfs rootwait ro init=/init
root=/dev/vda1 ...`. `skip_initramfs` makes the kernel mount system directly and
run system's `/init`, never the ramdisk's `magiskinit`. It is **not** removable
via flags (`-qemu -append` only *appends*; the token then still wins). Build a
kernel that ignores it.

```sh
# toolchain matching the 4.4 kernel era (host gcc is far too new for 4.4)
git clone --depth 1 -b android10-c2f2-release \
  https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/x86/x86_64-linux-android-4.9 gcc49
git clone --depth 1 -b android-goldfish-4.4-dev \
  https://android.googlesource.com/kernel/goldfish goldfish

cd goldfish
# 1) neuter skip_initramfs: in init/initramfs.c, skip_initramfs_param(), set
#    `do_skip_initramfs = 0;` (was 1). Kernel now always unpacks the (Magisk-
#    patched) initramfs as rootfs and runs /init = magiskinit.
# 2) use the STOCK kernel config (a generic defconfig mismatches the emulator HW
#    and panics in MM once userspace starts — "Bad swap file entry" etc.):
./scripts/extract-ikconfig \
  ~/Android/Sdk/system-images/android-28/google_apis/x86_64/kernel-ranchu > stock.config
cp stock.config .config
export PATH=$PWD/../gcc49/bin:$PATH ARCH=x86_64 CROSS_COMPILE=x86_64-linux-android-
# stock-like build id (the emulator's version parser is picky):
export KBUILD_BUILD_USER=android-build KBUILD_BUILD_HOST=localhost \
       KBUILD_BUILD_TIMESTAMP="Wed Jan 30 07:13:09 UTC 2019"
make olddefconfig
make -j"$(nproc)" bzImage      # ~2 min, out: arch/x86/boot/bzImage
```

**Emulator kernel-version gotcha:** the emulator reads the version string from
the bzImage setup header (2-byte pointer at file offset `0x20E`) and **rejects
`4.4.302`**. Patch the reported version to the stock `4.4.124` (same length):

```sh
cp arch/x86/boot/bzImage bzImage_124
printf '124' | dd of=bzImage_124 bs=1 seek=12788 count=3 conv=notrunc  # "302"->"124"
```

Boot with the custom kernel + the Magisk-patched ramdisk (rootAVD makes the
ramdisk; you only swap the kernel):

```sh
emulator -avd <name> -no-window -no-snapshot -writable-system \
  -gpu swiftshader_indirect -memory 3072 -show-kernel \
  -kernel .../bzImage_124 -ramdisk .../ramdisk.img.<magisk-patched>
```

It boots Android 9 with Magisk active.

## Completing Magisk / Zygisk (needed after messy rootAVD runs)

If `magisk --install-module` says **"Incomplete Magisk install"** and Zygisk
doesn't inject (`/data/adb/magisk` empty), populate it from the matching Magisk
APK. Match the version to the running core (`magisk -V`, e.g. 25.2):

```sh
# from Magisk-vXX.apk: push lib/x86_64/lib*.so and assets/, then on device:
#   cp libmagisk64.so magisk64; cp libmagisk32.so magisk32 (from lib/x86);
#   cp libmagiskinit.so magiskinit; libmagiskboot.so magiskboot;
#   libmagiskpolicy.so magiskpolicy; libbusybox.so busybox;
#   assets/util_functions.sh, boot_patch.sh, addon.d.sh -> /data/adb/magisk/
#   chmod 755 magisk* busybox
adb shell 'magisk --sqlite "REPLACE INTO settings (key,value) VALUES(\"zygisk\",1)"'
adb shell 'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(2000,2,0,0,0)"' # grant shell root
adb reboot
```

Verify Zygisk from logcat after reboot: `Magisk: zygisk64: replaced
com/android/internal/os/Zygote#nativeForkAndSpecialize`.

## Installing & enabling LSPosed + a module (headless)

```sh
# LSPosed framework (supports 8.1-14):
curl -LO https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/LSPosed-v1.9.2-7024-zygisk-release.zip
adb push LSPosed-*.zip /data/local/tmp/ && adb shell magisk --install-module /data/local/tmp/LSPosed-*.zip
adb reboot                                   # zygisk companion should log "welcome to LSPosed!"
adb shell pm install -r -g /data/adb/modules/zygisk_lsposed/manager.apk   # LSPosed manager

adb install -r -g <vpnhide app-debug.apk>    # the module

# enable + scope via LSPosed's config db (no GUI):
DB=/data/adb/lspd/config/modules_config.db
adb shell "sqlite3 $DB \"UPDATE modules SET enabled=1 WHERE module_pkg_name='dev.okhsunrog.vpnhide'\""
# scope 'android' = system_server (needed for ConnectivityService hooks):
adb shell "sqlite3 $DB \"INSERT OR REPLACE INTO scope(mid,app_pkg_name,user_id) VALUES(2,'android',0)\""
adb reboot
```

On API 28, LSPosed logs a non-fatal `NoClassDefFoundError:
android.os.IServiceCallback$Stub` (that binder class is API 29+) and falls back
to polling — LSPosed still works.

## Reading the vpnhide hooks

The system_server hooks log at ERROR only for missing targets; the per-call
telemetry (`VpnHide-NC/-LP/-NI`) is gated by the **debug** flag in the canonical
config. Enable it (we have root; the app can't flip it itself):

```sh
printf '{"version":1,"debug":true,"debugSwitch":true}' > c.json
adb push c.json /data/system/vpnhide_config.json
adb shell 'chown root:system /data/system/vpnhide_config.json; chmod 640 /data/system/vpnhide_config.json'
adb reboot
adb shell 'logcat -d | grep -aoE "VpnHide-[A-Za-z]+" | sort | uniq -c'
```

## Gotchas that wasted the most time

- **arm64 images don't run on an x86_64 host** with emulator 36.x. Full stop.
- **`skip_initramfs`** on API 28 defeats rootAVD; needs the custom kernel.
- **Generic defconfig panics** (MM corruption once userspace starts). Use the
  stock kernel-ranchu config via `extract-ikconfig`. In particular do **not**
  leave `RANDOMIZE_MEMORY=y` with `RANDOMIZE_BASE=n`.
- **Emulator rejects the kernel version** unless it parses; keep it stock-like
  and patch the reported number if needed.
- **Leftover `multiinstance.lock`** after `kill -9` makes the next launch fail
  with no output. `find .../<avd>.avd -name '*.lock' -delete` before each boot.
- **Emulator daemonizes on an accepted `-kernel`**, so its `-show-kernel`
  output detaches — capture kernel console from a **real terminal** with a
  direct `> file` redirect (not a pipe), or read the pstore/ramoops file.
