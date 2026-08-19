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

## v1.1.1

### Fixed
- Fixed incomplete VPN hiding on some devices and firmwares (notably many MediaTek-based and custom OEM ROMs), where several app-side checks could still detect the tunnel through legacy connectivity APIs.

## v1.1.0

### Added
- Detailed diagnostics can export a separate opt-in ZIP with active kernel boot images and partition metadata.
- The dashboard now diagnoses a KPM install stuck without loading (corrupted activator or a generic activator failure), showing which one and how to fix it — previously it silently sat inactive with no explanation.
- The dashboard now shows the installed kernel module's GKI variant on its card, and update warnings for an outdated kmod name the exact recommended zip to grab — so updating over an old kmod install no longer means guessing which variant you originally flashed.
- Diagnostics now checks that VPN Hide itself is routed through your VPN; if it has been split-tunnelled out, it asks you to add it to the tunnel instead of running meaningless checks.
- Diagnostics now includes an RTM_GETRULE check that detects a VPN leaking through per-app policy routing rules.
- Settings now has a full Hidden apps page that shows automatic app-hiding matches, manual hidden apps, and per-app auto-hide exclusions.
- The dashboard now offers one-tap module downloads: a button to grab the exact recommended zip from the latest release (plus a link to all releases), and a download button on outdated-version and wrong-variant module warnings.

### Changed
- Debug bundles now include a best-effort boot logcat excerpt for LSPosed and Vector attach diagnostics.
- Debug exports now include richer device, module and backend diagnostics, including decoded hook status/counter reports for KPM, Zygisk, LSPosed and Ports live state.
- Default debug APK is now R8-shrunk; use rawDebug for the old unminified debug build.
- Full system logcat recording now saves a diagnostic ZIP with device, backend, kernel, config, dmesg, and debug-capture context.
- Diagnostics now probes the legacy getNetworkInfo(TYPE_VPN) API and drops the uninformative system-proxy check.
- Java-level diagnostics now attribute who hid the VPN (hidden by the backend, or a leak) like the native checks, instead of a bare clean/fail.
- The detailed diagnostics screen now shows who hid the VPN on each check — the backend, SELinux, or nothing to hide — instead of a bare pass/fail.
- Compact single-row app bar (logo, title and actions on one line) that scales the brand down on very narrow screens; Apps-list filters moved out of the top-bar menu into chips above the list.
- On large screens the Dashboard expands its header into a larger brand and animates back to the compact bar when you switch tabs.
- The detailed diagnostics screen now shows what root saw on each native check next to the app's own read, and marks 'nothing to hide' distinctly from a backend win — so a SELinux-blocked check that has nothing to leak explains itself instead of looking protected.

### Fixed
- Auto-hidden VPN apps are now re-detected on startup and when you tap Refresh, so a VPN app installed after the app list was cached gets hidden from detector apps without re-saving the hiding list.
- Dashboard now explains inactive Ports rules using the portshide load_status.
- Diagnostic captures now restore temporary debug logging reliably after errors, cancellation, or overlapping captures.
- Portshide diagnostics now record the latest apply status and command output.
- Dashboard native/Java level tiles no longer mislabel an unloaded backend as "Partial" or a mostly-working layer as "Not working": each layer now reads OK / Partial / Not active, judged by what actually hid the VPN.
- KPM activation now works on FolkPatch/APatch setups that expose the trusted su KernelPatch control token, without requiring users to save a SuperKey when the runtime already grants it.
- Protection search now closes on back, and unsaved app role toggles no longer move rows between groups before Save.

## v1.0.0

### Added
- New KPM (KernelPatch Module) native backend (beta): hides VPN interfaces — and the VPN server's public host-route — on non-GKI and module-signing-locked kernels (4.14 through 6.12). It flashes as a Magisk/KernelSU-Next/APatch module that loads at boot and applies your targets automatically (under APatch it waits for the superkey from the app).
- New Statistics tab: a per-app view of which apps probe for the VPN and how, with app icons and names like the Hiding tab, and a tap-through per-hook breakdown that explains each detection method. Capture sessions surface probes live while you use a target app, and stopping a session keeps its results on screen for review.
- Per-app hook selection: choose which Java and native hooks apply to each protected app, grouped by detection method with an explanation of what each method is and how an app uses it to detect a VPN. Full role labels (Java / Native / Apps / Ports) are shown by default.
- The dashboard now explains more failure modes instead of surfacing raw errors: a kernel that rejects the module because it enforces module signatures (EKEYREJECTED — it recommends KernelSU Next), a KPM installed but waiting for the APatch superkey, and a hiding layer that is active while some of its runtime checks still fail (a Details button opens the full diagnostics). A fresh install with no targets now reads as guidance rather than an error. It also detects private AOSP fields broken by a new Android release at install time, listing the affected fields and Android SDK so you can file a bug.
- App hiding gains an advanced manual picker for choosing which apps to hide, automatic hiding of installed VPN apps via configurable heuristics, cleanup of configured apps that are no longer available, and plaintext export of the package list.
- Settings can now export and import the full canonical JSON configuration, so you can back up or move your entire setup as a single file.
- Add a Settings reset button that removes the config, state and target files VPN Hide writes to the device, so nothing is left orphaned after you uninstall the app and its modules.
- Optional daily background update check that notifies you when a new version is available.
- Configure which localhost port ranges the Ports hiding role blocks.
- New Community & feedback screen gathers the author's contacts (GitHub, Telegram, 4PDA) in one place.
- Experimental local HTTP bridge with a host MCP server for adb-driven app-state and hiding management. Off by default; enable it under Settings → Developer → Agent control.
- Native libraries now align their LOAD segments on 16 KiB, so they load cleanly on 16 KiB-page devices such as Pixel 8 Pro on Android 16 and future hardware, without the "ELF LOAD not aligned" warning at app start.

### Changed
- Redesigned the app with a Material 3 Expressive UI: grouped cards across Dashboard, Hiding, Diagnostics, and Settings, an at-a-glance status summary, a refreshed top bar and app mark, a proper themed monochrome chameleon icon, haptic and animated navigation, surface colors that avoid overly black Material You backgrounds, and live theme controls for card shadows and animations. The 'Root access required' screen matches the new look and gains a 'Check again' button to re-probe root without reopening the app. The dashboard is decluttered, with low-priority notes shown as neutral info rather than warnings.
- Reframed the whole app around hiding the VPN: the Dashboard status reads “VPN hidden / VPN visible”, the Protection tab is renamed Hiding, and the Russian and English wording was polished throughout.
- The three Hiding tabs (Tun, App hiding, Ports) are now a single app list: each row carries J/N/A/P toggles (Java, Native, App-hiding, Ports) and one Save applies everything at once, so there's no more hunting an app across tabs or saving three times. Filters keep already-configured apps visible, list sorting is configurable (configured apps first by default), the in-screen help is better formatted, selected apps temporarily missing from the list are preserved on Save, and the list shows a retry card instead of spinning forever if it fails to load.
- Diagnostics now lives under Settings, alongside a new Developer section: a debug-logging preference (off by default to keep logcat quiet and save resources; when enabled it turns on verbose kmod dmesg output and LSPosed hook logs, which are also captured in debug exports) plus a toggle to mute version and changelog notices on dev builds. The dashboard now waits for the full protection-check set and shows an in-app loading state instead of holding the splash screen. Diagnostics also shows a distinct checks-failed retry state on a failed run instead of misreporting an active VPN as off.
- The Dashboard now models your setup as one Java backend (LSPosed) and one Native backend (kmod, KPM, or Zygisk) instead of a per-module list. It recognizes the new KPM backend and warns when more than one native backend is installed — an error for the kmod+KPM combination, which can freeze the kernel — and KPM reports a truthful conflict status when it stands down for a co-installed kernel module.
- On non-GKI kernels (4.14–5.4) the app now recommends the KPM backend (beta) instead of Zygisk.
- The Zygisk module now appears as VPN Hide (Zygisk) in your root manager.

### Fixed
- Closed several Java Connectivity detection vectors so more apps can no longer see the VPN: network-callback pushes (e.g. VTB), getNetworkInfo(TYPE_VPN) (e.g. Улыбка радуги), the legacy NetworkInfo API (getActiveNetworkInfo), and the VPN Network handles from getActiveNetwork/getAllNetworks are now sanitized for target apps. The system_server hooks scrub results through public APIs, so they also cover Android 17, which changed the private fields the old hooks read.
- Target apps can no longer detect a hidden VPN by enumerating interfaces or reading routes directly from the kernel. VPN routes — and the physical host-route hints that expose them — are stripped from RTM_GETROUTE netlink dumps (including the FORTIFY'd recvfrom/__recvfrom_chk path) and from /proc/net/ipv6_route, the SIOCGIFCONF buffer-size count trick (ifc_req == NULL) is closed, and tunnels renamed to the kernel-default `if<N>` pattern (issue #86) — along with utun/l2tp/gre and renamed *vpn* interfaces — are recognized and hidden consistently across the kmod, native, and Java backends. Hiding interfaces from RTM_GETLINK dumps also no longer hangs under Permissive SELinux.
- Apps you hide stay invisible to other observer apps while still seeing themselves, and Android 17's PackageManager list wrappers are preserved when hiding packages.
- A malformed port rule in the stored config no longer discards the whole configuration (which silently disabled every hook), and selected-app UIDs now resolve by literal package match so similarly named packages are never targeted by mistake. The activator also warns on stderr when native targets exceed the 64-target backend cap, instead of silently dropping the highest-UID apps from native protection.
- Fixed the app failing to start with "Startup preparation failed" — the root setup command was being flattened to one line, breaking its shell syntax. The failure screen now explains the actual cause (root unavailable, incomplete root data, or a config-write failure) and shows the underlying error detail instead of always telling you to check root permissions.
- Fixed an out-of-memory crash when running diagnostics with a VPN connected on some kernels: the native netlink interface/route checks now bound their read loop and time out instead of looping until the allocator aborts.

### Security
- Localhost port blocking now covers the entire 127.0.0.0/8 loopback range, so a proxy or VPN daemon bound to 0.0.0.0 can no longer be reached through a 127.x.x.x alias.
- LSPosed hides a protected package from getNameForUid / getNamesForUids uid-to-name resolution and scrubs the VPN's DNS servers and tunnel addresses from LinkProperties.
- Zygisk blocks VPN interface-index probes (if_nametoindex / ioctl(SIOCGIFINDEX)) and intercepts /proc/net/{dev,udp,udp6} and /proc/thread-self and task path forms that could reveal a hidden VPN, and no longer reads out of bounds on netlink replies that use MSG_TRUNC.
