---
name: playbridge-rust-core
description: Work on the PlayBridge Rust casting core, reusable receiver runtime, sender-hosted browser receiver, FFI bindings, and CLI. Use for discovery and protocol sessions, receiver TLS/WSS and SAS pairing, authentication, UniFFI/C/JNI/Dart bindings, command-line sender or receiver behavior, or changes under cast/core/, cast/receiver/, cast/ffi/, browser-receiver-rust/, packages/playbridge_cast_core_dart/, and cli/.
---

# PlayBridge Rust Core

## Establish ownership

- Treat `cast/core/`, `cast/receiver/`, `cast/ffi/`, `browser-receiver-rust/`, and `cli/` as the portable Rust casting layer.
- Keep discovery (mDNS, UPnP), SAS pairing, security (X25519, HKDF, AES-GCM), and stream metadata logic inside `cast/core/`.
- Keep TLS/WSS serving, pairing orchestration, authentication, connection limits, and typed receiver command delivery inside `cast/receiver/`. Keep playback, UI, discovery lifecycle, and platform services in the consumer.
- Keep the sender-hosted web receiver and its short-code approval/control protocol in `browser-receiver-rust/`; Desktop and CLI embed its host API, and its generated web bundle is checked in.
- Keep UniFFI, C, and JNI exported bindings in `cast/ffi/`. Treat Cast Core sessions, sender services, proxy upstream callbacks, and receiver runtime as distinct stable ABI surfaces with separate version counters where provided.
- Keep the Dart C-ABI façade in `packages/playbridge_cast_core_dart/`.
- Keep command-line behavior and the external-mpv playback adapter in `cli/`; do not duplicate receiver networking there.
- Coordinate FFI or protocol model changes with platform specialist owners (`playbridge-android`, `playbridge-apple`, `playbridge-desktop-proxy`).

## Preserve the CLI dashboard model

- Route every human-oriented CLI workflow through the full-screen dashboard. This includes bare invocation, media paths, `send`, `cast`, human-readable `discover`, `browser`, `receiver`, and preferred-device management.
- Keep plain terminal output limited to explicit machine or diagnostic surfaces such as help/version, JSON discovery, configuration diagnostics, and low-level Google Cast diagnostics.
- The dashboard owns terminal setup, restoration, input, overlays, and notices. Background cast, browser-host, discovery, and receiver tasks must report through typed channels and must not print directly to stdout or stderr while the dashboard is active.
- Scope asynchronous cast and pairing events to their session generation. On successful connection, cancellation, replacement, or teardown, clear stale pairing state and overlays before navigating or accepting more input.
- Preserve independent navigation and content focus. A retained cursor may identify the next content action, but only the focused pane should render an active selection.
- Keep update checks and downloads behind typed dashboard events. Resolve releases through `playbridge.app`, verify the advertised SHA-256 before replacement, restore the terminal before handing off to a relaunch helper, and block self-update while cast or receiver work is active.

## Work safely

1. Follow the root `AGENTS.md` rules and preserve unrelated working-tree edits.
2. Keep sensitive cryptographic keys, SAS session secrets, authenticated URLs, and the raw token in `ReceiverEvent::Paired` out of logs.
3. Preserve persisted TLS identities and SPKI pins. Accept legacy raw tokens and `sha256:<hex>` records during credential migrations.
4. Keep receiver connection, message, and outbound-queue limits bounded. Credential removal must terminate sessions authenticated by the removed token.
5. Ensure FFI bindings remain thread-safe and panic-safe. Bump the matching Cast Core, sender-services, proxy-upstream, or receiver-runtime ABI version for incompatible C/JSON changes.
6. Maintain compatibility with AGPL-3.0 licensing constraints across all core components.
7. Preserve Google Cast readiness, `STOP` versus `END_RECEIVER`, stale-session teardown, and Android `network_handle` behavior across consumer adapters.

## Verify

From the repository root:

```bash
cargo test -p playbridge-cast-core -p playbridge-cast-receiver \
  -p playbridge-cast-core-ffi -p playbridge-browser-receiver \
  -p playbridge-cast-cli \
  --features playbridge-cast-core-ffi/sender-services --locked
cargo clippy -p playbridge-cast-core -p playbridge-cast-receiver \
  -p playbridge-cast-core-ffi -p playbridge-browser-receiver \
  -p playbridge-cast-cli --all-targets \
  --features playbridge-cast-core-ffi/sender-services --locked -- -D warnings
```

When a native ABI or platform-facing Rust implementation changes, rebuild each
affected platform artifact from the repository root. These scripts target
different consumers and do not replace one another:

```bash
sh cast/build-desktop.sh
sh cast/build-android.sh
sh cast/build-apple.sh
cd desktop
flutter test test/rust_receiver_runtime_test.dart
```

Only run the platform scripts relevant to the change. `build-apple.sh` refuses
to overwrite an existing XCFramework; remove or relocate that generated output
deliberately before rebuilding it.

Run specific examples when testing core discovery or pairing workflows:

```bash
cargo run --example discover -p playbridge-cast-core
cargo run --example pair -p playbridge-cast-core
```
