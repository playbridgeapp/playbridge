---
name: playbridge-rust-core
description: Work on the PlayBridge Rust core casting engine, FFI bindings, and CLI binary. Use for portable receiver discovery, SAS pairing/encryption, UniFFI/C/JNI bindings, command-line client, or changes under cast/core/, cast/ffi/, and cli/.
---

# PlayBridge Rust Core

## Establish ownership

- Treat `cast/core/`, `cast/ffi/`, and `cli/` as the portable Rust core layer for PlayBridge.
- Keep discovery (mDNS, UPnP), SAS pairing, security (X25519, HKDF, AES-GCM), and stream metadata logic inside `cast/core/`.
- Keep UniFFI, C, and JNI exported bindings in `cast/ffi/`. Ensure language binding definitions stay compatible across platform targets.
- Keep command-line interface features in `cli/`.
- Coordinate FFI or protocol model changes with platform specialist owners (`playbridge-android`, `playbridge-apple`, `playbridge-desktop-proxy`).

## Work safely

1. Follow the root `AGENTS.md` rules and preserve unrelated working-tree edits.
2. Keep sensitive cryptographic keys, SAS session secrets, and credentials zeroized and safe from accidental logging.
3. Ensure FFI bindings remain thread-safe and panic-safe across FFI boundaries.
4. Maintain compatibility with AGPL-3.0 licensing constraints across all core components.

## Verify

From the repository root:

```bash
cargo test -p playbridge-cast-core -p playbridge-cast-core-ffi -p playbridge-cast-cli
cargo check --workspace
```

Run specific examples when testing core discovery or pairing workflows:

```bash
cargo run --example discover -p playbridge-cast-core
cargo run --example pair -p playbridge-cast-core
```
