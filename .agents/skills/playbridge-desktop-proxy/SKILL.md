---
name: playbridge-desktop-proxy
description: Work on PlayBridge Flutter Desktop, its Rust-backed receiver adapter, and the Dart stream proxy. Use for receiver UI and playback adapters, Dart FFI lifecycle, libmpv/media_kit, desktop sender behavior, extension bridge integration, local proxy routing, HLS rewriting, FFmpeg AVIO, proxy authentication, Docker packaging, or changes under desktop/ and stream-proxy-dart/.
---

# PlayBridge Desktop and Stream Proxy

## Establish ownership

- Treat `desktop/` and `stream-proxy-dart/` as separate build and release units.
- Treat `desktop/lib/receiver_server.dart` as the production PlayBridge receiver adapter. Rust owns TLS/WSS, pairing, authentication, limits, and command decoding; Dart owns `PlayerController`, UI, certificate/token persistence, discovery publishing, and application lifecycle.
- Treat Rust sender services as the owner of Desktop's outbound discovery/cast workers, embedded Rust stream proxy, and sender-hosted browser receiver lifecycle. Dart owns UI policy, routing choices, media preparation, and session orchestration around those services.
- Treat `desktop/lib/server.dart` as legacy/test-only until an explicit cleanup removes it. Do not implement production receiver behavior there.
- Keep small in-process proxy adaptations with the Desktop owner. Split out a proxy owner for API, authentication, networking, HLS rewriting, FFmpeg, Docker, or standalone release work.
- Give shared Desktop/proxy interfaces one writer while the other consumer reviews compatibility.
- Load `playbridge-extension` for native-messaging or browser-to-Desktop bridge changes.
- Load `playbridge-rust-core` for receiver runtime, Cast session/sender-services ABI, browser receiver host, bundled native library, or `packages/playbridge_cast_core_dart/` changes.
- Load `playbridge-protocol` for cast envelopes, pairing, authentication, or generated Dart binding changes.

## Work safely

1. Follow the root `AGENTS.md` and preserve unrelated Desktop working-tree edits.
2. Keep proxy credentials and session authorization out of logs.
3. Preserve authenticated headers without exposing full stream URLs or tokens in diagnostics.
4. Preserve the persisted receiver TLS identity and SPKI pin across the Rust migration. Store paired credentials as SHA-256 token digests and refresh the runtime after forgetting devices so active credentials are revoked.
5. Keep platform-specific Desktop behavior compatible with macOS, Windows, and Linux where practical.

## Verify

From `desktop/`:

```bash
flutter test
flutter analyze
```

When the Rust receiver, Cast/sender-services C ABI, Dart wrapper, browser receiver,
or bundled library changes, run from
the repository root before the Desktop checks:

```bash
sh cast/build-desktop.sh
cd packages/playbridge_cast_core_dart && dart analyze --fatal-infos
cd ../../desktop && flutter test test/rust_receiver_runtime_test.dart
```

From `stream-proxy-dart/`:

```bash
dart format --output=none --set-exit-if-changed .
dart analyze --fatal-infos
```

Run `dart test` when proxy tests exist or are added. Build the Docker image when container, native dependency, or packaging behavior changes.
