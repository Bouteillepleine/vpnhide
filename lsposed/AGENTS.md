# lsposed module — architecture & reuse rules

Scoped guidance for the Kotlin module (LSPosed hooks + Compose target-picker
app). Read before adding code here. The point of this file: stop the
duplication / god-function drift that AI-assisted edits cause when each change
only sees its local neighbourhood. **Reuse the abstractions below — don't
reinvent them.** `grep` for an existing helper before writing a new one.

## Data flow

- **Read path:** one batched root shell → `RootSnapshotCache` → typed snapshots
  (`DashboardState`, `TargetsSnapshot`) derived in pure functions → Compose.
  Dashboard and Protection derive from the *same* snapshot so their counts
  can't drift.
- **Write path (Save):** typed entries → `ShellCommandBuilders` → base64 →
  root-owned text files in `/data/adb` + `/data/system` (the kmod/service.sh
  contract). See `docs/state.md` for every path's owner/reader/lifetime.

## Load-bearing abstractions — reuse these

- **`StateCache<T>`** — base for every app-scoped, lazily-loaded cache
  (loading/error/value flows + single-flight job). A new cache **extends this**;
  never hand-roll `inflight`/`loading` again.
- **`RootSnapshotCache`** — the single batched root read. Need new system state
  on the Dashboard/Protection path? Add a section to its shell snapshot; don't
  add an ad-hoc `suExec` that races the snapshot.
- **`ShellUtils`** — `suExec`/`suExecAsync`, and the parsers `parseConfigLines`,
  `parseKeyValueLines`, `parsePackageUidMap`. **Never write another `pm list`
  or `key=value` parser** — there used to be four; there is now one of each.
- **`ConfigChannels`** — the single serialiser of the `vpnhide 1 config` wire
  (docs/protocol.md) and the one place that fans it to every runtime channel
  (kmod `/proc/vpnhide_ctl`, the zygisk module dir, the system_server UID file).
  Save, the debug toggle, and the startup reconcile all go through it. **Don't
  hand-build a config snapshot anywhere else** — use `Protocol.formatConfig` via
  this object.
- **`ShellCommandBuilders`** — `buildRawWriteCommand` (the base64 `echo | base64
  -d > path` primitive every file write uses), `buildConfigWriteCommand` /
  `managedConfigBody` (the persistent **package-list** files, NOT the wire),
  `systemDataFilePermsParts`, and `buildUidResolverCommand` (now only the
  observer-UID file — the app-hiding "A" role; it is **not** the target-config
  path anymore, that's `ConfigChannels`). The persistent package lists and the
  resolved-UID runtime channels are deliberately separate (protocol §1.2).
- **`TargetPickerScaffold`** — `TargetPickerScreen<T>`, `TargetRowShell`,
  `TargetChip`, `AppListScrollbar`. All three picker screens (tun / hiding /
  ports) are thin configs over this; a new picker is too.
- **`StatusUi`** — `StatusColors` (pinned status palette — **never** use
  `MaterialTheme.colorScheme.errorContainer` etc. for status; Material You
  remixes them off-meaning), `StatusBanner`, `FileSaveShareRow`,
  `shareFileViaProvider`.
- **`NativeChecks`** — `NATIVE_CHECKS` is the single probe list (Dashboard
  summary + Diagnostics share it); `CheckStatus.toPassed()` is the single
  tri-state mapping.
- **`watchSystemDataDir`** — the shared `/data/system` FileObserver factory for
  the three system_server watchers (HookEntry / PackageVisibilityHooks /
  HookLog).
- **`VpnHideLog` / `HookLog`** — gated logging; don't `Log.*` directly on hot
  paths (stealth).

## Rules

- **Pure logic goes in top-level functions in `*Data.kt`, with a unit test** —
  not inside a composable or an orchestrator. `classifyKmodProblem`,
  `resolveLsposedState`, `buildNativeInstallRecommendation` are the pattern:
  data in, data out, no Android deps, tested. This is what keeps orchestrators
  (`loadDashboardState`) from rotting back into god-functions.
- **Keep functions short** (detekt fails new non-`@Composable` methods over
  ~60 lines). If an orchestrator grows, extract a pure helper.
- **Add a unit test** for any new pure function (JUnit, `src/test`; run shell
  fragments through `ProcessBuilder("sh", ...)` like `ShellCommandBuildersTest`).
- **`grep` before adding** any parser / formatter / shell-builder / status
  colour — it probably already exists above.

## Quality gates

- **ktlint** — formatting/style. Pre-commit hook (`.githooks/pre-commit`) + CI.
  Fix with `ktlint --format "lsposed/**/*.kt"`.
- **detekt** — complexity, function/file length, dead private members, bug
  patterns (the smell ktlint can't see). Config in `config/detekt/detekt.yml`.
  CI-enforced (`./gradlew :app:detekt`), runs clean with **no baseline**.
  When a new finding is genuinely inherent (a lookup table, an embedded shell
  script, priority-dispatch), opt out **at the call site** with
  `@Suppress("RuleName")` + a one-line reason — visible in the code, not hidden
  in a baseline. Only disable a rule in `detekt.yml` (with a comment) when it
  doesn't fit the codebase at all.
- **CPD** (copy-paste detector) — finds cross-file duplicated blocks that
  detekt can't (re-implemented parsers / save-builders are the classic
  AI-duplication smell). CI-enforced (`./gradlew cpdCheck`); report at
  `build/reports/cpd/`. Tune `minimumTokenCount` in the root `build.gradle.kts`
  if it ever false-positives.
