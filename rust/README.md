# PlayBridge Cast Core

This workspace contains the reusable receiver protocol core. It is intentionally
independent of Android, Apple, Flutter, and desktop UI lifecycles.

## Crates

- `cast-core`: discovery, DLNA, Roku/DIAL, PlayBridge protocol and secure WebSocket logic.
- `cast-core-ffi`: UniFFI bindings for Kotlin and Swift plus a stable C ABI for Dart and CLI consumers.
- `cast-cli`: cross-platform bounded discovery CLI built directly on `cast-core`.

Discovery is caller-owned and time-boxed. A caller chooses one or more protocols,
reads events, and cancels or drops the scanner when its screen or command ends. The
core does not run a permanent background scan.

## Verify

```sh
cargo test --workspace --all-targets
cargo clippy --workspace --all-targets -- -D warnings
```

## Regenerate platform bindings

Install the UniFFI CLI at the same version as `cast-core-ffi`, build the host
dynamic library, then generate both façades:

```sh
cargo build -p playbridge-cast-core-ffi
uniffi-bindgen generate --language kotlin \
  --out-dir bindings/kotlin \
  target/debug/libplaybridge_cast_core_ffi.dylib
uniffi-bindgen generate --language swift \
  --out-dir bindings/swift \
  target/debug/libplaybridge_cast_core_ffi.dylib
```

The generated Kotlin binding requires JNA. Do not add it to an Android app until
the matching ABI-specific `.so` files are packaged. The Swift output must be paired
with the matching static or dynamic library for each Apple target.

## C ABI

`cast-core-ffi/include/playbridge_cast_core.h` is the stable surface intended for
Dart FFI and other languages that do not need generated bindings. Discovery events
are UTF-8 JSON. Strings returned by `pb_discovery_next_json` must be released with
`pb_string_free`; scanner handles must be cancelled and released.

Protocol mask values are stable:

- PlayBridge: `1`
- DLNA: `2`
- Roku: `4`
- DIAL: `8`

The reusable Dart wrapper lives in `../packages/playbridge_cast_core_dart`. Build
the native library for the current desktop OS with `./build-desktop.sh`. The
macOS build is universal; Linux and Windows builds are produced by their native
Desktop CI jobs and packaged by Flutter's platform build files.

## Desktop CLI

`build-desktop.sh` also writes `playbridge-cast` (`playbridge-cast.exe` on
Windows) beside the FFI library. It can scan one protocol, several selected
protocols, or the normal automatic set without starting a background service:

```sh
playbridge-cast discover --protocol playbridge --timeout 5
playbridge-cast discover --protocol dlna,roku --json
playbridge-cast discover --protocol all --json-lines
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

The native engine is gated by **TV settings → Experimental → Compare Rust
discovery** and defaults to off. When enabled it runs only during an existing
15-second device-picker scan. Kotlin discovery remains authoritative; the shadow
worker logs only aggregate protocol counts and never receiver names, addresses, or
identifiers.
