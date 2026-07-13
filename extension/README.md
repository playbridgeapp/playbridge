# PlayBridge — Browser Extension

A **Firefox and Chromium** extension that detects media in desktop browser tabs and casts it to a PlayBridge receiver through the PlayBridge desktop app — the desktop counterpart to the phone app's built-in detector.

## Tech

- TypeScript, bundled with [esbuild](https://esbuild.github.io/) via `build.mjs`
- Protocol bindings via `@bufbuild/protobuf`, generated from the [`protocol/`](../protocol/) submodule
- Manifests: `manifests/firefox.json` (MV2) and `manifests/chrome.json` (MV3)

## Build

```bash
cd extension
corepack enable
pnpm install --frozen-lockfile
pnpm build      # bundles to dist/firefox and dist/chrome
pnpm test       # focused source-association and preference tests
pnpm watch      # live rebuilds both browser targets
```

`package.json` pins the pnpm version used for reproducible builds.

## Store validation and packaging

```bash
pnpm store:check
```

This runs type-checking, tests, both browser builds, manifest validation,
Firefox `web-ext lint`, and deterministic packaging. Uploadable Firefox/Chrome
ZIPs, the separate AMO source ZIP, and SHA-256 checksums are written to
`artifacts/`. See [`AMO_SOURCE_BUILD.md`](AMO_SOURCE_BUILD.md) and
[`STORE_PUBLISHING_READINESS.md`](STORE_PUBLISHING_READINESS.md).

## Load in Firefox

1. `pnpm build`
2. Open `about:debugging` → **This Firefox** → **Load Temporary Add-on…**
3. Select `extension/dist/firefox/manifest.json`

## Load in Chrome, Edge, or Brave

1. `pnpm build`
2. Open the browser's extensions page and enable **Developer mode**.
3. Choose **Load unpacked** and select `extension/dist/chrome`.
