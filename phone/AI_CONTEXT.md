# Phone App — AI Context
_Last verified: 2026-04-05_

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
GOTCHA: Context menu requires proper EngineSession API or custom GeckoView integration (BrowserActivity.kt).
WARNING: Missing error handling in extensions. Browser extension silently catches errors in background.js (e.g. lines 109, 267, 273, 297). Ensure proper error logging.

## Current State
_As of 2026-04-05:_
- Working: Core Infrastructure, Browser Setup, WebSocket Client, WebExtension Support, QR Scanner, URL bar, Extension Management
- Broken/degraded: nothing critical
- In progress: TabManager, TabsScreen, Native App Integration for Video Detector, Video FAB & Bottom Sheet, Send to TV UI
- Blockers: none
