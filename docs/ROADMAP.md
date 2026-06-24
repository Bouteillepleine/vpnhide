# Roadmap

This document tracks larger product directions that are too broad for a
single changelog entry. It is not a release commitment; concrete work should
still be tracked in GitHub issues and pull requests.

## VPN Hiding Modes

### VPN-preserving concealment

Current Java `Network` handle hiding is optimized for the recommended
split-tunnel setup: target apps are kept outside the VPN, so returning a
physical `Network` handle is consistent with where their traffic should go.

There is a second valid use case: target apps must keep their traffic inside
the VPN while the VPN remains hidden from local detection APIs. This matters
for users who intentionally route banking, media, work, or home-network apps
through a VPN endpoint in a specific country.

The current physical-handle replacement can affect that use case when a target
app explicitly binds traffic to the returned `Network` with APIs such as
`bindProcessToNetwork`, `Network.bindSocket`, or `Network.openConnection`.
Ordinary sockets still follow Android VPN policy, but explicitly-bound traffic
may bypass the VPN on setups that allow physical-network use.

Follow-up work:

- Add a VPN-preserving concealment mode that hides VPN state without steering
  explicitly-bound target-app traffic away from the VPN.
- Decide whether this should be a global mode, per-target setting, or automatic
  behavior based on VPN bypassability / split-tunnel policy.
- Extend Diagnostics with a routing-oriented check that can distinguish
  "VPN hidden and traffic outside VPN" from "VPN hidden and traffic still
  inside VPN".

Tracking: [issue 130](https://github.com/okhsunrog/vpnhide/issues/130).

### Network handle edge cases

The physical replacement path used by LSPosed Java hooks intentionally prefers
connectivity over perfect concealment in rare fallback cases: if no non-VPN
replacement exists for `getActiveNetwork()`, the original active network is left
unchanged instead of reporting that there is no active network.

Follow-up work:

- Watch real app compatibility reports for APIs that are intentionally
  suppressed to `null`, especially `getNetworkForType(TYPE_VPN)`.
- Consider short-lived caching for the selected replacement network if real
  devices show measurable overhead from repeated `ConnectivityService` lookups.

## Kernel Module (kmod)

### Single-lookup route concealment (low priority)

The kmod hides VPN routes from netlink route *dumps* (`RTM_GETROUTE` with
`NLM_F_DUMP`, via the global `fib_dump_info` hook) and from `/proc/net/route`
and `/proc/net/ipv6_route`. It does **not** hide single resolved-route lookups
(`ip route get <dst>`, `RTM_GETROUTE` without `NLM_F_DUMP`), served by the
`static` `rt_fill_info`.

`rt_fill_info` is intentionally left unhooked. As a directly-called `static`
function it has no stable argument→register ABI: a fixed `regs[N]` read is
correct on some builds and wrong on others (verified to differ between a
real LTO device build and a no-LTO QEMU build, where `regs[3]` held
`table_id` instead of the `struct rtable *`). The vector is also low value —
detection apps enumerate routes via dumps (covered by `fib_dump_info`), and
single lookups respect the caller's own routing, which under the recommended
split-tunnel setup resolves to the physical interface anyway.

Follow-up work (low priority):

- If single-lookup concealment is ever wanted, hook the global `rtnl_unicast`
  (ABI-stable, runs in caller context) — the choke point for every
  single-reply rtnetlink response, IPv4 and IPv6 — and rewrite `RTA_OIF` in
  the reply skb to a physical ifindex, instead of reading a fixed register
  off the static `rt_fill_info`.

## Diagnostics And Observability

- Add optional per-hook interception counters so users can see which apps are
  probing VPN state and through which API family.
- Keep install-time hook status detailed enough to diagnose Android framework
  drift, especially private-field changes in new Android releases.

## Configuration

- Consider user-defined VPN interface prefixes for uncommon tunnels or renamed
  interfaces, while keeping generated defaults as the primary path.
- Keep split-tunnel guidance prominent in user-facing docs because server-side
  IP, DNS, and latency checks cannot be fixed client-side.
