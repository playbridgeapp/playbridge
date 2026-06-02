# PlayBridge — Browser Extension

A **Firefox** extension that detects media in desktop browser tabs and casts it to a PlayBridge receiver over WebSockets — the desktop counterpart to the phone app's built-in detector.

## Tech

- TypeScript, bundled with [esbuild](https://esbuild.github.io/) via `build.mjs`
- Protocol bindings via `@bufbuild/protobuf`, generated from the [`protocol/`](../protocol/) submodule
- Manifest: `manifests/firefox.json`

## Build

```bash
cd extension
npm install
npm run build      # bundles to dist/   (npm run watch for live rebuild)
```

## Load in Firefox

1. `npm run build`
2. Open `about:debugging` → **This Firefox** → **Load Temporary Add-on…**
3. Select `extension/dist/manifest.json`
