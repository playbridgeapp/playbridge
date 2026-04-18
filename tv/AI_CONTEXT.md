# TV App — AI Context
_Last verified: 2026-04-17_

## Ownership
The `tv/` module provides a Leanback UI for the Android TV application, acting as the receiver. It runs a Ktor WebSocket server to listen for commands and handles video playback via MPV, VLC, or ExoPlayer. It also provides a fallback web browser (SystemWebView or GeckoView). It does NOT own protocol data structures.

## Key Files
- `player/app/src/main/java/com/playbridge/player/player/PlayerActivity.kt` — base abstract full-screen Leanback player
- `player/app/src/main/java/com/playbridge/player/player/ExoPlayerActivity.kt` — primary modern player implementation
- `player/app/src/main/java/com/playbridge/player/server/ServerService.kt` — foreground service keeping WebSocket server alive
- `player/app/src/main/java/com/playbridge/player/server/WebSocketServer.kt` — Ktor-based server implementation for receiving commands
- `player/app/src/main/java/com/playbridge/player/preplay/PrePlayActivity.kt` — handles stream resolution from metadata
- `player/app/src/main/java/com/playbridge/player/stremio/StremioClient.kt` — core logic for resolving streams from addons
- `player/app/src/main/java/com/playbridge/player/stremio/SeriesNavigator.kt` — manages playlist/series state during playback
- `player/app/src/main/java/com/playbridge/player/player/ContentSniffer.kt` — pre-flight content-type sniffing (maintains intentional SSL bypass for local-network URLs only)
- `browser/app/src/main/java/com/playbridge/browser/AdBlocker.kt` — singleton ad blocker logic for TV browser

## Inter-module Contracts
- Calls into: `protocol/` module for structured `Message` data classes.
- **Protocol ripple now includes `tv/apple-tv/PlayBridgeProtocol/Sources/PlayBridgeProtocol/`.**
- Called by: none (top-level app module).
- Communication mechanism: Hosts a Ktor WebSocket server to receive JSON commands from Phone app.

## Gotchas
WARNING: `network_security_config.xml` has a global `<base-config cleartextTrafficPermitted="true">` which must be removed for production.
WARNING: Uses `SYSTEM_ALERT_WINDOW` as a workaround for Android 14+ background activity limits.
GOTCHA: `ContentSniffer.kt` deliberately bypasses SSL certificate validation, but ONLY for local network URLs (like `192.168.x.x` or `.local`).

## Current State
_As of 2026-04-17:_
- Working: Core Infrastructure, WebSocket Server, Pairing System, Dual-engine browser, Metadata-based handoff, Pre-play resolution, Stream scoring, ExoPlayer playlist support.
- Broken/degraded: nothing critical
- In progress: Player UI refinements, Settings expansion.
- Blockers: Play Store compliance (cleartext traffic config globally enabled, missing Privacy Policy URL, Data Safety section unfilled).
