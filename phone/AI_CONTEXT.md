# Phone App — AI Context
_Last verified: 2026-04-24_

## Ownership
The `phone/` module handles all UI and networking for the Android sender application. It provides a full-featured GeckoView web browser, Debrid integration, history tracking, and connects directly to the TV application via WebSockets to send video payloads. It uses the `shared/` module for core business logic.

## Key Files
- `app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt` — primary activity hosting GeckoView
- `app/src/main/java/com/playbridge/sender/connection/WebSocketClient.kt` — core networking for communication with the TV
- `app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt` — manages WebSocket state with TV
- `app/src/main/java/com/playbridge/sender/browser/TabManager.kt` — session lifecycle and tab switching
- `app/src/main/java/com/playbridge/sender/browser/LibraryViewModel.kt` — manages state for the library
- `app/src/main/java/com/playbridge/sender/browser/CastSheet.kt` — UI for choosing player and initiating cast
- `app/src/main/assets/extensions/video_detector/background.js` — detects videos in GeckoView tabs
- `app/src/main/java/com/playbridge/sender/browser/SessionObserverSetup.kt` — centralizes GeckoView context menu logic

## Inter-module Contracts
- Calls into: `shared/` module for core logic and playback state.
- Called by: none (top-level app module).
- Communication mechanism: WebSockets to TV app (JSON serialized via kotlinx.serialization); embeds `video_detector` extension natively.

## Gotchas
WARNING: The `video_detector` extension is embedded in `assets/extensions/video_detector/` and depends on Mozilla Android Components AddonManager.
WARNING: Debrid APIs (Real-Debrid, Premiumize) logic is highly sensitive to token management; ensure credentials are not accidentally logged.
GOTCHA: Context menu logic is centralized in `SessionObserverSetup.kt`; avoid redundant implementation in `BrowserActivity`'s `BrowserView`.
WARNING: Missing error handling in extensions. Browser extension silently catches errors in background.js.
WARNING: `network_security_config.xml` has a global `<base-config cleartextTrafficPermitted="true">` for local networking.

## Current State
_As of 2026-04-24:_
- Working: Core Infrastructure, Browser Setup, WebSocket Client, WebExtension Support, QR Scanner, URL bar, Local history DB, Metadata-based library handoff, Kitsu/MAL ID normalization.
- Broken/degraded: nothing critical
- In progress: Migrating core logic to use the `shared` KMP module.
- Blockers: none.
