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
- **`ShellCommandBuilders`** — `buildConfigWriteCommand` / `managedConfigBody` /
  `systemDataFilePermsParts` / `buildUidResolverCommand`. Every managed-config
  file write goes through here so the on-disk format lives in one place.
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
  patterns (the smell ktlint can't see). Config + baseline in
  `config/detekt/`. The baseline freezes pre-existing findings so the gate
  only fails on **new** smell. Run with `./gradlew :app:detekt`.
  **Don't regenerate the baseline to silence a new finding** — fix the code,
  or, if it's a genuine false positive, tune a rule in `config/detekt/detekt.yml`
  with a comment saying why. Regenerate (`./gradlew :app:detektBaseline`) only
  when intentionally accepting legacy debt.
  *Not in CI yet* — the plan is to pay down the baseline to zero first, then
  wire `:app:detekt` into the CI lint job (and drop the baseline).
- **CPD** (copy-paste detector) — finds cross-file duplicated blocks that
  detekt can't (re-implemented parsers / save-builders are the classic
  AI-duplication smell). Run with `./gradlew cpdCheck`; report at
  `build/reports/cpd/`. Advisory for now (doesn't fail the build); tune
  `minimumTokenCount` in the root `build.gradle.kts`. Also slated for CI once
  the current duplicates are cleaned up.
