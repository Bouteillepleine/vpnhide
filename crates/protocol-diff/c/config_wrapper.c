#include "shared/vpnhide_logic.h"

/* Stable external symbol for the otherwise static-inline freestanding parser. */
int vpnhide_diff_parse_config(const unsigned char *input, unsigned long len,
                              struct vpnhide_target *targets, int capacity,
                              int *debug, unsigned int *default_mask)
{
    return vpnhide_parse_config((const char *)input, len, targets, capacity,
                                debug, default_mask);
}
