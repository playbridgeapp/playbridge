#ifndef PLAYBRIDGE_CAST_CORE_H
#define PLAYBRIDGE_CAST_CORE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct DiscoveryScanner DiscoveryScanner;
typedef struct CastSession CastSession;

/* Increment when the stable C ABI or its JSON event contract changes. */
uint32_t pb_cast_core_abi_version(void);

/* Protocol mask: PlayBridge=1, DLNA=2, Roku=4, generic DIAL=8, Google Cast=16. */
DiscoveryScanner *pb_discovery_start(uint32_t protocol_mask, uint64_t timeout_ms);
char *pb_discovery_next_json(const DiscoveryScanner *scanner, uint64_t wait_ms);
void pb_discovery_cancel(const DiscoveryScanner *scanner);
void pb_discovery_free(const DiscoveryScanner *scanner);

/*
 * Starts one resource-bounded receiver session worker. target_json:
 * {"protocol":"dlna|roku|google_cast","addresses":["192.0.2.1"],
 *  "port":8060,"location":"http://192.0.2.1/device.xml"}
 */
CastSession *pb_session_start(const char *target_json, uint64_t timeout_ms);
/* Commands are JSON objects with command and request_id fields. */
bool pb_session_submit_json(const CastSession *session, const char *command_json);
char *pb_session_next_json(const CastSession *session, uint64_t wait_ms);
void pb_session_cancel(const CastSession *session);
void pb_session_free(const CastSession *session);
void pb_string_free(char *value);

#ifdef __cplusplus
}
#endif

#endif
