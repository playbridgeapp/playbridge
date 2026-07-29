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
 * Optional sender-services ABI. Build with `sender-services` (Desktop: reqwest
 * upstream) or `sender-services-android` (phone: JNI upstream callbacks).
 * Owns one embedded stream proxy and an on-demand browser-receiver host.
 * Commands and events are UTF-8 JSON.
 */
uint32_t pb_sender_services_abi_version(void);
SenderServices *pb_sender_services_start(void);
bool pb_sender_services_submit_json(const SenderServices *services,
                                    const char *command_json);
char *pb_sender_services_next_json(const SenderServices *services,
                                   uint64_t wait_ms);
void pb_sender_services_cancel(const SenderServices *services);
void pb_sender_services_free(const SenderServices *services);

/*
 * Android / host origin-fetch callbacks for stream-proxy-rust (upstream-jni).
 * Linked when the native library is built with stream-proxy-rust/upstream-jni
 * (e.g. cast/ffi feature sender-services-android).
 *
 * open: returns handle > 0 on success; on failure returns 0 and may set *out_error
 *       (free with free_string). On success writes HTTP status and a JSON object
 *       of response headers (content-type, content-length, content-range,
 *       accept-ranges) into *out_response_headers_json (free with free_string).
 * read: >0 bytes, 0 EOF, <0 error (optional *out_error).
 * close: release handle (idempotent preferred).
 * free_string: free host-allocated C strings from open/read.
 */
typedef int64_t (*pb_proxy_upstream_open_fn)(const char *url,
                                             const char *request_headers_json,
                                             int32_t *out_status,
                                             char **out_response_headers_json,
                                             char **out_error);
typedef int32_t (*pb_proxy_upstream_read_fn)(int64_t handle, uint8_t *buf,
                                             int32_t len, char **out_error);
typedef void (*pb_proxy_upstream_close_fn)(int64_t handle);
typedef void (*pb_proxy_upstream_free_string_fn)(char *ptr);

typedef struct PbUpstreamCallbacks {
  pb_proxy_upstream_open_fn open;
  pb_proxy_upstream_read_fn read;
  pb_proxy_upstream_close_fn close;
  pb_proxy_upstream_free_string_fn free_string;
} PbUpstreamCallbacks;

uint32_t pb_proxy_upstream_abi_version(void);
void pb_proxy_upstream_set_callbacks(PbUpstreamCallbacks callbacks);
void pb_proxy_upstream_clear_callbacks(void);
int32_t pb_proxy_upstream_callbacks_registered(void);

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
