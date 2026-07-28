# PlayBridge Cast

This workspace contains the reusable receiver protocol core. It is intentionally
independent of Android, Apple, Flutter, and desktop UI lifecycles.

## Crates

- `core`: discovery plus a unified PlayBridge, DLNA, Roku, and Google Cast session API.
- `receiver`: secure PlayBridge WSS receiver runtime with typed command events.
- `ffi`: UniFFI bindings for Kotlin and Swift plus a stable C ABI for Dart consumers.
- `../cli`: cross-platform CLI built directly on `core`.
- `../stream-proxy-rust`: embeddable and standalone media proxy.
- `../browser-receiver-rust`: sender-hosted web receiver library and binary.

Discovery is caller-owned and time-boxed. A caller chooses one or more protocols,
reads events, and cancels or drops the scanner when its screen or command ends. The
core does not run a permanent background scan.

## Verify

```sh
cargo test --workspace --all-targets
cargo clippy --workspace --all-targets -- -D warnings
```

## Regenerate platform bindings

Install the UniFFI CLI at the same version as `ffi`, build the host
dynamic library, then generate both façades:

```sh
cargo build -p playbridge-cast-core-ffi
uniffi-bindgen generate --language kotlin \
  --out-dir cast/bindings/kotlin \
  target/debug/libplaybridge_cast_core_ffi.dylib
uniffi-bindgen generate --language swift \
  --out-dir cast/bindings/swift \
  target/debug/libplaybridge_cast_core_ffi.dylib
```

The generated Kotlin binding requires JNA. Do not add it to an Android app until
the matching ABI-specific `.so` files are packaged. The Swift output must be paired
with the matching static or dynamic library for each Apple target.

## C ABI

`cast/ffi/include/playbridge_cast_core.h` is the stable surface intended for
Dart FFI and other languages that do not need generated bindings. Discovery and
session events are UTF-8 JSON. Strings returned by either `next_json` function
must be released with `pb_string_free`; handles must be cancelled and released.
Consumers must check `pb_cast_core_abi_version` before resolving the rest of the
session API.

Desktop builds enable the optional `sender-services` feature (reqwest upstream).
Android phone builds use `sender-services-android` (JNI upstream callbacks via
`pb_proxy_upstream_*`). Both expose the same `pb_sender_services_*` lifecycle
ABI for the embedded stream proxy and on-demand browser-receiver host. Keeping
this lifecycle separate allows other builds to ship the protocol core without
pulling in either server.

The `pb_receiver_runtime_*` ABI exposes the shared native PlayBridge receiver.
It accepts a caller-owned TLS identity and authorized token digests, then emits
pairing, connection, and authenticated command events. Playback snapshots and
other receiver responses are submitted by the host application, keeping Flutter
and mobile player lifecycles outside Rust.

A session target selects exactly one protocol and retains all known addresses:

```json
{"protocol":"roku","addresses":["192.168.1.20"],"port":8060}
```

DLNA targets use `location`; Google Cast defaults to port 8009. Every queued
command has a scalar `request_id`, which is echoed by its `operation`, `status`,
or `error` event. Supported commands are `load`, `play`, `pause`, `stop`, `seek`,
`relative_seek`, `status`, and `disconnect`. Session command/event queues are
bounded and the worker exists only for the lifetime of an active connection.

Protocol mask values are stable:

- PlayBridge: `1`
- DLNA: `2`
- Roku: `4`
- DIAL: `8`
- Google Cast: `16`

The reusable Dart wrapper lives in `../packages/playbridge_cast_core_dart`. Build
the native library for the current desktop OS with `cast/build-desktop.sh`. The
macOS build is universal; Linux and Windows builds are produced by their native
Desktop CI jobs and packaged by Flutter's platform build files.

## Desktop CLI

`cast/build-desktop.sh` also writes `playbridge` (`playbridge.exe` on
Windows) beside the FFI library. It can scan one protocol, several selected
protocols, or the normal automatic set without starting a background service:

```sh
playbridge discover --protocol playbridge --timeout 5
playbridge discover --protocol dlna,roku --json
playbridge discover --protocol all --json-lines
```

`--json` emits one deduplicated report after the scan. `--json-lines` streams
stable started, found, updated, error, and finished event objects for scripts.

## Android packaging status

The phone uses a thin JNI adapter rather than JNA. Build and copy the two supported
Android libraries with:

```sh
cd mobile/android
./gradlew :app:buildRustCastCore
```

The phone uses Rust as its primary PlayBridge, DLNA, and Roku discovery engine;
Android retains the platform lifecycle, multicast lock, and UI integration.
