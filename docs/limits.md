# Capacity limits

Every hard ceiling on the runtime path: where it lives, what it is measured at,
what binds first, and what raising it would cost. Read this before changing
`MAX_TARGET_UIDS`, the wire format, or a backend's static arrays — several of
these ceilings are in third-party code we cannot move, and the interesting ones
are not where people expect.

Numbers here are **measured**, not estimated; see [Re-measuring](#re-measuring)
so they can be checked rather than trusted.

## Two paths, two ceilings

The single most common mistake is treating these as one budget:

| | control (`config`) | telemetry (`stats`, `status`) |
|---|---|---|
| direction | activator → backend | backend → app |
| bounds | how many apps can be **protected** | how many apps **report counters in one read** |
| overflow costs | apps left unprotected | numbers missing from a screen |

They are separate protocols with separate versions ([protocol.md §3](protocol.md)).
A telemetry ceiling cannot limit how many apps are hidden, and vice versa. "We
can't raise the app count because stats won't fit" conflates the two.

## Control — how many apps can be protected

| constraint | where | value | binds at |
|---|---|---|---|
| `MAX_TARGET_UIDS` | `crates/protocol/src/lib.rs`, mirrored in both backends | 64 | **64 targets — currently binding** |
| `KPM_ARGS_LEN` | `kmod/third_party/KernelPatch/kernel/include/kpmodule.h` | 1024 B | ~190 targets sharing one mask; fewer with many distinct masks |
| `ctl_write` payload cap | `kmod/vpnhide_kmod.c` (`count > PAGE_SIZE`) | 4096 B | ~800 targets |
| parse scratch on the kernel stack | `ctl_write`, `vpnhide_kpm_ctl0` | 8 B/target | thousands (16 KiB arm64 stack, shared with a 4 KiB reply buffer in the KPM) |

Measured control payloads under the v2 grammar (full kernel hookmask):

| targets | bytes | KPM (1024) | kmod (4096) |
|---|---|---|---|
| 40 | 248 | ok | ok |
| 64 | 368 | ok | ok |
| 64, half in a work profile | 400 | ok | ok |
| 120 | 648 | ok | ok |
| 160 | 848 | ok | ok |
| 200 | 1048 | **over** | ok |

Two things follow.

**`KPM_ARGS_LEN` is not ours.** It is a fixed buffer in KernelPatch, baked into
the user's patched boot image, and `compat_strncpy_from_user` truncates into it
*silently*. Before the v2 grammar a 64-target config was ~1.6 KB and so could
not be delivered at all — it was cut around 40 targets, mid-line, with no
signal. Grouping by hookmask and dropping the `0x` prefix cut that to 368 bytes;
the mandatory `end <count>` record turned any remaining overflow into a loud
whole-payload rejection. See [protocol.md §4.3](protocol.md).

**The cap counts UIDs, not apps.** `resolver.uids_for()` returns every UID a
package has across profiles, so an app present in a work profile spends two
slots. A user with a work profile reaches 64 UIDs at around 32 apps.

## Telemetry — how many apps report counters

There is no fixed app count. A uid's cost depends on how many hooks fired for it
and how large its counters grew, and counters are cumulative since the backend
loaded — so the same device fits fewer uids after a week of uptime than after a
reboot. Measured with the real formatter, 5-digit uids:

| profile | bytes/uid | fits in 4096 (KPM) | fits in 32768 (kmod) |
|---|---|---|---|
| 2 hooks, counts < 256 | 25 | 163 | 1310 |
| 4 hooks, counts < 65k | 51 | 80 | 642 |
| 8 hooks, counts ~1e6 | 103 | 39 | 317 |
| 27 hooks, saturated u64 | 639 | 6 | 51 |

The two backends are not in the same position:

- **KPM** — 4096 B hard: `VPNHIDE_OUT_MAX` in `kmod/kpm/vpnhide_kpm.c`, and the
  KPatch and APatch clients cap their side at the same figure, so a larger
  userspace buffer does not help. On overflow it clamps to a whole record, the
  activator marks the reply `# vpnhide truncated`, and the app drops that
  backend's stats rather than showing partial totals
  (`StatisticsData.kt`). Blunt, but it never reports a wrong number.
- **kmod** — 32 KiB (`CTL_READ_BUF_SIZE`), and its entry array is already
  heap-allocated. Eight times the room.

The KPM's serialisation scratch is deliberately sized by the transport
(`VPNHIDE_STATS_SNAPSHOT_MAX = VPNHIDE_OUT_MAX / 8`), not by
`MAX_TARGET_UIDS * VPNHIDE_HOOK_COUNT`. The product would reserve 1728 entries
when no reply can carry more than ~510, and — the point — it would grow with the
target ceiling. Static growth is what broke KernelPatch boot on the 6.12 image,
so keeping this bound tied to the transport is what lets the ceiling move
independently. `.bss` is 23120 B at the time of writing, down from 42576 B.

## Raising a ceiling — options and cost

Ordered by cost. None of these are scheduled; this is the menu.

### Control

1. **Raise `MAX_TARGET_UIDS` to ~160.** The v2 grammar already fits it in the
   KPM transport, the KPM's `.bss` no longer tracks the ceiling, and the kmod
   payload cap has room. Costs: move the parse scratch off the kernel stack
   (8 B/target, currently 512 B), and re-verify KernelPatch boot on 6.12. This
   is the cheap one and the only ceiling currently binding.
2. **Past ~190 targets on the KPM**, the 1024-byte transport is the wall. The
   only way through is chunking — `config-begin` / `config-chunk` /
   `config-commit` across several ctl0 calls — which is a control-protocol
   change. The `.ko` needs none of this; raising its `PAGE_SIZE` check is a
   one-liner.
3. **Whitelist mode** makes the whole question moot for the kernel backends:
   with a non-zero `default` the enumerated set becomes the exception list, a
   handful of entries, and every ceiling above stops binding. The wire already
   carries the mechanism (`default <hookmask>`); nothing emits it yet. Zygisk
   cannot honour the inverted meaning without injecting into every app process,
   so its activator rejects a non-zero default. See issue #248 for the design
   discussion and the reasons it should be opt-in rather than default.

### Telemetry

1. **Bare hex in a telemetry v2** — the same trick control v2 used:
   ` 0x3:0xf4240` → ` 3:f4240`, four bytes per cell, roughly +45% uids per read
   at the heavy profile. Cheap in code, expensive in delivery: telemetry's
   reader is the app, so bumping it means shipping the APK in step with every
   module. Only worth bundling with some other telemetry change.
2. **A cursor** — `stats <after-uid>`, read in a loop. [protocol.md §7.2](protocol.md)
   currently says "no pagination" for the KPM; that was a decision, not a law.
   This is the only option that actually scales, and it is confined to the KPM
   transport.
3. **Cap by policy, not by configuration** — emit the top-N uids by total and
   mark that it happened. The user gets numbers for the apps that did something
   and configures nothing.

Explicitly **not** recommended: a user-facing "collect stats for these apps"
list. It makes the user maintain a second selection whose mistakes are
invisible — no numbers looks identical to nothing happened — to solve a problem
that is telemetry-only and already degrades visibly.

## Known gaps

- The app does not act on the truncation signal for the **kmod** stats channel.
  It reads `/proc/vpnhide_ctl` directly and only looks for the activator's
  `# vpnhide truncated` marker, which exists on the KPM path. After the clamp
  fix a truncated kmod read ends on a whole record, so the numbers shown are
  correct — there are just silently fewer of them.
- The activator caps the target set at `MAX_NATIVE_TARGETS` and warns on stderr,
  but `ConfigChannels.nativeActivatorCommand()` runs it without `2>&1` and
  `suExec` discards stderr, so a user who selects more apps than fit is told
  nothing. The backend now rejects an over-capacity payload outright, so this is
  no longer silent *corruption* — but it is still a silent *cap*.

## Re-measuring

Nothing here should be trusted because it is written down. The control-side
figure is guarded by a test — `crates/protocol` asserts that all
`MAX_TARGET_UIDS` targets, using maximum-width UIDs and one shared full mask,
fit in `KPM_ARGS_LEN` with room for its trailing NUL. Arbitrary per-app masks
can cost more group headers, so the activator also checks the formatted wire's
actual byte length before KPM delivery.

The telemetry table is not guarded, because its input is a usage profile rather
than a constant. To redo it, format `n` uids × `k` hooks with
`vpnhide_format_stats` from `kmod/shared/vpnhide_logic.h` (a dozen lines of host
C, built with `gcc -I kmod`) and divide by the buffer under test. The KPM `.bss`
figure comes from `llvm-size --format=sysv kmod/vpnhide.kpm` after
`python3 kmod/kpm/build.py`.
