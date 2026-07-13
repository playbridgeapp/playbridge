# PlayBridge — Browser Extension

A **Firefox and Chromium** extension that detects media in desktop browser tabs and casts it to a PlayBridge receiver through the PlayBridge desktop app — the desktop counterpart to the phone app's built-in detector.

## Tech

- TypeScript, bundled with [esbuild](https://esbuild.github.io/) via `build.mjs`
- Protocol bindings via `@bufbuild/protobuf`, generated from the [`protocol/`](../protocol/) submodule
- Manifests: `manifests/firefox.json` (MV2) and `manifests/chrome.json` (MV3)

## Build

```bash
cd extension
npm install
npm run build      # bundles to dist/firefox and dist/chrome
npm test           # focused source-association tests
npm run watch      # live rebuilds both browser targets
```

## Load in Firefox

1. `npm run build`
2. Open `about:debugging` → **This Firefox** → **Load Temporary Add-on…**
3. Select `extension/dist/firefox/manifest.json`

## Load in Chrome, Edge, or Brave

1. `npm run build`
2. Open the browser's extensions page and enable **Developer mode**.
3. Choose **Load unpacked** and select `extension/dist/chrome`.
