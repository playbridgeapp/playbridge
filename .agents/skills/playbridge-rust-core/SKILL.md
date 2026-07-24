---
name: playbridge-rust-core
description: Work on the PlayBridge Rust casting core, reusable receiver runtime, FFI bindings, and CLI. Use for discovery and protocol sessions, receiver TLS/WSS and SAS pairing, authentication, UniFFI/C/JNI/Dart bindings, command-line sender or receiver behavior, or changes under cast/core/, cast/receiver/, cast/ffi/, packages/playbridge_cast_core_dart/, and cli/.
---

# PlayBridge Rust Core

## Establish ownership

- Treat `cast/core/`, `cast/receiver/`, `cast/ffi/`, and `cli/` as the portable Rust casting layer.
- Keep discovery (mDNS, UPnP), SAS pairing, security (X25519, HKDF, AES-GCM), and stream metadata logic inside `cast/core/`.
- Keep TLS/WSS serving, pairing orchestration, authentication, connection limits, and typed receiver command delivery inside `cast/receiver/`. Keep playback, UI, discovery lifecycle, and platform services in the consumer.
- Keep UniFFI, C, and JNI exported bindings in `cast/ffi/`. Ensure language binding definitions stay compatible across platform targets.
- Keep the Dart C-ABI façade in `packages/playbridge_cast_core_dart/`.
- Keep command-line behavior and the external-mpv playback adapter in `cli/`; do not duplicate receiver networking there.
- Coordinate FFI or protocol model changes with platform specialist owners (`playbridge-android`, `playbridge-apple`, `playbridge-desktop-proxy`).

## Work safely

1. Follow the root `AGENTS.md` rules and preserve unrelated working-tree edits.
2. Keep sensitive cryptographic keys, SAS session secrets, authenticated URLs, and the raw token in `ReceiverEvent::Paired` out of logs.
3. Preserve persisted TLS identities and SPKI pins. Accept legacy raw tokens and `sha256:<hex>` records during credential migrations.
4. Keep receiver connection, message, and outbound-queue limits bounded. Credential removal must terminate sessions authenticated by the removed token.
5. Ensure FFI bindings remain thread-safe and panic-safe. Keep the receiver ABI version separate and bump it for incompatible C/Dart changes.
6. Maintain compatibility with AGPL-3.0 licensing constraints across all core components.

## Verify

From the repository root:

```bash
cargo test -p playbridge-cast-core -p playbridge-cast-receiver \
  -p playbridge-cast-core-ffi -p playbridge-cast-cli \
  --features playbridge-cast-core-ffi/sender-services --locked
cargo clippy -p playbridge-cast-core -p playbridge-cast-receiver \
  -p playbridge-cast-core-ffi -p playbridge-cast-cli --all-targets \
  --features playbridge-cast-core-ffi/sender-services --locked -- -D warnings
```

When the receiver ABI or Desktop-facing Rust code changes, rebuild the native
artifacts from the repository root and validate the Dart consumer:

```bash
sh cast/build-desktop.sh
cd desktop
flutter test test/rust_receiver_runtime_test.dart
```

Run specific examples when testing core discovery or pairing workflows:

```bash
cargo run --example discover -p playbridge-cast-core
cargo run --example pair -p playbridge-cast-core
```
