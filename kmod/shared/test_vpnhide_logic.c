/*
 * Host unit test for shared/vpnhide_logic.h (the backend-agnostic filtering
 * logic shared by the .ko and the KPM).
 *
 * Build: gcc -O2 -Wall -Wextra -Werror -I.. -o test_vpnhide_logic test_vpnhide_logic.c
 * Run:   ./test_vpnhide_logic   (exit 0 on success)
 */
#include <stdio.h>
#include <string.h>

#include "generated/iface_lists.h"
#include "shared/vpnhide_logic.h"

static int failures;

/* int-returning adapter for the bool matcher (function-pointer-type safe). */
static int match_vpn(const char *name)
{
	return vpnhide_iface_is_vpn(name) ? 1 : 0;
}

static void expect_str(const char *what, const char *got, const char *want)
{
	if (strcmp(got, want) != 0) {
		fprintf(stderr, "FAIL %s:\n  got : %s\n  want: %s\n", what, got, want);
		failures++;
	}
}

static void test_route_first_field(void)
{
	/* /proc/net/route: iface is the first tab-separated field. */
	char buf[512] =
		"Iface\tDestination\tGateway\n"
		"wlan0\t00000000\t0101A8C0\n"
		"tun0\t00000000\t010010AC\n"
		"rmnet0\tFEFFFFFF\t00000000\n"
		"wg0\t00000000\t00000000\n";
	unsigned long count = strlen(buf);
	/* keep the header line (start=0) — matcher rejects "Iface". */
	unsigned long n = vpnhide_compact_seq_lines(buf, 0, count,
						    VPNHIDE_FIELD_FIRST, match_vpn);
	buf[n] = '\0';
	expect_str("route: tun0+wg0 removed",
		   buf,
		   "Iface\tDestination\tGateway\n"
		   "wlan0\t00000000\t0101A8C0\n"
		   "rmnet0\tFEFFFFFF\t00000000\n");
}

static void test_ipv6_route_last_field(void)
{
	/* /proc/net/ipv6_route: iface is the last whitespace field. */
	char buf[512] =
		"00000000000000000000000000000000 00 ... wlan0\n"
		"fe800000000000000000000000000000 40 ... tun0\n"
		"00000000000000000000000000000000 00 ... rmnet_data0\n";
	unsigned long count = strlen(buf);
	unsigned long n = vpnhide_compact_seq_lines(buf, 0, count,
						    VPNHIDE_FIELD_LAST, match_vpn);
	buf[n] = '\0';
	expect_str("ipv6_route: tun0 removed",
		   buf,
		   "00000000000000000000000000000000 00 ... wlan0\n"
		   "00000000000000000000000000000000 00 ... rmnet_data0\n");
}

static void test_start_offset_preserved(void)
{
	/* Bytes before `start` belong to earlier show() calls — never touched,
	 * even if they name a VPN iface. */
	char buf[256] = "tun9\tearlier-entry\nwlan0\tkeep\ntun0\tdrop\n";
	unsigned long start = strlen("tun9\tearlier-entry\n");
	unsigned long count = strlen(buf);
	unsigned long n = vpnhide_compact_seq_lines(buf, start, count,
						    VPNHIDE_FIELD_FIRST, match_vpn);
	buf[n] = '\0';
	expect_str("start offset preserved",
		   buf, "tun9\tearlier-entry\nwlan0\tkeep\n");
}

static void test_parse_uids(void)
{
	const char *in = "10010\n  10020 \n# a comment\n\n10030\nbad\n10040";
	unsigned int out[8];
	int n = vpnhide_parse_target_uids(in, strlen(in), out, 8);
	if (n != 4 || out[0] != 10010 || out[1] != 10020 || out[2] != 10030 ||
	    out[3] != 10040) {
		fprintf(stderr, "FAIL parse_uids: n=%d [%u %u %u %u]\n", n,
			out[0], out[1], out[2], out[3]);
		failures++;
	}
}

int main(void)
{
	test_route_first_field();
	test_ipv6_route_last_field();
	test_start_offset_preserved();
	test_parse_uids();

	if (failures) {
		fprintf(stderr, "%d test(s) failed\n", failures);
		return 1;
	}
	printf("all shared-logic tests passed\n");
	return 0;
}
