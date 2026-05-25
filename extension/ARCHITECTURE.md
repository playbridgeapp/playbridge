# Standalone Browser Extension Architecture
_Last verified: 2026-05-23_

The `extension/` directory is a standalone Web Extension that brings PlayBridge casting to **Firefox (Desktop)**. It is written in **TypeScript**, bundled with **esbuild** into `dist/`, and connects directly to a receiver over WebSockets (injected Shadow DOM UI + popup).

*(The Android phone app uses its own lightweight bundled extension at `mobile/android/app/src/main/assets/extensions/video_detector` for internal GeckoView communication — separate from this one.)*

## Package Structure
```
extension/
├── build.mjs              (esbuild bundler — bundles .ts entry points into dist/, copies statics)
├── tsconfig.json
├── manifests/
│   └── firefox.json       (Firefox manifest → copied to dist/manifest.json)
├── src/
│   ├── background.ts       (video detection, WebSocket client, context-menu/cast commands)
│   ├── content.ts          (in-page video UI, Shadow DOM floating button)
│   ├── hls-parser.ts       (HLS manifest parsing for quality selection)
│   ├── config.ts           (shared constants)
│   ├── icon.png
│   └── ui/
│       ├── popup.ts         (popup logic: video list, subtitles, URL sender, connection settings)
│       ├── popup.html / popup.css
│       └── fonts/           (Outfit woff2 + outfit.css)
└── dist/                   (esbuild output — packaged into the .xpi)
```

> The `*.js` files committed beside the `*.ts` sources are esbuild outputs; edit the TypeScript.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Background Script | `src/background.ts` | Request interception, WebSocket client, TV commands, right-click "PlayBridge" context menu ("Play on TV" / "Open on TV") |
| Content Script | `src/content.ts` | In-page video UI (Shadow DOM floating button) |
| HLS Parser | `src/hls-parser.ts` | HLS manifest parsing for quality selection |
| Popup UI | `src/ui/popup.*` | Video list, subtitles, URL sender, connection settings |
| Protocol types | `@bufbuild/protobuf` | Consumes the generated TS binding from `protocol/generated/typescript` |

## Build & Release
- **Local**: `npm run build` (or `npm run watch`) → runs `build.mjs` (esbuild, target `firefox102`), emitting `dist/` and copying `manifests/firefox.json` → `dist/manifest.json`.
- **CI**: `.github/workflows/extension_build.yml` packages the build into a `.xpi` (Firefox extension package) and attaches it to the GitHub Release when the version is bumped.

## Dependencies
- **esbuild** — bundler (`build.mjs`).
- **TypeScript** — source language.
- **@bufbuild/protobuf** v2 — runtime for the generated protobuf message types.

## Secure transport note
Receivers now default to **wss-only**, but a secure-context extension page cannot pin a self-signed `wss://` LAN cert. Until resolved, the extension only reaches a receiver that has "Allow insecure (ws)" enabled. See `WSS_MIGRATION_PLAN.md` (Phase 2b) for the open decision (loopback-only `ws://` vs. drop).
