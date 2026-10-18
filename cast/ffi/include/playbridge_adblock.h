/* SPDX-License-Identifier: MPL-2.0 */
#ifndef PLAYBRIDGE_ADBLOCK_H
#define PLAYBRIDGE_ADBLOCK_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define PB_ADBLOCK_ABI_VERSION 1
/* Adblock ABI 1 is separate from Cast ABI 2. Feature: adblock-engine.
 * Synchronous; build/convert/cache calls belong on a background actor.
 * Matching is serialized per handle; destroy is safe with in-flight calls.
 * No network I/O, logs, privileged resources, scriptlets or JS execution.
 *
 * All inputs: readable for len, 1..64 MiB; JSON UTF-8. Config {lists:[text,...]}
 * rejects unknown fields, max 128 lists / 32 MiB decoded list text.
 * create/deserialize return JSON {handle:nonzero uint64} or {error:code}.
 * deserialize's second buffer is the expected Config. Cache verifies engine
 * format, list SHA-256 hashes, preparser profile and payload checksum; mismatch
 * is an error and callers must rebuild. Serialized bytes are not a wire format.
 *
 * call commands (all fields required):
 * {op:"check",url,source_url,request_type,method} ->
 *   {matched:bool,exception:bool,important:bool,redirect:string|null}.
 * URLs max 16384 bytes each; HTTP method token max 32 bytes. Request types:
 * document/main_frame,sub_frame/subdocument,media,xmlhttprequest/fetch,script,
 * image/imageset,stylesheet,font,object/object_subrequest,beacon,ping,csp_report,
 * websocket,other. Popup is explicitly unsupported; caller keeps a popup subset.
 * {op:"cosmetic",url} -> {hide_selectors,procedural_actions,exceptions,
 *   injected_script,generichide}; {op:"hidden_class_id_selectors",classes,ids,
 *   exceptions} -> selector array. Stage 1 does not inject any of these results.
 *
 * conversion {lists:[text,...],chunk_limit:1..140000} -> {chunks:[rules,...],
 * source_lines,converted_filters,unique_rules,emitted_rules,disabled_by_badfilter,
 * failures:{static_reason:count}}. unique_rules is pre-replication record count,
 * not content deduplication. Every chunk ends with the full exception set.
 * Optional json_strings:true returns each chunk as canonical JSON text (stable
 * set-field ordering, lower client memory). Otherwise chunks contain rule arrays.
 * Conversion is a WebKit-compatible subset; unsupported semantics are counted.
 *
 * Errors are stable codes only: invalid_input/config/command, list_limit,
 * engine_limit, unknown_engine, registry_unavailable, engine_unavailable,
 * engine_panic, invalid_request, request_too_large, unsupported_request_type,
 * unsupported_request_method, invalid_cache, invalid_cache_checksum, invalid_serialized_engine,
 * cache_manifest_mismatch, invalid_chunk_limit, exceptions_exceed_chunk_capacity,
 * conversion_failed, encoding_failed. Never log input or returned raw JSON.
 *
 * Free every returned JSON string exactly once using string_free, including
 * errors. serialize returns NULL/zero length on invalid handle/failure; otherwise
 * free bytes exactly once using bytes_free and the exact returned length.
 * Borrowed input buffers remain caller-owned. Do not reuse destroyed handles.
 */
uint32_t pb_adblock_abi_version(void);
char *pb_adblock_create_json(const uint8_t *data, size_t len);
char *pb_adblock_deserialize(const uint8_t *data, size_t len, const uint8_t *config, size_t config_len);
char *pb_adblock_call_json(uint64_t handle, const uint8_t *data, size_t len);
uint8_t *pb_adblock_serialize(uint64_t handle, size_t *out_len);
char *pb_adblock_content_blocking_json(const uint8_t *data, size_t len);
void pb_adblock_destroy(uint64_t handle);
void pb_adblock_string_free(char *data);
void pb_adblock_bytes_free(uint8_t *data, size_t len);
#ifdef __cplusplus
}
#endif
#endif
