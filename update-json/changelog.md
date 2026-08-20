## v1.2.5

### Added
- Warn before collecting a debug log or logcat recording while the VPN is off or VPN Hide is not routed through the tunnel — such captures are incomplete and hard to diagnose. Offers to re-check or proceed anyway.

### Changed
- The VPN-tunnel check is now shared across the whole app — re-checking in one place updates every screen, and Diagnostics now reacts automatically when you turn the VPN on or off.

### Fixed
- Fixed the kernel module failing to load on some OEM kernels with a trimmed module symbol table (e.g. Xiaomi HyperOS android12-5.10), where dev_get_by_index_rcu was reported as an unknown symbol; it is now resolved at runtime via kallsyms like path_put, so the module links without a hard reference.
- Settings switches no longer flicker off/on while a debug log is being collected, and the debug-export sheet no longer keeps a stale collected file when reopened.
- The app list now loads reliably on devices with many installed apps and/or multiple profiles (common on MIUI/HyperOS). It no longer fails on huge app counts or blocks the whole list demanding you unlock a profile — it shows what it can read and flags any profile it couldn't; settings for un-scanned profiles are preserved.

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
