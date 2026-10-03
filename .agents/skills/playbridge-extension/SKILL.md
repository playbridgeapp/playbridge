---
name: playbridge-extension
description: Work on the PlayBridge browser extension. Use for TypeScript, WebExtension APIs, background/content/popup code, media detection, video overlays, native messaging, Desktop bridge reconnection, store packaging, manifests, or changes under extension/.
---

# PlayBridge Extension

## Establish ownership

- Work from `extension/`; source lives primarily under `extension/src/` and is built with TypeScript and esbuild.
- Shared pure detection logic lives in `extension/src/core/` (media-candidate, synthetic-hls, hls-parser). Desktop store hosts and the phone GeckoView detector both import it.
- Desktop hosts: `src/background.ts`, `src/content.ts`, popup → `dist/firefox` / `dist/chrome`.
- Phone host: `src/geckoview/*` → `dist/geckoview`, copied on build into `mobile/android/app/src/main/assets/extensions/video_detector/`.
- Modify source and manifests rather than hand-editing generated `dist/` or Android asset JS.
- Load `playbridge-desktop-proxy` when native messaging, Desktop discovery, reconnection, or proxy routing changes.
- Load `playbridge-protocol` when message envelopes, payloads, pairing, or authentication change.
- Load `playbridge-android` when Kotlin `VideoDetector` / cast consumption of `playlistBody` / `audioUrl` changes.
- The GeckoView page API lives in `src/geckoview/page-bridge.ts`, `page-cast.ts`, `plugin-bridge.ts` and `background.ts`. Read root `docs/bridged-apps.md` for `play()`, destination selection, linked queues and native plugin capabilities; coordinate contract changes with the Android host and Apple page API. Companion apps live in the separate `bridged-apps` repo.

## Work safely

1. Follow the root `AGENTS.md` and preserve Chrome/Firefox target compatibility.
2. Keep content-script, page, popup, background, and native-host trust boundaries explicit.
3. Validate origins, frame identity, lifecycle state, and externally supplied media metadata before acting on them.
4. Never log pairing credentials, authenticated headers, cookies, or complete protected stream URLs.
5. Keep the lightweight page API available while bridged-site detection is off. Detection policy changes must stop disabled DOM observers, probes and body scanners and reject late results; page/document generations fence both detector and linked-session messages. Preserve the background's linked-session heartbeat and cancellation cleanup.

## Verify

From `extension/`:

```bash
pnpm typecheck
pnpm test
pnpm build
```

For store or manifest work, also run the relevant store validation or the complete check:

```bash
pnpm store:check
```
