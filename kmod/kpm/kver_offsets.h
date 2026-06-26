/* SPDX-License-Identifier: MIT */
/*
 * Per-kernel-version struct-offset table for the KPM backend.
 *
 * WHY THIS EXISTS
 * ---------------
 * KernelPatch resolves *symbol addresses* at load time (runtime
 * `kallsyms_lookup_name`), so one `.kpm` binary loads across many kernels.
 * It does NOT give us struct *field* offsets — those changed across
 * versions and the compiler can't compute them because we never include
 * the real kernel headers (`-nostdinc`). So we keep a small offset table
 * keyed on the running kernel version, selected at init.
 *
 * This is the difference between our approach and soranerai's KPM, which
 * ships three near-identical source files (4.14 / 5.4 / 6.1) with the
 * offsets baked in. One source + one runtime table = one binary for all.
 *
 * VALUES MUST BE PROVEN, NOT GUESSED. Each non-TODO value below is either
 * (a) confirmed by soranerai on a real device, or (b) trivially stable
 * (e.g. net_device.name is the first member on every version). Every new
 * value has to pass the QEMU KPM harness (see kmod/kpm/README.md) before
 * it ships — a wrong offset here is a kernel panic / bootloop, not a soft
 * failure like a kretprobe that won't register.
 */
#ifndef VPNHIDE_KVER_OFFSETS_H
#define VPNHIDE_KVER_OFFSETS_H

/* KernelPatch exposes the running kernel version as `kver` (see
 * <kpmodule.h>). Encoding matches LINUX_VERSION_CODE: (a<<16)|(b<<8)|c. */
#define VPNHIDE_KVER(a, b, c) (((a) << 16) + ((b) << 8) + (c))

struct vpnhide_offsets {
	/* sk_buff.len — saved before a netlink fill, restored via skb_trim. */
	unsigned int skb_len;

	/* net_device.name — first member on all supported versions. */
	unsigned int netdev_name;

	/* seq_file.{buf,count} — stable across the seq_file lifetime. */
	unsigned int seqfile_buf;
	unsigned int seqfile_count;

	/* in_ifaddr.ifa_dev (-> in_device.dev -> net_device).  [TODO 5.4/4.14] */
	unsigned int in_ifaddr_ifa_dev;
	unsigned int in_device_dev;

	/* inet6_ifaddr.idev (-> inet6_dev.dev -> net_device). [TODO 5.4/4.14] */
	unsigned int inet6_ifaddr_idev;
	unsigned int inet6_dev_dev;

	/* IPv4 route dump (fib_dump_info): fib_rt_info.fi=0, fib_nh_common.nhc_dev=0
	 * (constants). These three vary per version; for the legacy single-nexthop
	 * route: dev = *(fi + fib_info_fib_nh + nhc_dev). fib_info_nh != 0 marks a
	 * nexthop-object route (not unpacked yet). 0 => hook bails (no guessing). */
	unsigned int fib_info_fib_nhs;
	unsigned int fib_info_nh;
	unsigned int fib_info_fib_nh;

	/* IPv6 route dump (rt6_fill_node): fib6_info.nh != 0 => nexthop object;
	 * else dev = *(rt + fib6_info_fib6_nh) (fib6_nh[0].nhc_dev). */
	unsigned int fib6_info_nh;
	unsigned int fib6_info_fib6_nh;

	/* 1 if procfs uses `struct proc_ops` (>=5.6), 0 if `file_operations`
	 * (<5.6). Mixing these up is the most likely cause of the
	 * /proc/vpnhide_targets crash reported on HyperOS 5.4. */
	int proc_uses_proc_ops;
};

/*
 * GKI 6.1 — values confirmed by soranerai's working module on-device.
 *   skb.len = 112, inet6_ifaddr.idev = 216, in_ifaddr.ifa_dev = 24.
 */
static const struct vpnhide_offsets vpnhide_off_6_1 = {
	.skb_len = 112,
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 24,
	.in_device_dev = 0, /* TODO: confirm in_device.dev offset on 6.1 */
	.inet6_ifaddr_idev = 216,
	.inet6_dev_dev = 0, /* TODO: confirm inet6_dev.dev offset on 6.1 */
	.fib_info_fib_nhs = 0, /* TODO 6.1 */
	.fib_info_nh = 0,
	.fib_info_fib_nh = 0,
	.proc_uses_proc_ops = 1,
};

/* 5.10 / 5.15 — older skb layout (skb.len = 104 per soranerai); rest TODO. */
static const struct vpnhide_offsets vpnhide_off_5_x = {
	.skb_len = 104,
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 24, /* TODO: confirm on 5.4/5.10 */
	.in_device_dev = 0,
	.inet6_ifaddr_idev = 0, /* TODO: derive for 5.x */
	.inet6_dev_dev = 0,
	/* android12-5.10 fib_info (LP64, no ifdefs before fib_nh[]): fib_nhs@96,
	 * nh@104, fib_nh[]@128; nhc_dev is first in fib_nh -> +128. */
	.fib_info_fib_nhs = 96,
	.fib_info_nh = 104,
	.fib_info_fib_nh = 128,
	/* android12-5.10 fib6_info (LP64; rt6key=20, ANDROID_KABI_RESERVE=8):
	 * nh@152, fib6_nh[]@168; nhc_dev first in fib6_nh -> +168. */
	.fib6_info_nh = 152,
	.fib6_info_fib6_nh = 168,
	.proc_uses_proc_ops = 1, /* 5.6+; for <5.6 use file_operations below */
};

/* 4.14 / 4.19 / pre-5.6 — procfs still uses `file_operations`. All struct
 * offsets TODO; this entry only encodes the proc-ABI flag for now. */
static const struct vpnhide_offsets vpnhide_off_4_x = {
	.skb_len = 104, /* TODO: confirm for 4.14 */
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 0, /* TODO */
	.in_device_dev = 0,
	.inet6_ifaddr_idev = 0,
	.inet6_dev_dev = 0,
	.proc_uses_proc_ops = 0, /* <5.6 → file_operations */
};

/*
 * Select the offset table for the running kernel. Returns NULL for an
 * unsupported version — the caller MUST refuse to install hooks then
 * (kpm-spore does the same: unknown version → bail, never guess).
 */
static inline const struct vpnhide_offsets *vpnhide_select_offsets(unsigned int kver)
{
	if (kver >= VPNHIDE_KVER(6, 0, 0))
		return &vpnhide_off_6_1; /* 6.x — only 6.1 proven so far */
	if (kver >= VPNHIDE_KVER(5, 6, 0))
		return &vpnhide_off_5_x;
	if (kver >= VPNHIDE_KVER(4, 0, 0))
		return &vpnhide_off_4_x;
	return 0; /* unsupported → do not install */
}

#endif /* VPNHIDE_KVER_OFFSETS_H */
