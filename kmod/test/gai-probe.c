/*
 * getifaddrs() probe for the kmod/KPM harnesses — the real native enumeration path
 * (bionic getifaddrs() = RTM_GETLINK + RTM_GETADDR over netlink), which the
 * `ip addr` shell vector can't isolate. Counts how many entries name "vpn0"
 * and how many non-vpn entries remain visible.
 *
 * Build (static, runs on the Alpine VM regardless of its libc):
 *   <ndk>/aarch64-linux-android35-clang -static -O2 -o gai gai-probe.c
 * Output:
 *   GAI_VPN0=<n>
 *   GAI_OTHER=<n>
 *
 * With only rtnl_fill_ifinfo hooked, the RTM_GETADDR entries for vpn0 still
 * arrive and bionic may reconstruct the iface — so this probe is what proves
 * inet_fill_ifaddr / inet6_fill_ifaddr actually close the address path.
 */
#include <ifaddrs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void)
{
	struct ifaddrs *head, *cur;
	int other = 0, vpn = 0;

	if (getifaddrs(&head) != 0) {
		printf("GAI_VPN0=-1\n");
		return 2;
	}
	for (cur = head; cur; cur = cur->ifa_next)
		if (cur->ifa_name && strcmp(cur->ifa_name, "vpn0") == 0)
			vpn++;
		else if (cur->ifa_name)
			other++;
	freeifaddrs(head);
	printf("GAI_VPN0=%d\n", vpn);
	printf("GAI_OTHER=%d\n", other);
	return 0;
}
