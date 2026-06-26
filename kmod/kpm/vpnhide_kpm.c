// SPDX-License-Identifier: MIT
/*
 * vpnhide — KernelPatch Module (KPM) backend.  *** SKELETON / WIP ***
 *
 * A third native backend alongside the kretprobe `.ko`, for kernels the
 * `.ko` can't serve: non-GKI / proprietary kernels with no published
 * headers or Module.symvers (e.g. kernel 4.14 — issue #33; HyperOS 5.4).
 * Loaded by the KernelPatch runtime (target: KPatch-Next on KernelSU-Next),
 * NOT insmod — so it also works where module signing blocks a `.ko`.
 *
 * STATUS: compiles against the KernelPatch header tree into a valid .kpm
 * (`make kpm KP_DIR=/path/to/KernelPatch`; verified against bmax121/KernelPatch).
 * NOT yet validated on a kernel: the running-kver source is a stub (so it
 * refuses to install), several hooks are still TODO, and every per-kver offset
 * in kver_offsets.h must pass the QEMU KPM harness before anything ships — a
 * wrong offset is a panic/bootloop, not a soft failure.
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
#include <kpmodule.h>
#include <hook.h>
#include <kputils.h>
#include <log.h>
#include <symbol.h>
#include <kallsyms.h>
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

/* KernelPatch runtime helpers (provided by the headers above). Declared
 * here for the skeleton; confirm exact spelling against the KernelPatch
 * tree when wiring the real build.  [TODO: verify current_uid / kver source] */
extern unsigned long (*kallsyms_lookup_name)(const char *name);
extern uid_t current_uid(void);

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

/*
 * HOOK COVERAGE — to reach parity with vpnhide_kmod.c (the .ko). Each line
 * below is a hook_wrap target; PoC ones are wired above, the rest are the
 * porting backlog. Mirror the .ko's logic; reuse shared/vpnhide_logic.h.
 *
 *   fib_route_seq_show     /proc/net/route        PoC (above)
 *   rtnl_fill_ifinfo       RTM_NEWLINK            PoC (above)
 *   ipv6_route_seq_show    /proc/net/ipv6_route   TODO (FIELD_LAST compactor)
 *   inet_fill_ifaddr       RTM_GETADDR v4         TODO (skb_trim; ifa->ifa_dev->dev)
 *   inet6_fill_ifaddr      RTM_GETADDR v6         TODO (skb_trim; ifa->idev->dev)
 *   dev_ioctl              SIOCGIF* by name       TODO (rewrite ret -> -ENODEV)
 *   sock_ioctl             SIOCGIFCONF            TODO (ifconf compaction via copy_*_user)
 *   fib_dump_info          RTM_GETROUTE v4 dump   TODO (#86; fib_info nexthop dev)
 *   rt6_fill_node          RTM_GETROUTE v6        TODO
 *   fib_nl_fill_rule       RTM_GETRULE            TODO
 *   ( rt_fill_info — intentionally NOT hooked; unstable ABI )
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

	_skb_trim = (void *)kallsyms_lookup_name("__skb_trim");
	if (!_skb_trim)
		_skb_trim = (void *)kallsyms_lookup_name("skb_trim");

	/* Hooks need these; proc is best-effort. */
	return _skb_trim ? 0 : -1;
}

/* TODO: source the running kernel version from KernelPatch rather than this
 * placeholder (e.g. a `kver` global, or parse `linux_banner`/init_uts_ns).
 * Returning 0 here makes vpnhide_select_offsets() bail — intentional until
 * wired, so the skeleton can't install hooks with a wrong table. */
static unsigned int running_kver(void)
{
	return 0; /* TODO */
}

static long vpnhide_kpm_init(const char *args, const char *event,
			     void *__user reserved)
{
	unsigned long fn;

	logki(MODNAME ": KPM init (event=%s)\n", event ? event : "");

	off = vpnhide_select_offsets(running_kver());
	if (!off) {
		logki(MODNAME ": unsupported kernel version — refusing to install\n");
		return -1; /* never guess offsets */
	}
	if (resolve_symbols() != 0) {
		logki(MODNAME ": symbol resolution failed\n");
		return -1;
	}

	/* TODO: create /proc/vpnhide_targets + /proc/vpnhide_debug using the
	 * proc-ABI selected by off->proc_uses_proc_ops. */

	/* Install the PoC hooks. hook_wrap(func, argno, before, after, udata). */
	fn = kallsyms_lookup_name("fib_route_seq_show");
	if (fn)
		hook_wrap((void *)fn, 2, (void *)fib_route_before,
			  (void *)fib_route_after, 0);

	fn = kallsyms_lookup_name("rtnl_fill_ifinfo");
	if (fn)
		hook_wrap((void *)fn, 12, (void *)rtnl_fill_before,
			  (void *)rtnl_fill_after, 0);

	logki(MODNAME ": KPM hooks installed\n");
	return 0;
}

static long vpnhide_kpm_ctl0(const char *args, char *__user out_msg, int outlen)
{
	/* TODO: optional userspace control channel (sc_kpm_control). For now
	 * targets come through /proc, matching the .ko's control plane. */
	(void)args;
	(void)out_msg;
	(void)outlen;
	return 0;
}

static long vpnhide_kpm_exit(void *__user reserved)
{
	unsigned long fn;

	fn = kallsyms_lookup_name("fib_route_seq_show");
	if (fn)
		hook_unwrap((void *)fn, (void *)fib_route_before,
			    (void *)fib_route_after);
	fn = kallsyms_lookup_name("rtnl_fill_ifinfo");
	if (fn)
		hook_unwrap((void *)fn, (void *)rtnl_fill_before,
			    (void *)rtnl_fill_after);

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
