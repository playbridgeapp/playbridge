# Phone App — AI Context
_Last verified: 2026-03-30_

## Ownership
The `phone/` module handles all UI and networking for the Android sender application. It provides a full-featured GeckoView web browser, Debrid integration, history tracking, and connects directly to the TV application via WebSockets to send video payloads. It does NOT own protocol data structures.

## Key Files
- `app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt` — primary activity hosting GeckoView
- `app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt` — manages WebSocket state with TV
- `app/src/main/java/com/playbridge/sender/browser/TabManager.kt` — session lifecycle and tab switching
- `app/src/main/java/com/playbridge/sender/browser/MediaDownloadService.kt` — handles file/HLS downloads

## Inter-module Contracts
- Calls into: `protocol/` module for structured `Message` data classes.
- Called by: none (top-level app module).
- Communication mechanism: WebSockets to TV app (JSON serialized via kotlinx.serialization); embeds `video_detector` extension natively.

## Gotchas
WARNING: The `video_detector` extension is embedded in `assets/extensions/video_detector/` and depends on Mozilla Android Components AddonManager.
WARNING: Debrid APIs (Real-Debrid, Premiumize) logic is highly sensitive to token management; ensure credentials are not accidentally logged.

## Current State
_As of 2026-03-30:_
- Working: Core Infrastructure, Browser Setup, WebSocket Client, WebExtension Support (Phase 1 & 2 complete)
- Broken/degraded: nothing critical
- In progress: Video Detection WebExtension, Video Detection UI (Phases 3 & 4)
- Blockers: none
