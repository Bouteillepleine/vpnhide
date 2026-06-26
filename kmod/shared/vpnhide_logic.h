/* SPDX-License-Identifier: MIT */
/*
 * vpnhide shared filtering logic — backend-agnostic, FREESTANDING.
 *
 * This header is included by BOTH native backends:
 *   - the kretprobe `.ko` (compiled with the real Linux kernel headers), and
 *   - the KernelPatch KPM (compiled `-nostdinc` against KernelPatch's
 *     own headers).
 *
 * Therefore it must depend on NOTHING: no libc, no kernel headers, no
 * `memmove`/`strlen`. It operates only on caller-provided buffers + a
 * caller-provided interface-name matcher. Keep it that way — the moment
 * this header `#include`s anything, one of the two backends stops
 * compiling.
 *
 * The algorithms here are intentionally byte-identical to the seq-file
 * compaction in `vpnhide_kmod.c` (`fib_route_ret` / `ipv6_route_ret`),
 * so adopting this header in the `.ko` later is a mechanical change.
 */
#ifndef VPNHIDE_SHARED_LOGIC_H
#define VPNHIDE_SHARED_LOGIC_H

#ifndef VPNHIDE_IFNAMSIZ
#define VPNHIDE_IFNAMSIZ 16
#endif

/* Matcher: returns non-zero if `ifname` (NUL-terminated, <= IFNAMSIZ) is a
 * VPN interface. Backends pass `vpnhide_iface_is_vpn` from the generated
 * `iface_lists.h` (single source of truth: data/interfaces.toml). */
typedef int (*vpnhide_match_fn)(const char *ifname);

/* Which whitespace-delimited field carries the interface name. */
enum vpnhide_iface_field {
	VPNHIDE_FIELD_FIRST = 0, /* /proc/net/route        — first tab field   */
	VPNHIDE_FIELD_LAST = 1,  /* /proc/net/ipv6_route    — last ws field      */
};

/*
 * Compact VPN lines out of a /proc/net seq-file buffer in place.
 *
 * `buf`   : seq_file buffer base.
 * `start` : byte offset where THIS show()-call's output began (everything
 *           before it belongs to earlier entries and must be preserved).
 * `count` : current end-of-content offset (seq_file->count).
 * `field` : where the iface name is on each line.
 * `match` : VPN-name predicate.
 *
 * Returns the new content length (caller writes it back to seq_file->count).
 *
 * Compaction only ever moves bytes DOWN (dst <= src), so the overlapping
 * copy is safe with a plain forward byte loop — no memmove dependency.
 */
static inline unsigned long
vpnhide_compact_seq_lines(char *buf, unsigned long start, unsigned long count,
			  enum vpnhide_iface_field field, vpnhide_match_fn match)
{
	unsigned long src = start;
	unsigned long dst = start;
	char ifname[VPNHIDE_IFNAMSIZ];

	if (!buf || count <= start || !match)
		return count;

	while (src < count) {
		/* Find end of the current line (past the '\n', or EOF). */
		unsigned long nl = src;
		unsigned long line_end;
		unsigned long line_len;
		int hide;

		while (nl < count && buf[nl] != '\n')
			nl++;
		line_end = (nl < count) ? nl + 1 : count;
		line_len = line_end - src;

		/* Extract the interface name for this line. */
		if (field == VPNHIDE_FIELD_FIRST) {
			unsigned long j = 0;
			while (j < (unsigned long)(VPNHIDE_IFNAMSIZ - 1) &&
			       src + j < line_end && buf[src + j] != '\t' &&
			       buf[src + j] != '\n') {
				ifname[j] = buf[src + j];
				j++;
			}
			ifname[j] = '\0';
		} else {
			/* Last whitespace-delimited field. Trim trailing
			 * newline / CR / spaces / tabs, then walk back to the
			 * preceding separator. */
			unsigned long fe = line_end;
			unsigned long fs;
			unsigned long j = 0;

			while (fe > src && (buf[fe - 1] == '\n' ||
					    buf[fe - 1] == '\r' ||
					    buf[fe - 1] == ' ' ||
					    buf[fe - 1] == '\t'))
				fe--;
			fs = fe;
			while (fs > src && buf[fs - 1] != ' ' &&
			       buf[fs - 1] != '\t')
				fs--;
			while (j < (unsigned long)(VPNHIDE_IFNAMSIZ - 1) &&
			       fs + j < fe) {
				ifname[j] = buf[fs + j];
				j++;
			}
			ifname[j] = '\0';
		}

		hide = (ifname[0] != '\0') && match(ifname);

		if (hide) {
			src = line_end; /* drop this line */
			continue;
		}

		if (dst != src) {
			unsigned long k;
			for (k = 0; k < line_len; k++)
				buf[dst + k] = buf[src + k]; /* dst<=src: safe */
		}
		dst += line_len;
		src = line_end;
	}

	return dst;
}

/*
 * Parse a newline-separated list of decimal UIDs (with `#` comments and
 * blank lines) from `buf` into `out` (capacity `max`). Returns the count.
 * Pure string work — shared by both backends' /proc/vpnhide_targets writer.
 */
static inline int
vpnhide_parse_target_uids(const char *buf, unsigned long len,
			  unsigned int *out, int max)
{
	unsigned long i = 0;
	int n = 0;

	while (i < len && n < max) {
		unsigned long uid = 0;
		int have_digit = 0;

		/* skip leading spaces/tabs */
		while (i < len && (buf[i] == ' ' || buf[i] == '\t'))
			i++;

		/* comment or empty line → skip to next '\n' */
		if (i < len && (buf[i] == '#' || buf[i] == '\n')) {
			while (i < len && buf[i] != '\n')
				i++;
			if (i < len)
				i++;
			continue;
		}

		while (i < len && buf[i] >= '0' && buf[i] <= '9') {
			uid = uid * 10u + (unsigned long)(buf[i] - '0');
			have_digit = 1;
			i++;
		}
		if (have_digit)
			out[n++] = (unsigned int)uid;

		/* advance to next line */
		while (i < len && buf[i] != '\n')
			i++;
		if (i < len)
			i++;
	}

	return n;
}

#endif /* VPNHIDE_SHARED_LOGIC_H */
