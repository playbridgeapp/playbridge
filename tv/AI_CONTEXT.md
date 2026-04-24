# TV App — AI Context
_Last verified: 2026-04-24_

## Ownership
The `tv/` module provides a Leanback UI for the Android TV application, acting as the receiver. It runs a Ktor WebSocket server to listen for commands and handles video playback via MPV, VLC, or ExoPlayer engines (now abstracted in `shared/`). It also provides a fallback web browser.

## Key Files
- `player/app/src/main/java/com/playbridge/player/player/PlayerActivity.kt` — base abstract full-screen Leanback player
- `player/app/src/main/java/com/playbridge/player/player/ExoPlayerActivity.kt` — primary modern player implementation
- `player/app/src/main/java/com/playbridge/player/server/ServerService.kt` — foreground service keeping WebSocket server alive
- `player/app/src/main/java/com/playbridge/player/server/WebSocketServer.kt` — Ktor-based server implementation for receiving commands
- `player/app/src/main/java/com/playbridge/player/ui/player/PlayerControlsViewModel.kt` — manages overlay and player state logic
- `player/app/src/main/java/com/playbridge/player/preplay/PrePlayScreen.kt` — Compose-based stream resolution UI
- `player/app/src/main/java/com/playbridge/player/player/ContentSniffer.kt` — pre-flight content-type sniffing (SSL bypass for local IPs)
- `browser/app/src/main/java/com/playbridge/browser/AdBlocker.kt` — singleton ad blocker logic for TV browser

## Inter-module Contracts
- Calls into: `shared/` module for core playback engines, Stremio logic, and state; `protocol/` (legacy) for messages.
- Called by: none (top-level app module).
- Communication mechanism: Hosts a Ktor WebSocket server to receive JSON commands from Phone app.

## Gotchas
WARNING: `network_security_config.xml` has a global `<base-config cleartextTrafficPermitted="true">` which must be removed for production.
WARNING: Uses `SYSTEM_ALERT_WINDOW` as a workaround for Android 14+ background activity limits.
GOTCHA: `ContentSniffer.kt` deliberately bypasses SSL certificate validation, but ONLY for local network URLs.

## Current State
_As of 2026-04-24:_
- Working: Core Infrastructure, WebSocket Server, Pairing System, Dual-engine browser, Metadata-based handoff, Pre-play resolution, Stream scoring, ExoPlayer playlist support.
- Broken/degraded: nothing critical.
- In progress: Migrating player engines to use `shared/` implementations.
- Blockers: Play Store compliance (cleartext traffic, Privacy Policy, Data Safety).
