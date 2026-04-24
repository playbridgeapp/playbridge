# Extension — AI Context
_Last verified: 2026-04-24_

## Ownership
The `extension/` directory houses the standalone Desktop Web Extension (Firefox). It intercepts media requests in desktop browsers to cast directly to the PlayBridge TV, mirroring the functionality of the native Phone app's built-in `video_detector`.

## Key Files
- `src/background.js` — intercepts network requests and handles WebSocket connection to TV
- `src/content.js` — script injected into web pages to detect and extract media URLs
- `src/manifest.json` — extension permissions and configuration
- `src/ui/popup.html` — browser action popup interface
- `src/hls-parser.js` — parses HLS manifests for stream extraction

## Inter-module Contracts
- Calls into: none explicitly (pure JS/WebExtension API).
- Called by: none.
- Communication mechanism: WebSockets directly to the TV's `ServerService`.

## Gotchas
WARNING: The extension must manually format JSON messages to match `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` exactly, as it cannot import the Kotlin data classes.
WARNING: Firefox extension UI changes should be tested using Playwright and `mock` for `window.browser`.

## Current State
_As of 2026-04-24:_
- Working: Connection to TV, media detection (standard + HLS), popup UI.
- Broken/degraded: none.
- In progress: final polishing.
- Blockers: none.
