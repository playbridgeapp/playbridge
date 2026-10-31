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
typedef struct AtvRemote AtvRemote;

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
 *  "port":8060,"location":"http://192.0.2.1/device.xml",
 *  "application_id":"30FDC6BC","launch_policy":"reuse_or_launch",
 *  "network_handle":467262165005}
 * network_handle is the optional Android Network#getNetworkHandle() used to
 * bind local Cast sockets around a VPN without changing the process route.
 *
 * For Google Cast, the connected event is emitted only after the configured
 * receiver application has launched (or been joined), its transport channel
 * is connected, and its media namespace answers GET_STATUS. LOAD is a later
 * command and may be repeated while the receiver remains ready.
 *
 * LOAD additionally accepts optional content_type, art_url, start_seconds,
 * and stream_type fields. STOP stops media but leaves the receiver application
 * ready; END_RECEIVER explicitly terminates the receiver application.
 */
CastSession *pb_session_start(const char *target_json, uint64_t timeout_ms);
/* Commands are JSON objects with command and request_id fields. */
bool pb_session_submit_json(const CastSession *session, const char *command_json);
char *pb_session_next_json(const CastSession *session, uint64_t wait_ms);
void pb_session_cancel(const CastSession *session);
void pb_session_free(const CastSession *session);

/*
 * Android TV Remote v2 ABI v1 (independent of Cast/session/sender-services ABI).
 * One pairing and one control session per handle. No network activity at start.
 * All commands carry "command" and a nonempty string (<=128 bytes), number or
 * bool "request_id". IDs are caller correlation tokens; never put secrets in IDs.
 *
 * Commands (additional fields):
 * discover: timeout_ms (0 skips mDNS; capped at 60000).
 * generate_identity: client_name (complete model label + UUID-derived suffix); optional cert_pem +
 *   key_pem from the stored install identity, for explicit pairing only. Reuses a
 *   modern identity or replaces a legacy CN/serial. The identity event additionally
 *   returns client_name (persisted certificate CN) and replaces_legacy. Commit only
 *   after verified pairing; never log these commands or identity events.
 * start_pairing: host, port? (6467), cert_pem, key_pem, client_name.
 * finish_pairing: code. A failed finish consumes pairing; restart to retry.
 * cancel_pairing: aborts a pending or started pairing only.
 * connect: host, port? (6466), cert_pem, key_pem, pin_sha256_hex (64 hex chars).
 * key: exactly one of code (Android enum) or name; direction defaults to
 *      "short", also "start_long" and "end_long".
 * volume: action "up"|"down"|"mute". text: text.
 * launch_app: uri_or_package (packages become market://launch?id=...).
 * power: short POWER key. disconnect: abort pending/active control only.
 * A new start_pairing/connect replaces the previous session of that kind.
 *
 * Events: devices {devices}, identity {cert_pem,key_pem,fingerprint},
 * pairing_started {server_name,fingerprint,mac}, paired
 * {server_cert_der_b64,fingerprint,mac,server_name}, connected, state {state},
 * operation {operation,ok:true}, error {kind,message}, closed {kind}.
 * Each carries request_id; state/closed use the establishing connect ID.
 * Fingerprints are lowercase SHA-256 of full certificate DER.
 * State: is_on, volume_level/max (nullable), volume_muted, volume_revision,
 * current_app/model/vendor (nullable). IME text, label and counters are excluded.
 * State uses latest-value watch semantics; rapid pushes may coalesce.
 * Error/closed kind: unreachable, tls_handshake, not_trusted, pin_mismatch,
 * bad_code, protocol, timeout, closed. Errors never include command values.
 * Successful key/volume/text/app/power operation means the frame was written,
 * not that the TV executed it. Volume observations arrive through state events.
 *
 * submit is nonblocking: false for malformed JSON/ID, >1 MiB command, full
 * command queue (32), or stopped worker. Invalid input also queues a protocol
 * error if capacity permits (null request_id when unrecoverable).
 * Events have capacity 64 with cancellable backpressure, as pb_session_*.
 * Additional identity/discovery jobs are bounded to 32. Credentials are only
 * returned by explicit identity/paired payloads; consumers must store them
 * privately and must not log these payloads or submitted credential commands.
 *
 * next_json returns null on timeout/end (wait_ms=0 polls). Caller releases
 * every returned string via pb_string_free. Calls may run on different threads;
 * next_json consumers serialize. cancel/free never join a worker or wait on TV.
 * cancel is terminal; later submits fail. Cancellation's closed event is best
 * effort if full. Free exactly once, after all concurrent calls have ended.
 * Null handles are accepted. A freed handle must never be reused.
 */
// Compile-time counterpart of pb_atv_abi_version(); independent of Cast ABI 2.
#define PB_ATV_REMOTE_ABI_VERSION 1
uint32_t pb_atv_abi_version(void);
AtvRemote *pb_atv_start(void);
bool pb_atv_submit_json(const AtvRemote *handle, const char *command_json);
char *pb_atv_next_json(const AtvRemote *handle, uint64_t wait_ms);
void pb_atv_cancel(const AtvRemote *handle);
void pb_atv_free(const AtvRemote *handle);

/*
 * Optional sender-services ABI. Build with `sender-services` (Desktop: reqwest
 * upstream), `sender-services-android` (HttpURLConnection callbacks), or
 * `sender-services-apple` (URLSession callbacks). `receiver-proxy-apple` (Apple TV)
 * is the same lifecycle with the proxy only: browser_* commands are rejected.
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
 * (e.g. cast/ffi features sender-services-android or sender-services-apple).
 *
 * open: returns handle > 0 on success; on failure returns 0 and may set *out_error
 *       (free with free_string). On success writes HTTP status and a JSON object
 *       of response headers (content-type, content-length, content-range,
 *       accept-ranges, location for 3xx) into *out_response_headers_json
 *       (free with free_string). Hosts may return redirects without following;
 *       Rust validates supported redirect hops and scopes their headers.
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

/* Additive policy-bound API. Native HTTP/TLS MUST use the supplied authenticated
 * loopback HTTP proxy (including CONNECT for HTTPS), not re-resolve the URL.
 * proxy_json: {host:"127.0.0.1",port:N,username,password}; NULL only for trusted calls.
 * Strings/handles retain the same ownership rules as ABI v1. No v1 layout change. */
typedef int64_t (*PbUpstreamCheckedOpenFn)(const char *url, const char *request_headers_json,
    const char *proxy_json, int32_t *out_status, char **out_response_headers_json, char **out_error);
typedef struct PbUpstreamCheckedCallbacks {
    PbUpstreamCheckedOpenFn open;
    pb_proxy_upstream_read_fn read;
    pb_proxy_upstream_close_fn close;
    pb_proxy_upstream_free_string_fn free_string;
} PbUpstreamCheckedCallbacks;
void pb_proxy_upstream_set_checked_callbacks(PbUpstreamCheckedCallbacks callbacks);
int32_t pb_proxy_upstream_checked_callbacks_registered(void);
void pb_proxy_upstream_clear_callbacks(void);
int32_t pb_proxy_upstream_callbacks_registered(void);

/* Secure PlayBridge receiver runtime, ABI 1. Commands and events are UTF-8 JSON.
 * RuntimeConfig accepts optional `capabilities`, a native ReceiverCapabilities
 * v1 JSON object (protocol/docs/RECEIVER_CAPABILITIES.md). Omit it or use null
 * for exact legacy auth/protected-credential envelopes. Validated static JSON
 * is published identically on successful auth and inside encrypted credentials;
 * it is never added to the plaintext pairing_approved envelope or event logs.
 * Limits: 64 KiB serialized UTF-8, 16 surfaces/engines per map, 64 unique IDs
 * per set, 1-64 Unicode characters per ID. Invalid/unsupported descriptors
 * cause start to return NULL; unknown top-level config fields remain tolerated.
 * This additive optional JSON field does not change ABI 1 functions, ownership,
 * commands or events. Dynamic descriptor updates are not supported. */
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
