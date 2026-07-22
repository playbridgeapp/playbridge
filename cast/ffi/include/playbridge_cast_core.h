#ifndef PLAYBRIDGE_CAST_CORE_H
#define PLAYBRIDGE_CAST_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct DiscoveryScanner DiscoveryScanner;

/* Protocol mask: PlayBridge=1, DLNA=2, Roku=4, generic DIAL=8, Google Cast=16. */
DiscoveryScanner *pb_discovery_start(uint32_t protocol_mask, uint64_t timeout_ms);
char *pb_discovery_next_json(const DiscoveryScanner *scanner, uint64_t wait_ms);
void pb_discovery_cancel(const DiscoveryScanner *scanner);
void pb_discovery_free(const DiscoveryScanner *scanner);
void pb_string_free(char *value);

#ifdef __cplusplus
}
#endif

#endif
