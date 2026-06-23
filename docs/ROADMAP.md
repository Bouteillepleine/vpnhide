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
