## v1.2.4

### Changed
- The debug export now bundles a single self-contained diagnostics file (state.json) in the .zip, replacing the old pile of separate text files — one file has everything.

### Fixed
- A hiding module could show a false "inactive" on some KernelSU devices when the status probe couldn't read the module's liveness; it now reads "status not verified" instead of claiming the module is off.
- KPM: on some kernels built with Clang LTO the IPv6 `/proc/net/ipv6_route` hook could silently fail to install, leaving native hiding partial. It now installs.
- SO_BINDTODEVICE hiding could fail on GKI kernels where the compiler dropped setsockopt's unused level argument, letting a target app still bind a socket to the VPN interface

## v1.2.3

### Fixed
- The app list no longer fails to load with a "couldn't read all Android profiles" error when a profile is legitimately empty — a scan that succeeds (exit 0) with no packages is now accepted, and only a profile whose scan actually errors blocks the list (seen on a Motorola vendor profile that reports zero apps).

## v1.2.2

### Fixed
- KPM: harden the route and interface hooks against vendor kernels whose struct layout differs from the built-in offset table — a mismatched offset now degrades to "not hidden" instead of risking an out-of-bounds skb write or a dereference of a bogus device pointer (reported as a spontaneous reboot on a vendor 5.4 kernel).

## v1.2.1

### Changed
- The KPM (KernelPatch Module) backend is now considered stable — its experimental/beta labels and the "if something breaks, fall back" warning cards have been removed.

### Fixed
- A freshly installed native module (kmod/KPM) that is only waiting for its first reboot now shows "Installed, reboot needed" instead of a misleading "installation corrupted — reinstall" error.
- Kernel module (.ko) no longer fails to load on OEM kernels that don't export path_put (e.g. some OnePlus android14-6.1 builds), where the backend showed as installed but inactive with a "missing vpnhide_kmod.ko" error.
- Port-hiding activation no longer fails when another process (netd, an OEM firewall) briefly holds the iptables lock — the activator now waits for the lock instead of aborting the whole apply.
- When the KPM backend is installed but the kernel has no live KernelPatch runtime to load it (detected via `kpatch hello`, the same check KPatch-Next uses), the dashboard now tells you to install the KPatch-Next-Module and patch your kernel from its interface — or use APatch/FolkPatch — instead of a cryptic "kpatch CLI not found — reinstall the zip" error.

## v1.2.0

### Added
- Selected apps can no longer bind sockets to hidden VPN interfaces through SO_BINDTODEVICE or SO_BINDTOIFINDEX on the kernel backends, with a best-effort libc fallback for Zygisk-only installs.
- KPM supports Android 4.9 kernels, with the matching in-app installation recommendation; legacy 4.14/4.19 validation moved to pinned AOSP common reference kernels.
- Zygisk now hides VPN routing policy rules (ip rule / RTM_GETRULE) from target apps — a detection vector that previously only the kernel backends (.ko / KPM) covered, so Zygisk-only devices leaked it.
- Simplified Chinese (zh-rCN) app translation and a Chinese README.
- The Dashboard shows a one-time support request once hiding is confirmed working, with Support and Hide.
- The minimum supported Android version is now 9 (API 28).
- Optional filesystem path hiding for VPN interfaces under sysfs and /proc/sys/net, on all three native backends. It is an explicit opt-in: the UI warns about the possible slowdown and shows whether the reboot has taken effect. kmod and KPM apply it after a reboot and install no VFS hooks at all while it is off; Zygisk covers it best-effort in-process, filtering both libc readdir and raw getdents64 so native and Rust callers cannot enumerate those directories either.
- Settings has a "Support the project" entry with Boosty and crypto donation options.

### Changed
- The GKI kernel module stays loaded until reboot, matching the Magisk, KernelSU, and APatch module lifecycle.
- Detailed diagnostics headlines how many vectors are hidden vs leaking instead of a misleading passed-count.
- Diagnostics debug export now records each check's full outcome (leak / hidden by backend / hidden by SELinux) and the root ground-truth behind it, plus each layer's verdict and a machine-readable diagnostics.json — instead of a plain pass/fail list.
- The native backend target limit is raised from 64 to 160 UIDs, while oversized KPM control payloads stay fail-safe.
- The app now blocks Native selections beyond 159 resolved app UIDs, reserves one backend slot for VPN Hide, and prevents secondary-profile copies from changing the shared configuration.
- Diagnostics no longer treats detection vectors that no active backend can cover on this device as errors: the Dashboard stays clean when the active module hides everything it can, and such vectors are shown neutrally ("not covered") in the detailed breakdown instead of as red leaks.

### Fixed
- VPN interfaces named for Tailscale, ZeroTier, and Hurricane Electric IPv6 tunnels are now hidden consistently across every backend.
- Stale SIOCGIFCONF buffer entries are cleared, so apps cannot recover hidden VPN interface names past the returned length.
- Apps in a user profile the system leaves nameless (cloned apps, private space) are labelled by profile type in the Hiding list instead of a bare user ID — rows like "Beeline (10)" read as leftovers from an already uninstalled app.
- The Dashboard no longer says the VPN is off when a diagnostics run actually failed (root dropped); it shows a distinct retry prompt.
- Apps from work, private, clone, and secondary profiles no longer go missing from the Hiding list on ROMs with incomplete multi-user package enumeration.
- KPM rejects unvalidated kernel families before loading and reports the exact incompatibility in the app, and uses raw user-copy routines only where the kernel or hardware PAN makes them safe.
- KPM telemetry now reports failed userspace copies instead of returning a false successful byte count.
- Concurrent KPM configuration updates now use a race-free snapshot protocol.
- Malformed custom port policies no longer expand into all-port blocking.
- The app now detects when vpnhide.kpm was installed by itself instead of the complete KPM module ZIP and explains how to repair the installation.
- The Dashboard and Diagnostics screens now agree on why a check is blocked, and a pending self-restart is shown ahead of a VPN-off prompt.
- The Hiding screen now warns after Save when the native backend's resolved UID limit leaves some selected app profiles without Native protection.
- A target list that exceeds KernelPatch's 1024-byte control buffer is now rejected with a clear capacity error — by the activator before it is sent, and by the kernel and KPM backends on receipt — instead of being silently truncated, which could leave some selected apps unprotected.
- Native backend statistics no longer stop at a fixed KPM or kernel-module output buffer; a readout that does hit the limit ends on a complete record and is marked partial instead of being counted as whole.
- VPN Hide no longer targets packages that share a platform identity such as android.uid.system. Selecting one used to write a rule against every system component running under that same UID, which could break connectivity; vendor-preinstalled apps have ordinary app UIDs and are unaffected.
- The Dashboard detects missing or non-executable module activators directly, and Save reports a corrupted module bundle instead of silently skipping it.
- Zygisk native mode now works in 32-bit app processes: the module ships an armeabi-v7a build alongside arm64-v8a (previously NeoZygisk failed to load it into processes forked from zygote32, e.g. on Samsung devices).
- Diagnostics no longer reports an unobservable check as a pass or a leak: a push-callback that never arrives and a network-interface enumeration that throws are now shown as "not measured" instead of a misleading green or red verdict.
- KPM backend now hides ioctl(SIOCGIFINDEX) interface-index probes (used by if_nametoindex), closing a VPN-presence leak that the .ko and Zygisk backends already blocked.
- The app no longer fails to load its state when a config file lacks a trailing newline.
- Zygisk no longer leaks hidden VPN interfaces through scatter/gather netlink reads.

### Removed
- The obsolete decimal KPM load-argument format is gone; load-time configuration now uses the control v2 snapshot.
- The retired pre-v1.0 per-backend storage migration is gone; the canonical JSON config is now the only configuration source.
