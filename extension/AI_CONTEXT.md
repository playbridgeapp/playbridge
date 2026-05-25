# Extension — AI Context
_Last verified: 2026-05-23_

## Ownership
The `extension/` directory is the standalone **Firefox desktop extension**. It detects media in desktop browser tabs and casts to a PlayBridge receiver over WebSockets — mirroring the phone app's built-in `video_detector`. It is now written in **TypeScript**, bundled with **esbuild** (`build.mjs`), and consumes the `protocol/` submodule's TypeScript protobuf binding via `@bufbuild/protobuf`.

## Key Files
- `src/background.ts` — request interception, WebSocket client to the receiver, context-menu/cast commands
- `src/content.ts` — in-page detection + Shadow DOM floating button
- `src/hls-parser.ts` — HLS manifest parsing for quality selection
- `src/config.ts` — shared constants
- `src/ui/` — popup UI (video list, subtitles, URL sender, connection settings)
- `manifests/firefox.json` — Firefox manifest
- `build.mjs` — esbuild bundler entry (`npm run build` / `watch`)
- `tsconfig.json` — TypeScript config

> The committed `*.js` files next to the `*.ts` sources are esbuild **outputs** — edit the `.ts`, not the `.js`.

## Inter-module Contracts
- Calls into: `@bufbuild/protobuf` with the generated TS types from `protocol/generated/typescript`.
- Called by: none.
- Communication mechanism: WebSockets directly to a receiver's server.

## Gotchas
WARNING: **wss is out of scope for the extension (v1).** A secure-context page can't trust a self-signed `wss://` cert for a LAN IP, and a worker has no click-through. Since receivers now default to **wss-only**, the extension can't reach a default receiver unless the user enables "Allow insecure (ws)" on it. The plan is to either restrict the extension to a loopback-only `ws://` path or drop it — undecided (`WSS_MIGRATION_PLAN.md` Phase 2b).
WARNING: Manual message construction must match the protocol exactly; prefer the generated `@bufbuild/protobuf` types over hand-rolled JSON.
WARNING: Test UI changes with Playwright, mocking `window.browser`.

## Current State
_As of 2026-05-23:_
- Working: TypeScript/esbuild build, media detection (standard + HLS), popup UI, direct `ws://` connection to receivers.
- Broken/degraded: cannot reach wss-only receivers (see gotcha) — needs the user's "Allow insecure" toggle.
- In progress: deciding the long-term secure-transport story.
- Blockers: none.
