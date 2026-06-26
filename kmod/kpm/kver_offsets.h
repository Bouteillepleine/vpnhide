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

	/* fib_dump_info arg shape (its fib_info moved across versions):
	 *   fib_dump_fi_arg     = args[] index of the fib_info/fib_rt_info ptr
	 *                         (0 => hook not installed for this version)
	 *   fib_dump_fi_via_fri = 1 if that arg is a fib_rt_info* (fi=*(arg+0),
	 *                         5.6+), 0 if it is the fib_info* directly (<5.6).
	 * Always hooked as a 12-arg frame; only this one arg is read. */
	unsigned int fib_dump_fi_arg;
	int fib_dump_fi_via_fri;

	/* IPv6 route dump (rt6_fill_node): fib6_info.nh != 0 => nexthop object;
	 * else dev = *(rt + fib6_info_fib6_nh) (fib6_nh[0].nhc_dev). */
	unsigned int fib6_info_nh;
	unsigned int fib6_info_fib6_nh;

	/* Pre-fib6_info kernels (<= 4.14): rt6_fill_node takes a struct rt6_info*
	 * (which begins with a struct dst_entry), not a fib6_info. When
	 * rt6_via_dst is set, dev = *(rt + rt6_dst_dev) (dst_entry.dev). */
	int rt6_via_dst;
	unsigned int rt6_dst_dev;

	/* Policy rules (fib_nl_fill_rule -> struct fib_rule). */
	unsigned int fib_rule_table;
	unsigned int fib_rule_iifname;
	unsigned int fib_rule_oifname;
	unsigned int fib_rule_uid_start;
	unsigned int fib_rule_uid_end;

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
	/* in_ifaddr: hash(16)+ifa_next(8) -> ifa_dev@24; in_device.dev@0. */
	.in_ifaddr_ifa_dev = 24,
	.in_device_dev = 0,
	/* inet6_ifaddr.idev@168 (derived; past lock/dad_work — verify via gai),
	 * inet6_dev.dev@0. */
	.inet6_ifaddr_idev = 168,
	.inet6_dev_dev = 0,
	/* android12-5.10 fib_info (LP64, no ifdefs before fib_nh[]): fib_nhs@96,
	 * nh@104, fib_nh[]@128; nhc_dev is first in fib_nh -> +128. */
	.fib_info_fib_nhs = 96,
	.fib_info_nh = 104,
	.fib_info_fib_nh = 128,
	/* 5.6+ fib_dump_info(skb, portid, seq, event, fib_rt_info *fri, flags):
	 * fi = fri->fi, so arg index 4, dereffed through fri. */
	.fib_dump_fi_arg = 4,
	.fib_dump_fi_via_fri = 1,
	/* android12-5.10 fib6_info (LP64; rt6key=20, ANDROID_KABI_RESERVE=8):
	 * nh@152, fib6_nh[]@168; nhc_dev first in fib6_nh -> +168. */
	.fib6_info_nh = 152,
	.fib6_info_fib6_nh = 168,
	/* android12-5.10 struct fib_rule (LP64). */
	.fib_rule_table = 36,
	.fib_rule_iifname = 88,
	.fib_rule_oifname = 104,
	.fib_rule_uid_start = 120,
	.fib_rule_uid_end = 124,
	.proc_uses_proc_ops = 1, /* 5.6+; for <5.6 use file_operations below */
};

/* 5.4 (android11-5.4, derived from AOSP common source + QEMU-validated).
 * Mostly identical to 5.10, with two version-specific differences:
 *   - fib_dump_info still uses the legacy <5.6 prototype (fib_info* passed
 *     directly at arg 9, 11 args) — not the fib_rt_info* form 5.10 has.
 *   - struct fib6_info has no ANDROID_KABI_RESERVE before fib6_nh[] here, so
 *     fib6_nh[]@160 (vs 5.10's 168).
 * procfs is file_operations (<5.6). */
static const struct vpnhide_offsets vpnhide_off_5_4 = {
	.skb_len = 112, /* modern sk_buff head + _nfct (CONFIG_NF_CONNTRACK) ->
			 * len@112. Real 5.4 devices carry conntrack, so this is
			 * the device-faithful value; a conntrack-less kernel would
			 * be @104 (cf. the 5.10 test kernel). */
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 24, /* hash(16)+ifa_next(8) — same as 5.10 */
	.in_device_dev = 0,
	.inet6_ifaddr_idev = 168, /* rt_priority present + post-4.15 timer_list */
	.inet6_dev_dev = 0,
	/* fib_info identical to 5.10: fib_nhs@96, nh@104, fib_nh[]@128. */
	.fib_info_fib_nhs = 96,
	.fib_info_nh = 104,
	.fib_info_fib_nh = 128,
	/* legacy fib_dump_info(skb,...,tos, struct fib_info *fi, flags): fi@arg9,
	 * passed directly (not via fib_rt_info). */
	.fib_dump_fi_arg = 9,
	.fib_dump_fi_via_fri = 0,
	/* fib6_info: rt6key=20, NO KABI reserve -> nh@152, fib6_nh[]@160. */
	.fib6_info_nh = 152,
	.fib6_info_fib6_nh = 160,
	/* struct fib_rule layout identical to 5.10. */
	.fib_rule_table = 36,
	.fib_rule_iifname = 88,
	.fib_rule_oifname = 104,
	.fib_rule_uid_start = 120,
	.fib_rule_uid_end = 124,
	.proc_uses_proc_ops = 0, /* <5.6 → file_operations */
};

/* 4.19 (vanilla 4.19.325, derived from source + QEMU-validated). Pre-nexthop
 * kernel: fib_info/fib6_info have NO `struct nexthop *nh` field (so the nh
 * guards in dev_from_fib*_info skip), and fib_dump_info uses the legacy <5.6
 * prototype (fib_info* directly at arg 9). Unlike 4.14 it already has the
 * struct fib6_info IPv6 route model (4.14 still uses rt6_info). procfs is
 * file_operations (<5.6). */
static const struct vpnhide_offsets vpnhide_off_4_19 = {
	.skb_len = 112, /* modern sk_buff head + _nfct (conntrack) -> len@112 */
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 24,
	.in_device_dev = 0,
	.inet6_ifaddr_idev = 168, /* rt_priority present, post-4.15 timer_list */
	.inet6_dev_dev = 0,
	/* fib_info: fib_nhs@80, rcu@88, fib_nh[]@104; fib_nh.nh_dev is first ->
	 * dev = *(fi+104). No nexthop objects (fib_info_nh = 0). */
	.fib_info_fib_nhs = 80,
	.fib_info_nh = 0,
	.fib_info_fib_nh = 104,
	.fib_dump_fi_arg = 9, /* legacy prototype: fib_info* at arg 9 */
	.fib_dump_fi_via_fri = 0,
	/* fib6_info (no nexthop objects): fib6_nh embedded (not fib_nh_common),
	 * nh_dev is the 2nd field (@+16 after in6_addr nh_gw). With
	 * CONFIG_IPV6_ROUTER_PREF (Android-common) fib6_nh@160 -> nh_dev@176.
	 * (Without ROUTER_PREF it would be @168 — config-sensitive.) */
	.fib6_info_nh = 0,
	.fib6_info_fib6_nh = 176,
	/* struct fib_rule layout identical to 5.4/5.10. */
	.fib_rule_table = 36,
	.fib_rule_iifname = 88,
	.fib_rule_oifname = 104,
	.fib_rule_uid_start = 120,
	.fib_rule_uid_end = 124,
	.proc_uses_proc_ops = 0, /* <5.6 → file_operations */
};

/* 4.14 (vanilla 4.14.336, derived from source + QEMU-validated, 9/9). Oldest
 * target and the most structurally different: fib_dump_info uses the legacy
 * <5.6 prototype (fi at arg 9), there are no nexthop objects, and IPv6 still
 * uses struct rt6_info (not fib6_info) — so the IPv6 dev comes from the
 * embedded dst_entry (rt6_via_dst) rather than a fib6_nh walk. procfs is
 * file_operations (<5.6). */
static const struct vpnhide_offsets vpnhide_off_4_x = {
	.skb_len = 104, /* older sk_buff head -> len@104 even with conntrack */
	.netdev_name = 0,
	.seqfile_buf = 0,
	.seqfile_count = 24,
	.in_ifaddr_ifa_dev = 24, /* in_ifaddr: hash(16)+ifa_next(8) — same as 5.x */
	.in_device_dev = 0,
	.inet6_ifaddr_idev = 168, /* no rt_priority (-4) but timer_list has `data` (+8) -> 168 */
	.inet6_dev_dev = 0,
	/* fib_info: fib_nhs@80, rcu@88, fib_nh[]@104 (fib_weight, if
	 * CONFIG_IP_ROUTE_MULTIPATH, fills the pad slot @84 so fib_nh stays @104);
	 * fib_nh.nh_dev is first. No nexthop objects. */
	.fib_info_fib_nhs = 80,
	.fib_info_nh = 0,
	.fib_info_fib_nh = 104,
	.fib_dump_fi_arg = 9, /* legacy prototype: fib_info* at arg 9 */
	.fib_dump_fi_via_fri = 0,
	/* IPv6: rt6_fill_node(rt6_info*). rt6_info begins with struct dst_entry,
	 * whose first member is net_device *dev -> dev = *(rt + 0). */
	.fib6_info_nh = 0,
	.fib6_info_fib6_nh = 0,
	.rt6_via_dst = 1,
	.rt6_dst_dev = 0,
	/* struct fib_rule layout matches 5.10; the symbol is fib_nl_fill_rule
	 * .isra.N under gcc — the fuzzy resolver finds it. */
	.fib_rule_table = 36,
	.fib_rule_iifname = 88,
	.fib_rule_oifname = 104,
	.fib_rule_uid_start = 120,
	.fib_rule_uid_end = 124,
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
	if (kver >= VPNHIDE_KVER(5, 0, 0))
		return &vpnhide_off_5_4; /* 5.0–5.5: legacy fib_dump_info + proc */
	if (kver >= VPNHIDE_KVER(4, 19, 0))
		return &vpnhide_off_4_19; /* 4.19: fib6_info, no nexthop objects */
	if (kver >= VPNHIDE_KVER(4, 0, 0))
		return &vpnhide_off_4_x;
	return 0; /* unsupported → do not install */
}

#endif /* VPNHIDE_KVER_OFFSETS_H */
