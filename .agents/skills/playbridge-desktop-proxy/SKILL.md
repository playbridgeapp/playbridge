---
name: playbridge-desktop-proxy
description: Work on the PlayBridge Flutter desktop receiver and its Dart stream proxy. Use for Flutter/Dart receiver UI, playback engines, libmpv/media_kit, desktop sender behavior, extension bridge integration, local proxy routing, HLS rewriting, FFmpeg AVIO, proxy authentication, Docker packaging, or changes under desktop/ and stream-proxy-dart/.
---

# PlayBridge Desktop and Stream Proxy

## Establish ownership

- Treat `desktop/` and `stream-proxy-dart/` as separate build and release units.
- Keep small in-process proxy adaptations with the Desktop owner. Split out a proxy owner for API, authentication, networking, HLS rewriting, FFmpeg, Docker, or standalone release work.
- Give shared Desktop/proxy interfaces one writer while the other consumer reviews compatibility.
- Load `playbridge-extension` for native-messaging or browser-to-Desktop bridge changes.
- Load `playbridge-protocol` for cast envelopes, pairing, authentication, or generated Dart binding changes.

## Work safely

1. Follow the root `AGENTS.md` and preserve unrelated Desktop working-tree edits.
2. Keep proxy credentials and session authorization out of logs.
3. Preserve authenticated headers without exposing full stream URLs or tokens in diagnostics.
4. Keep platform-specific Desktop behavior compatible with macOS, Windows, and Linux where practical.

## Verify

From `desktop/`:

```bash
flutter test
flutter analyze
```

From `stream-proxy-dart/`:

```bash
dart format --output=none --set-exit-if-changed .
dart analyze --fatal-infos
```

Run `dart test` when proxy tests exist or are added. Build the Docker image when container, native dependency, or packaging behavior changes.
