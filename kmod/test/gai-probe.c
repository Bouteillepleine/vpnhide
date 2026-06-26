/*
 * getifaddrs() probe for the KPM harness — the real native enumeration path
 * (bionic getifaddrs() = RTM_GETLINK + RTM_GETADDR over netlink), which the
 * `ip addr` shell vector can't isolate. Counts how many entries name "vpn0".
 *
 * Build (static, runs on the Alpine VM regardless of its libc):
 *   <ndk>/aarch64-linux-android35-clang -static -O2 -o gai gai-probe.c
 * Output: a single line `GAI_VPN0=<n>`.
 *
 * With only rtnl_fill_ifinfo hooked, the RTM_GETADDR entries for vpn0 still
 * arrive and bionic may reconstruct the iface — so this probe is what proves
 * inet_fill_ifaddr / inet6_fill_ifaddr actually close the address path.
 */
#include <ifaddrs.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
	struct ifaddrs *head, *cur;
	int n = 0;

	if (getifaddrs(&head) != 0) {
		printf("GAI_VPN0=-1\n");
		return 2;
	}
	for (cur = head; cur; cur = cur->ifa_next)
		if (cur->ifa_name && strcmp(cur->ifa_name, "vpn0") == 0)
			n++;
	freeifaddrs(head);
	printf("GAI_VPN0=%d\n", n);
	return 0;
}
