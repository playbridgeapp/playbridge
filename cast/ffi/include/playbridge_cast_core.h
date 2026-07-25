#ifndef PLAYBRIDGE_CAST_CORE_H
#define PLAYBRIDGE_CAST_CORE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct DiscoveryScanner DiscoveryScanner;
typedef struct CastSession CastSession;
typedef struct SenderServices SenderServices;
typedef struct ReceiverRuntime ReceiverRuntime;

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

/*
 * Optional Desktop sender-services ABI. The native library must be built with
 * the `sender-services` feature. It owns one embedded stream proxy and an
 * on-demand browser-receiver host. Commands and events are UTF-8 JSON.
 */
uint32_t pb_sender_services_abi_version(void);
SenderServices *pb_sender_services_start(void);
bool pb_sender_services_submit_json(const SenderServices *services,
                                    const char *command_json);
char *pb_sender_services_next_json(const SenderServices *services,
                                   uint64_t wait_ms);
void pb_sender_services_cancel(const SenderServices *services);
void pb_sender_services_free(const SenderServices *services);

/* Secure PlayBridge receiver runtime. Commands and events are UTF-8 JSON. */
uint32_t pb_receiver_runtime_abi_version(void);
ReceiverRuntime *pb_receiver_runtime_start(const char *config_json);
bool pb_receiver_runtime_submit_json(const ReceiverRuntime *runtime,
                                     const char *command_json);
char *pb_receiver_runtime_next_json(const ReceiverRuntime *runtime,
                                    uint64_t wait_ms);
void pb_receiver_runtime_cancel(const ReceiverRuntime *runtime);
void pb_receiver_runtime_free(const ReceiverRuntime *runtime);

void pb_string_free(char *value);

#ifdef __cplusplus
}
#endif

#endif
