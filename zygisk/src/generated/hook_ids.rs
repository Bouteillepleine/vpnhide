// AUTO-GENERATED from data/hooks.toml — do not edit by hand. Regenerate with: uv run scripts/codegen-hooks.py

#![allow(dead_code)]

/// Global hook id space (data/hooks.toml). bit N == hook id N.
#[repr(u32)]
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum Hook {
    /// /proc/net/route — IPv4 route lines
    FibRouteSeqShow = 0,
    /// /proc/net/ipv6_route — IPv6 route lines
    Ipv6RouteSeqShow = 1,
    /// RTM_NEWLINK — getifaddrs() link enumeration
    RtnlFillIfinfo = 2,
    /// RTM_GETADDR — IPv4 address dump
    InetFillIfaddr = 3,
    /// RTM_GETADDR — IPv6 address dump
    Inet6FillIfaddr = 4,
    /// SIOCGIF* by name (per-interface ioctls)
    DevIoctl = 5,
    /// SIOCGIFCONF — interface list ioctl
    SockIoctl = 6,
    /// RTM_GETROUTE — IPv4 route dump (issue #86)
    FibDumpInfo = 7,
    /// RTM_GETROUTE — IPv6 route dump
    Rt6FillNode = 8,
    /// RTM_GETRULE — policy routing rules
    FibNlFillRule = 9,
}

pub const HOOK_COUNT: u32 = 10;

/// Hooks owned by each backend: apply `mask & own`.
pub const KERNEL_HOOK_MASK: u32 = 0x3ff;
pub const ZYGISK_HOOK_MASK: u32 = 0x0;
pub const LSPOSED_HOOK_MASK: u32 = 0x0;

/// status error codes (protocol §5.1).
#[repr(u32)]
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum StatusError {
    /// healthy; every requested, owned hook installed
    Ok = 0,
    /// no offset table for the running kernel — refused, no hooks
    UnsupportedKver = 1,
    /// the other kernel backend (.ko<->KPM) is loaded — refused (protocol §1.2)
    ConflictingBackend = 2,
    /// a required kallsyms symbol was missing — refused
    SymbolResolutionFailed = 3,
    /// installed, but some owned hooks did not resolve (see the hooks mask)
    PartialHooks = 4,
}

/// backend ids (protocol §4.3 `status backend <id>`).
#[repr(u32)]
#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum Backend {
    /// .ko kretprobe backend
    Kmod = 0,
    /// KernelPatch Module backend
    Kpm = 1,
    /// Zygisk libc-hook backend
    Zygisk = 2,
    /// LSPosed Java-hook backend (system_server)
    Lsposed = 3,
}

pub const HOOK_NAMES: [&str; 10] = [
    "fib_route_seq_show",
    "ipv6_route_seq_show",
    "rtnl_fill_ifinfo",
    "inet_fill_ifaddr",
    "inet6_fill_ifaddr",
    "dev_ioctl",
    "sock_ioctl",
    "fib_dump_info",
    "rt6_fill_node",
    "fib_nl_fill_rule",
];
