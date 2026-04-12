# Extension — AI Context
_Last verified: 2026-04-12_

## Ownership
The `extension/` directory houses the standalone Desktop Web Extension (primarily targeting Firefox). It intercepts media requests in desktop browsers to cast directly to the PlayBridge TV, mirroring the functionality of the native Phone app's built-in `video_detector`. It does NOT own Android application code.

## Key Files
- `src/background.js` — intercepts network requests and handles WebSocket connection to TV
- `src/manifest.json` — extension permissions and configuration
- `src/ui/popup.html` — browser action popup interface
- `src/config.js` — shared configuration constants

## Inter-module Contracts
- Calls into: none explicitly (pure JS/WebExtension API).
- Called by: none.
- Communication mechanism: WebSockets directly to the TV's `ServerService`.

## Gotchas
WARNING: The extension must manually format JSON messages to match `protocol/src/main/java/com/playbridge/protocol/Message.kt` exactly, as it cannot import the Kotlin data classes.
WARNING: Firefox extension UI changes should be tested using Playwright and `mock` for `window.browser` if opening local HTML files directly. When testing locally with `file://`, the workspace root path is `/app`.

## Current State
_As of 2026-04-12:_
- Working: Connection to TV, media detection (standard + HLS), popup UI
- Broken/degraded: nothing critical
- In progress: polishing and final testing
- Blockers: none
