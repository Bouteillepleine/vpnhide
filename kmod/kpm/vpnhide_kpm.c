// SPDX-License-Identifier: MIT
/*
 * vpnhide — KernelPatch Module (KPM) backend.  *** WIP ***
 *
 * A third native backend alongside the kretprobe `.ko`, for kernels the
 * `.ko` can't serve: non-GKI / proprietary kernels with no published
 * headers or Module.symvers (e.g. kernel 4.14 — issue #33; HyperOS 5.4).
 * Loaded by the KernelPatch runtime (target: KPatch-Next on KernelSU-Next),
 * NOT insmod — so it also works where module signing blocks a `.ko`.
 *
 * STATUS: builds (`make kpm`) and runs end-to-end under QEMU via the KPM
 * harness (../test/run-kpm.sh). All 10 hooks are A/B-validated with no panic
 * (full native-vector parity with the .ko) on FIVE kernels, each a separate
 * from-source QEMU Image: 4.14, 4.19, 5.4, 5.10 and 6.1 (9/9 vectors apiece).
 * The procfs control plane is still TODO (A/B uses load-args). Every per-kver
 * offset must pass the harness before that version ships — a wrong offset is a
 * contained QEMU panic / A/B failure here, but a bootloop on a real device.
 *
 * DESIGN (how this differs from soranerai's prototype, deliberately):
 *   - ONE source + a runtime kver offset table (kver_offsets.h), not three
 *     per-version copies → one binary across 4.x/5.x/6.x.
 *   - Per-call state via `fargs->local.dataN`, NOT a per-CPU MPIDR stash
 *     (which races when a thread migrates between the before/after callback).
 *   - Filtering algorithms shared with the `.ko` via ../shared/vpnhide_logic.h.
 *   - VPN-name matching from the generated single source of truth
 *     (../generated/iface_lists.h → data/interfaces.toml), incl. the `if<N>`
 *     pattern (issue #86) that the hardcoded community lists miss.
 *   - rt_fill_info (single-route lookup) is intentionally NOT hooked: the
 *     QEMU harness proved its arg→register ABI is unstable. soranerai hooks
 *     it; we don't.
 */
#pragma GCC visibility push(hidden)
#include <compiler.h>
#include <common.h> /* kver + VERSION(major,minor,patch) */
#include <kpmodule.h>
#include <hook.h>
#include <kputils.h> /* current_uid() */
#include <log.h>
#include <symbol.h>
#include <kallsyms.h> /* kallsyms_lookup_name (KP-resolved pointer) */
#pragma GCC visibility pop

#include "../generated/iface_lists.h"
#include "../shared/vpnhide_logic.h"
#include "kver_offsets.h"

KPM_NAME("vpnhide");
KPM_VERSION("0.0.1-wip");
KPM_LICENSE("GPL v2"); /* GPL to use GPL-only kernel symbols at runtime */
KPM_AUTHOR("okhsunrog");
KPM_DESCRIPTION("Hide VPN interfaces from selected UIDs (KPM backend, WIP)");

#define MODNAME "vpnhide"
#define MAX_TARGET_UIDS 64

/* ------------------------------------------------------------------ */
/*  Resolved state                                                    */
/* ------------------------------------------------------------------ */

static const struct vpnhide_offsets *off; /* selected per running kver */

static uint32_t target_uids[MAX_TARGET_UIDS];
static int nr_target_uids;
static bool debug_enabled;

/* kernel functions resolved at init via kallsyms */
static void *(*_proc_create_data)(const char *, uint16_t, void *, void *, void *);
static void (*_remove_proc_entry)(const char *, void *);
static int (*_single_open)(void *, void *, void *);
static int (*_single_release)(void *, void *);
static void *_seq_read, *_seq_lseek;
static void (*_seq_printf)(void *, const char *, ...);
static unsigned long (*_copy_from_user)(void *, const void *, unsigned long);
static unsigned long (*_copy_to_user)(void *, const void *, unsigned long);
static void (*_skb_trim)(void *, unsigned int);

#define vpnhide_dbg(fmt, ...) \
	do { if (debug_enabled) logki(MODNAME ": " fmt, ##__VA_ARGS__); } while (0)

/* ------------------------------------------------------------------ */
/*  Core helpers                                                      */
/* ------------------------------------------------------------------ */

static int is_target_uid(void)
{
	uid_t uid;
	int i;

	if (nr_target_uids <= 0)
		return 0;
	uid = current_uid();
	for (i = 0; i < nr_target_uids; i++)
		if (target_uids[i] == uid)
			return 1;
	return 0;
}

/* NUL-safe copy of a kernel iface name, then match via the generated rules. */
static int iface_is_vpn(const char *name)
{
	char buf[VPNHIDE_IFNAMSIZ];
	int i;

	if (!name)
		return 0;
	for (i = 0; i < VPNHIDE_IFNAMSIZ - 1 && name[i]; i++)
		buf[i] = name[i];
	buf[i] = '\0';
	return vpnhide_iface_is_vpn(buf);
}

/* Read net_device.name given a net_device* (name is at offset 0 everywhere). */
static const char *netdev_name(void *dev)
{
	return dev ? (const char *)((char *)dev + off->netdev_name) : 0;
}

/* ================================================================== */
/*  Hook 1 (PoC): fib_route_seq_show — /proc/net/route                */
/*  arg0 = struct seq_file *.  Compact VPN lines out of this call's    */
/*  newly-written region using the shared seq-line compactor.          */
/* ================================================================== */

static void fib_route_before(hook_fargs2_t *fargs, void *udata)
{
	/* Stash seq_file->count at entry so the after-callback only touches
	 * THIS call's output, not earlier entries. (Correctness fix the .ko
	 * already does; soranerai re-scans the whole buffer each call.) */
	void *seq = (void *)fargs->arg0;
	unsigned long count =
		seq ? *(unsigned long *)((char *)seq + off->seqfile_count) : 0;
	fargs->local.data0 = (uint64_t)count;
}

static void fib_route_after(hook_fargs2_t *fargs, void *udata)
{
	void *seq = (void *)fargs->arg0;
	char *buf;
	unsigned long *countp;
	unsigned long start = (unsigned long)fargs->local.data0;

	if (!seq || !is_target_uid())
		return;

	buf = *(char **)((char *)seq + off->seqfile_buf);
	countp = (unsigned long *)((char *)seq + off->seqfile_count);
	*countp = vpnhide_compact_seq_lines(buf, start, *countp,
					    VPNHIDE_FIELD_FIRST, iface_is_vpn);
}

/* ipv6_route_seq_show — /proc/net/ipv6_route. Same as fib_route but the iface
 * name is the LAST field. Shares fib_route_before (stashes seq->count). */
static void ipv6_route_after(hook_fargs2_t *fargs, void *udata)
{
	void *seq = (void *)fargs->arg0;
	char *buf;
	unsigned long *countp;
	unsigned long start = (unsigned long)fargs->local.data0;

	if (!seq || !is_target_uid())
		return;

	buf = *(char **)((char *)seq + off->seqfile_buf);
	countp = (unsigned long *)((char *)seq + off->seqfile_count);
	*countp = vpnhide_compact_seq_lines(buf, start, *countp,
					    VPNHIDE_FIELD_LAST, iface_is_vpn);
}

/* ================================================================== */
/*  Hook 2 (PoC): rtnl_fill_ifinfo — RTM_NEWLINK (getifaddrs path)    */
/*  arg0 = skb, arg1 = net_device.  If the dev is a VPN iface and the  */
/*  caller is a target, undo whatever the fill wrote (skb_trim back to  */
/*  the saved length) and return 0 — same approach as the .ko.         */
/*  We do NOT return -EMSGSIZE (infinite retry on 6.1 — issue #38).    */
/* ================================================================== */

static void rtnl_fill_before(hook_fargs12_t *fargs, void *udata)
{
	void *skb = (void *)fargs->arg0;
	void *dev = (void *)fargs->arg1;

	fargs->local.data0 = 0; /* should_filter */
	if (!is_target_uid() || !skb || !dev)
		return;
	if (!iface_is_vpn(netdev_name(dev)))
		return;

	fargs->local.data0 = 1; /* filter */
	fargs->local.data1 = (uint64_t)skb;
	fargs->local.data2 =
		(uint64_t) * (unsigned int *)((char *)skb + off->skb_len);
}

static void rtnl_fill_after(hook_fargs12_t *fargs, void *udata)
{
	if (!fargs->local.data0)
		return;
	if ((long)fargs->ret < 0)
		return; /* the fill already failed; nothing to undo */
	if (_skb_trim)
		_skb_trim((void *)fargs->local.data1,
			  (unsigned int)fargs->local.data2);
	fargs->ret = 0;
}

/* ================================================================== */
/*  Hook 4: dev_ioctl — per-interface ioctls (SIOCGIF* by name)        */
/*  arg1 = cmd, arg2 = ifr (ifr_name at offset 0, uapi-stable). NOTE:   */
/*  arg2 is a *kernel* struct ifreq* on >=5.5, but a *userspace* ptr on */
/*  <5.5 (4.14/4.19/5.4 do the copy inside dev_ioctl). Dereferencing a  */
/*  user ptr directly from kernel context faults under PAN on real HW   */
/*  (QEMU without PAN didn't), so the name is read through the right     */
/*  path for whichever the pointer is. If it names a VPN iface, -ENODEV. */
/* ================================================================== */

#define VPNHIDE_ENODEV ((uint64_t)(-19))

/* arm64: TTBR1 (kernel) addresses have the top 16 bits set; user ptrs don't. */
static int ptr_is_kernel(const void *p)
{
	return ((unsigned long)p >> 48) == 0xffffUL;
}

/* SIOCGIF* range (0x8910..0x8930). SIOCGIFCONF (0x8912) goes via sock_ioctl,
 * never dev_ioctl, so the overlap is harmless here. */
static int is_siocgif(unsigned long cmd)
{
	return cmd >= 0x8910 && cmd <= 0x8930;
}

static void dev_ioctl_after(hook_fargs5_t *fargs, void *udata)
{
	unsigned long cmd = (unsigned long)fargs->arg1;
	const char *ifr = (const char *)fargs->arg2; /* ifr_name @ offset 0 */
	char name[VPNHIDE_IFNAMSIZ];
	int is_vpn;

	if ((long)fargs->ret != 0 || !ifr)
		return;
	if (!is_target_uid() || !is_siocgif(cmd))
		return;

	if (ptr_is_kernel(ifr)) {
		is_vpn = iface_is_vpn(ifr); /* >=5.5: ifr is kernel memory */
	} else {
		/* <5.5: ifr is a __user pointer — copy the name in safely. */
		if (!_copy_from_user ||
		    _copy_from_user(name, ifr, VPNHIDE_IFNAMSIZ))
			return;
		name[VPNHIDE_IFNAMSIZ - 1] = '\0';
		is_vpn = iface_is_vpn(name);
	}
	if (is_vpn) {
		vpnhide_dbg("dev_ioctl: hiding cmd=0x%lx\n", cmd);
		fargs->ret = VPNHIDE_ENODEV;
	}
}

/* ================================================================== */
/*  Hook 5: sock_ioctl — SIOCGIFCONF enumeration                       */
/*  arg1 = cmd, arg2 = userspace struct ifconf*. After the call, compact */
/*  VPN entries out of the returned ifreq[] array. All uapi-stable:      */
/*  struct ifconf { int ifc_len; <pad>; ptr ifc_req@8 }, sizeof ifreq=40,*/
/*  ifr_name @ offset 0.                                                */
/* ================================================================== */

#define VPNHIDE_SIOCGIFCONF 0x8912
#define VPNHIDE_IFREQ_SZ 40 /* sizeof(struct ifreq) on arm64 */

static void filter_ifconf(void *uifc)
{
	char ifc[16];   /* struct ifconf snapshot: len@0 (int), req@8 (ptr) */
	char e[VPNHIDE_IFREQ_SZ];
	char *req;
	int len, n, i, dst = 0;

	if (!_copy_from_user || !_copy_to_user)
		return;
	if (_copy_from_user(ifc, uifc, sizeof(ifc)))
		return;
	len = *(int *)ifc;
	req = *(char **)(ifc + 8);
	if (!req || len <= 0)
		return;

	n = len / VPNHIDE_IFREQ_SZ;
	for (i = 0; i < n; i++) {
		if (_copy_from_user(e, req + (long)i * VPNHIDE_IFREQ_SZ,
				    VPNHIDE_IFREQ_SZ))
			return;
		e[VPNHIDE_IFNAMSIZ - 1] = '\0';
		if (iface_is_vpn(e))
			continue; /* drop VPN entry */
		if (dst != i &&
		    _copy_to_user(req + (long)dst * VPNHIDE_IFREQ_SZ, e,
				  VPNHIDE_IFREQ_SZ))
			return;
		dst++;
	}
	if (dst != n) {
		int newlen = dst * VPNHIDE_IFREQ_SZ;

		_copy_to_user(uifc, &newlen, sizeof(newlen)); /* shrink ifc_len */
		vpnhide_dbg("sock_ioctl: ifconf %d -> %d\n", len, newlen);
	}
}

static void sock_ioctl_after(hook_fargs3_t *fargs, void *udata)
{
	unsigned long cmd = (unsigned long)fargs->arg1;
	void *argp = (void *)fargs->arg2;

	if ((long)fargs->ret != 0 || !argp)
		return;
	if (cmd != VPNHIDE_SIOCGIFCONF || !is_target_uid())
		return;
	filter_ifconf(argp);
}

/* ================================================================== */
/*  Hooks 9-10: inet_fill_ifaddr / inet6_fill_ifaddr — RTM_GETADDR     */
/*  arg0 = skb, arg1 = ifa.  getifaddrs() enumerates addresses via      */
/*  RTM_GETADDR even when the link (rtnl_fill_ifinfo) is hidden, so      */
/*  these close the address path. dev = ifa->{ifa_dev|idev}->dev.       */
/* ================================================================== */

/* p = *(*(base+off1)+off2) with NULL guards (two-pointer deref). */
static void *deref2(void *base, unsigned int off1, unsigned int off2)
{
	void *p;

	if (!base)
		return 0;
	p = *(void **)((char *)base + off1);
	if (!p)
		return 0;
	return *(void **)((char *)p + off2);
}

/* Shared by both addr-fill hooks: stash skb + len if ifa's dev is VPN. */
static void addr_fill_before(hook_fargs4_t *fargs, void *dev)
{
	void *skb = (void *)fargs->arg0;

	fargs->local.data0 = 0;
	if (!is_target_uid() || !skb || !dev)
		return;
	if (!iface_is_vpn(netdev_name(dev)))
		return;
	fargs->local.data0 = 1;
	fargs->local.data1 = (uint64_t)skb;
	fargs->local.data2 =
		(uint64_t) * (unsigned int *)((char *)skb + off->skb_len);
}

static void addr_fill_after(hook_fargs4_t *fargs, void *udata)
{
	if (!fargs->local.data0)
		return;
	if ((long)fargs->ret < 0)
		return;
	if (_skb_trim)
		_skb_trim((void *)fargs->local.data1,
			  (unsigned int)fargs->local.data2);
	fargs->ret = 0;
}

static void inet_fill_before(hook_fargs4_t *fargs, void *udata)
{
	void *dev = deref2((void *)fargs->arg1, off->in_ifaddr_ifa_dev,
			   off->in_device_dev);
	addr_fill_before(fargs, dev);
}

static void inet6_fill_before(hook_fargs4_t *fargs, void *udata)
{
	void *dev = deref2((void *)fargs->arg1, off->inet6_ifaddr_idev,
			   off->inet6_dev_dev);
	addr_fill_before(fargs, dev);
}

/* ================================================================== */
/*  Hook 6: fib_dump_info — IPv4 RTM_GETROUTE dump (issue #86)         */
/*  arg0 = skb; the fib_info arg index varies by version (table-driven).*/
/*  Resolve the route's output dev (fib_nh[0].nh_common.nhc_dev for a   */
/*  legacy single-nexthop route) and, if it's a VPN iface, undo the     */
/*  fill (skb_trim) + ret 0.                                            */
/*  This is the first hook that dereferences version-specific kernel    */
/*  structs — offsets live in kver_offsets.h, validated by the harness. */
/* ================================================================== */

/* net_device* for a route's fib_info (legacy single-nexthop path only). */
static void *dev_from_fib_info(void *fi)
{
	void *nh;
	int nhs;

	if (!fi || !off->fib_info_fib_nh)
		return 0;
	/* A non-NULL nexthop object means an `ip nexthop`-style route, whose
	 * dev lives behind a separate struct nexthop walk — not unpacked yet.
	 * fib_info_nh == 0 means this version has no nexthop-object field at all
	 * (e.g. 4.14), so skip the check rather than misread fib_info's head. */
	if (off->fib_info_nh) {
		nh = *(void **)((char *)fi + off->fib_info_nh);
		if (nh)
			return 0;
	}
	nhs = *(int *)((char *)fi + off->fib_info_fib_nhs);
	if (nhs <= 0)
		return 0;
	/* nhc_dev is the first member of fib_nh[0] (== fib_nh_common). */
	return *(void **)((char *)fi + off->fib_info_fib_nh);
}

/*
 * fib_dump_info's prototype moved the fib_info across versions, so the arg
 * index + how to reach the fib_info are table-driven (kver_offsets.h):
 *   - 5.6+ : fib_dump_info(skb, portid, seq, event, struct fib_rt_info *fri,
 *            flags) — fi = fri->fi, fi_arg=4, via_fri=1.
 *   - <5.6 : fib_dump_info(skb, portid, seq, event, tb_id, type, dst, dst_len,
 *            tos, struct fib_info *fi, flags) — fi_arg=9, via_fri=0.
 * Always hooked as a 12-arg frame (argno=11): KP just saves the extra slots,
 * the same way rtnl_fill_ifinfo is over-specified — only the fi arg is read.
 */
static void fib_dump_before(hook_fargs12_t *fargs, void *udata)
{
	void *skb = (void *)fargs->arg0;
	void *p = (void *)fargs->args[off->fib_dump_fi_arg];
	void *fi, *dev;

	fargs->local.data0 = 0;
	if (!is_target_uid() || !skb || !p)
		return;
	fi = off->fib_dump_fi_via_fri ? *(void **)p : p; /* fib_rt_info.fi @0 */
	dev = dev_from_fib_info(fi);
	if (!dev || !iface_is_vpn(netdev_name(dev)))
		return;

	fargs->local.data0 = 1;
	fargs->local.data1 = (uint64_t)skb;
	fargs->local.data2 =
		(uint64_t) * (unsigned int *)((char *)skb + off->skb_len);
}

static void fib_dump_after(hook_fargs12_t *fargs, void *udata)
{
	if (!fargs->local.data0)
		return;
	if ((long)fargs->ret < 0)
		return;
	if (_skb_trim)
		_skb_trim((void *)fargs->local.data1,
			  (unsigned int)fargs->local.data2);
	fargs->ret = 0;
}

/* ================================================================== */
/*  Hook 7: rt6_fill_node — IPv6 RTM_GETROUTE dump                     */
/*  arg1 = skb, arg2 = fib6_info*.  IPv6 analogue of fib_dump_info.     */
/* ================================================================== */

static void *dev_from_fib6_info(void *rt)
{
	void *nh;

	if (!rt)
		return 0;
	/* Pre-fib6_info kernels: rt is a struct rt6_info* whose embedded
	 * dst_entry holds the dev directly (no nexthop walk). */
	if (off->rt6_via_dst)
		return *(void **)((char *)rt + off->rt6_dst_dev);
	if (!off->fib6_info_fib6_nh)
		return 0;
	/* fib6_info_nh == 0 => this version has no nexthop-object field (e.g.
	 * 4.19/4.14); skip the check rather than misread fib6_info's head. */
	if (off->fib6_info_nh) {
		nh = *(void **)((char *)rt + off->fib6_info_nh);
		if (nh)
			return 0; /* nexthop-object route — not unpacked yet */
	}
	return *(void **)((char *)rt + off->fib6_info_fib6_nh);
}

static void rt6_fill_before(hook_fargs12_t *fargs, void *udata)
{
	void *skb = (void *)fargs->arg1;
	void *rt = (void *)fargs->arg2;
	void *dev;

	fargs->local.data0 = 0;
	if (!is_target_uid() || !skb || !rt)
		return;
	dev = dev_from_fib6_info(rt);
	if (!dev || !iface_is_vpn(netdev_name(dev)))
		return;

	fargs->local.data0 = 1;
	fargs->local.data1 = (uint64_t)skb;
	fargs->local.data2 =
		(uint64_t) * (unsigned int *)((char *)skb + off->skb_len);
}

static void rt6_fill_after(hook_fargs12_t *fargs, void *udata)
{
	if (!fargs->local.data0)
		return;
	if ((long)fargs->ret < 0)
		return;
	if (_skb_trim)
		_skb_trim((void *)fargs->local.data1,
			  (unsigned int)fargs->local.data2);
	fargs->ret = 0;
}

/* ================================================================== */
/*  Hook 8: fib_nl_fill_rule — RTM_GETRULE (policy routing rules)      */
/*  arg0 = skb, arg1 = fib_rule*.  Hide a rule if it routes via a VPN   */
/*  iface (iif/oifname) or selects a non-standard table for a target    */
/*  UID range — the per-UID VPN policy rules.                          */
/* ================================================================== */

static void fib_rule_before(hook_fargs8_t *fargs, void *udata)
{
	void *skb = (void *)fargs->arg0;
	char *rule = (char *)fargs->arg1;
	const char *iif, *oif;
	int filter = 0;

	fargs->local.data0 = 0;
	if (!is_target_uid() || !skb || !rule || !off->fib_rule_table)
		return;

	iif = rule + off->fib_rule_iifname;
	oif = rule + off->fib_rule_oifname;
	if ((iif[0] && iface_is_vpn(iif)) || (oif[0] && iface_is_vpn(oif))) {
		filter = 1;
	} else {
		uint32_t table = *(uint32_t *)(rule + off->fib_rule_table);
		uint32_t start = *(uint32_t *)(rule + off->fib_rule_uid_start);
		uint32_t end = *(uint32_t *)(rule + off->fib_rule_uid_end);
		uint32_t uid = (uint32_t)current_uid();

		if (uid >= start && uid <= end &&
		    (start != 0 || end != 0xffffffffu) && table != 253 &&
		    table != 254 && table != 255 && table > 100)
			filter = 1;
	}

	if (filter) {
		fargs->local.data0 = 1;
		fargs->local.data1 = (uint64_t)skb;
		fargs->local.data2 = (uint64_t) *
				     (unsigned int *)((char *)skb + off->skb_len);
	}
}

static void fib_rule_after(hook_fargs8_t *fargs, void *udata)
{
	if (!fargs->local.data0)
		return;
	if ((long)fargs->ret < 0)
		return;
	if (_skb_trim)
		_skb_trim((void *)fargs->local.data1,
			  (unsigned int)fargs->local.data2);
	fargs->ret = 0;
}

/*
 * HOOK COVERAGE — full parity with vpnhide_kmod.c (the .ko). All 10 hooks
 * ported and QEMU-validated A/B on android12-5.10 (no panic). Mirror the
 * .ko's logic; reuse shared/vpnhide_logic.h. Per-version struct offsets live
 * in kver_offsets.h (5.10 only so far) — a wrong offset is a contained QEMU
 * A/B fail / panic here, a bootloop on a real device.
 *
 *   fib_route_seq_show     /proc/net/route        ✓ (seq compactor)
 *   ipv6_route_seq_show    /proc/net/ipv6_route   ✓ (seq compactor)
 *   rtnl_fill_ifinfo       RTM_GETLINK            ✓ (skb.len)
 *   inet_fill_ifaddr       RTM_GETADDR v4         ✓ (in_ifaddr.ifa_dev->dev)
 *   inet6_fill_ifaddr      RTM_GETADDR v6         ✓ (inet6_ifaddr.idev->dev)
 *   dev_ioctl              SIOCGIF* by name       ✓ (ret -> -ENODEV)
 *   sock_ioctl             SIOCGIFCONF            ✓ (ifconf compaction)
 *   fib_dump_info          RTM_GETROUTE v4 dump   ✓ (#86; fib_info nexthop)
 *   rt6_fill_node          RTM_GETROUTE v6 dump   ✓ (fib6_info nexthop)
 *   fib_nl_fill_rule       RTM_GETRULE            ✓ (fib_rule iif/oif/uid)
 *   ( rt_fill_info — intentionally NOT hooked; unstable arg->reg ABI )
 */

/* ------------------------------------------------------------------ */
/*  /proc/vpnhide_targets + /proc/vpnhide_debug                       */
/* ------------------------------------------------------------------ */

static long targets_write(void *file, const char __user *ubuf, unsigned long count,
			  long *ppos)
{
	char buf[1024];
	int n;

	if (count == 0)
		return 0;
	if (count > sizeof(buf) - 1)
		count = sizeof(buf) - 1;
	if (_copy_from_user && _copy_from_user(buf, ubuf, count))
		return -14; /* -EFAULT */
	buf[count] = '\0';

	n = vpnhide_parse_target_uids(buf, count, target_uids, MAX_TARGET_UIDS);
	nr_target_uids = n;
	vpnhide_dbg("loaded %d target UIDs\n", n);
	return (long)count;
}

/*
 * proc_create needs a `struct proc_ops` (>=5.6) or a `struct file_operations`
 * (<5.6). We don't have the real headers, so the field layout is a mock —
 * and getting it wrong is the likely cause of the HyperOS-5.4 crash report.
 * off->proc_uses_proc_ops selects which mock to register.
 * TODO: define both mock layouts + the targets/debug show/open handlers, and
 * register the matching one. Kept as a stub here so the skeleton stays focused
 * on the hooks; see kver_offsets.h for the ABI flag.
 */

/* ------------------------------------------------------------------ */
/*  Init / exit                                                       */
/* ------------------------------------------------------------------ */

/*
 * Resolve a hook target by name, tolerating compiler-renamed clones. GCC may
 * emit a static function as `name.isra.N` / `name.constprop.N` (a specialised
 * clone) — kallsyms_lookup_name("name") then misses it. Android *device*
 * kernels are clang-built (name intact), but a gcc-built kernel (incl. our
 * QEMU test kernels) renames e.g. fib_nl_fill_rule -> fib_nl_fill_rule.isra.N.
 * Fall back to the first symbol equal to `name`, or `name.` + suffix — the
 * dot only appears on compiler clones, so this never matches an unrelated fn.
 */
struct vpnhide_sym_q {
	const char *base;
	int baselen;
	unsigned long addr;
};

static int vpnhide_sym_cb(void *data, const char *name, struct module *mod,
			  unsigned long addr)
{
	struct vpnhide_sym_q *q = data;
	int i;

	(void)mod;
	for (i = 0; i < q->baselen; i++)
		if (name[i] != q->base[i])
			return 0;
	if (name[q->baselen] == '\0' || name[q->baselen] == '.') {
		q->addr = addr;
		return 1; /* found — stop iterating */
	}
	return 0;
}

static unsigned long lookup_fn(const char *name)
{
	unsigned long fn = kallsyms_lookup_name(name);
	struct vpnhide_sym_q q;

	if (fn)
		return fn;
	if (!kallsyms_on_each_symbol)
		return 0;
	q.base = name;
	q.baselen = 0;
	while (name[q.baselen])
		q.baselen++;
	q.addr = 0;
	kallsyms_on_each_symbol(vpnhide_sym_cb, &q);
	return q.addr;
}

static int resolve_symbols(void)
{
	_proc_create_data = (void *)kallsyms_lookup_name("proc_create_data");
	_remove_proc_entry = (void *)kallsyms_lookup_name("remove_proc_entry");
	_single_open = (void *)kallsyms_lookup_name("single_open");
	_single_release = (void *)kallsyms_lookup_name("single_release");
	_seq_read = (void *)kallsyms_lookup_name("seq_read");
	_seq_lseek = (void *)kallsyms_lookup_name("seq_lseek");
	_seq_printf = (void *)kallsyms_lookup_name("seq_printf");

	_copy_from_user = (void *)kallsyms_lookup_name("__arch_copy_from_user");
	if (!_copy_from_user)
		_copy_from_user = (void *)kallsyms_lookup_name("_copy_from_user");

	_copy_to_user = (void *)kallsyms_lookup_name("__arch_copy_to_user");
	if (!_copy_to_user)
		_copy_to_user = (void *)kallsyms_lookup_name("_copy_to_user");

	_skb_trim = (void *)kallsyms_lookup_name("__skb_trim");
	if (!_skb_trim)
		_skb_trim = (void *)kallsyms_lookup_name("skb_trim");

	/* Hooks need these; proc is best-effort. */
	return _skb_trim ? 0 : -1;
}

/* Parse a NUL-terminated list of target UIDs (newline/space separated, `#`
 * comments) into the live set. Used from KPM load args and the ctl0 control
 * channel — same parser as the .ko's /proc writer (shared/vpnhide_logic.h).
 * proc is a secondary path (TODO); args/ctl0 need no procfs ABI guessing. */
static void apply_targets(const char *s)
{
	unsigned long n = 0;

	if (!s)
		return;
	while (s[n])
		n++;
	nr_target_uids = vpnhide_parse_target_uids(s, n, target_uids,
						   MAX_TARGET_UIDS);
	vpnhide_dbg("loaded %d target UIDs\n", nr_target_uids);
}

static long vpnhide_kpm_init(const char *args, const char *event,
			     void *__user reserved)
{
	unsigned long fn;

	logki(MODNAME ": KPM init (event=%s) kver=0x%x\n", event ? event : "",
	      (unsigned int)kver);

	/* `kver` is KernelPatch's running-kernel version (common.h), encoded
	 * the same way as VPNHIDE_KVER. NULL table = unsupported → bail. */
	off = vpnhide_select_offsets((unsigned int)kver);
	if (!off) {
		logki(MODNAME ": unsupported kernel version — refusing to install\n");
		return -1; /* never guess offsets */
	}
	if (resolve_symbols() != 0) {
		logki(MODNAME ": symbol resolution failed\n");
		return -1;
	}

	/* Targets can come at load time: sc_kpm_load(key, path, "10010 10020").
	 * (proc + a runtime ctl0 path also feed the same set.) */
	apply_targets(args);

	/* TODO: create /proc/vpnhide_targets + /proc/vpnhide_debug using the
	 * proc-ABI selected by off->proc_uses_proc_ops. */

	/*
	 * Install hooks. Each one is gated on the offset(s) it dereferences
	 * being known for this kernel version (0 => not installed), so a
	 * partially-filled offset table is SAFE: a hook never runs with a
	 * wrong/zero offset and panics. seq_file + ioctl hooks need only
	 * stable offsets (seqfile_count, uapi ifreq) so they install whenever
	 * the symbol exists. hook_wrap(func, argno, before, after, udata).
	 */
	if (off->seqfile_count) {
		fn = lookup_fn("fib_route_seq_show");
		if (fn)
			hook_wrap((void *)fn, 2, (void *)fib_route_before,
				  (void *)fib_route_after, 0);
		fn = lookup_fn("ipv6_route_seq_show");
		if (fn)
			hook_wrap((void *)fn, 2, (void *)fib_route_before,
				  (void *)ipv6_route_after, 0);
	}

	fn = lookup_fn("dev_ioctl");
	if (fn)
		hook_wrap((void *)fn, 5, 0, (void *)dev_ioctl_after, 0);
	fn = lookup_fn("sock_ioctl");
	if (fn)
		hook_wrap((void *)fn, 3, 0, (void *)sock_ioctl_after, 0);

	if (off->skb_len) {
		fn = lookup_fn("rtnl_fill_ifinfo");
		if (fn)
			hook_wrap((void *)fn, 12, (void *)rtnl_fill_before,
				  (void *)rtnl_fill_after, 0);
	}
	if (off->in_ifaddr_ifa_dev) {
		fn = lookup_fn("inet_fill_ifaddr");
		if (fn)
			hook_wrap((void *)fn, 3, (void *)inet_fill_before,
				  (void *)addr_fill_after, 0);
	}
	if (off->inet6_ifaddr_idev) {
		fn = lookup_fn("inet6_fill_ifaddr");
		if (fn)
			hook_wrap((void *)fn, 3, (void *)inet6_fill_before,
				  (void *)addr_fill_after, 0);
	}
	if (off->fib_dump_fi_arg) {
		fn = lookup_fn("fib_dump_info");
		if (fn)
			hook_wrap((void *)fn, 11, (void *)fib_dump_before,
				  (void *)fib_dump_after, 0);
	}
	if (off->fib6_info_fib6_nh || off->rt6_via_dst) {
		fn = lookup_fn("rt6_fill_node");
		if (fn)
			hook_wrap((void *)fn, 11, (void *)rt6_fill_before,
				  (void *)rt6_fill_after, 0);
	}
	if (off->fib_rule_table) {
		fn = lookup_fn("fib_nl_fill_rule");
		if (fn)
			hook_wrap((void *)fn, 7, (void *)fib_rule_before,
				  (void *)fib_rule_after, 0);
	}

	logki(MODNAME ": KPM hooks installed\n");
	return 0;
}

static long vpnhide_kpm_ctl0(const char *args, char *__user out_msg, int outlen)
{
	/* Runtime control via supercall: sc_kpm_control(key, "vpnhide", uids...).
	 * Lets the test / app update targets without a reboot or proc write. */
	(void)out_msg;
	(void)outlen;
	apply_targets(args);
	return nr_target_uids;
}

static long vpnhide_kpm_exit(void *__user reserved)
{
	unsigned long fn;

	fn = lookup_fn("fib_route_seq_show");
	if (fn)
		hook_unwrap((void *)fn, (void *)fib_route_before,
			    (void *)fib_route_after);
	fn = lookup_fn("rtnl_fill_ifinfo");
	if (fn)
		hook_unwrap((void *)fn, (void *)rtnl_fill_before,
			    (void *)rtnl_fill_after);
	fn = lookup_fn("ipv6_route_seq_show");
	if (fn)
		hook_unwrap((void *)fn, (void *)fib_route_before,
			    (void *)ipv6_route_after);
	fn = lookup_fn("inet_fill_ifaddr");
	if (fn)
		hook_unwrap((void *)fn, (void *)inet_fill_before,
			    (void *)addr_fill_after);
	fn = lookup_fn("inet6_fill_ifaddr");
	if (fn)
		hook_unwrap((void *)fn, (void *)inet6_fill_before,
			    (void *)addr_fill_after);
	fn = lookup_fn("dev_ioctl");
	if (fn)
		hook_unwrap((void *)fn, 0, (void *)dev_ioctl_after);
	fn = lookup_fn("sock_ioctl");
	if (fn)
		hook_unwrap((void *)fn, 0, (void *)sock_ioctl_after);
	fn = lookup_fn("fib_dump_info");
	if (fn)
		hook_unwrap((void *)fn, (void *)fib_dump_before,
			    (void *)fib_dump_after);
	fn = lookup_fn("rt6_fill_node");
	if (fn)
		hook_unwrap((void *)fn, (void *)rt6_fill_before,
			    (void *)rt6_fill_after);
	fn = lookup_fn("fib_nl_fill_rule");
	if (fn)
		hook_unwrap((void *)fn, (void *)fib_rule_before,
			    (void *)fib_rule_after);

	if (_remove_proc_entry) {
		_remove_proc_entry("vpnhide_targets", 0);
		_remove_proc_entry("vpnhide_debug", 0);
	}
	logki(MODNAME ": KPM unloaded\n");
	return 0;
}

KPM_INIT(vpnhide_kpm_init);
KPM_CTL0(vpnhide_kpm_ctl0);
KPM_EXIT(vpnhide_kpm_exit);
