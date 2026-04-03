# Extension — AI Context
_Last verified: 2026-04-03_

## Ownership
The `extension/` directory houses the standalone Desktop Web Extension (primarily targeting Firefox). It intercepts media requests in desktop browsers to cast directly to the PlayBridge TV, mirroring the functionality of the native Phone app's built-in `video_detector`. It does NOT own Android application code.

## Key Files
- `src/background.js` — intercepts network requests and handles WebSocket connection to TV
- `src/manifest.json` — extension permissions and configuration
- `src/ui/popup.html` — browser action popup interface

## Inter-module Contracts
- Calls into: none explicitly (pure JS/WebExtension API).
- Called by: none.
- Communication mechanism: WebSockets directly to the TV's `ServerService`.

## Gotchas
WARNING: The extension must manually format JSON messages to match `protocol/src/main/java/com/playbridge/protocol/Message.kt` exactly, as it cannot import the Kotlin data classes.
WARNING: Firefox extension UI changes should be tested using Playwright and `mock` for `window.browser` if opening local HTML files directly.

## Current State
_As of 2026-04-03:_
- Working: Basic structure
- Broken/degraded: nothing critical
- In progress: Standalone Desktop Web Extension integration
- Blockers: none
