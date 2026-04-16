# TV App — AI Context
_Last verified: 2026-04-15_

## Ownership
The `tv/` module provides a Leanback UI for the Android TV application, acting as the receiver. It runs a Ktor WebSocket server to listen for commands and handles video playback via MPV, VLC, or ExoPlayer. It also provides a fallback web browser (SystemWebView or GeckoView). It does NOT own protocol data structures.

## Key Files
- `player/app/src/main/java/com/playbridge/player/player/PlayerActivity.kt` — base abstract full-screen Leanback player
- `player/app/src/main/java/com/playbridge/player/server/ServerService.kt` — foreground service keeping WebSocket server alive
- `player/app/src/main/java/com/playbridge/player/player/ContentSniffer.kt` — pre-flight content-type sniffing (maintains intentional SSL bypass for local-network URLs only)
- `browser/app/src/main/java/com/playbridge/browser/AdBlocker.kt` — singleton ad blocker logic for TV browser

## Inter-module Contracts
- Calls into: `protocol/` module for structured `Message` data classes.
- Called by: none (top-level app module).
- Communication mechanism: Hosts a Ktor WebSocket server to receive JSON commands from Phone app.

## Gotchas
WARNING: `network_security_config.xml` has a global `<base-config cleartextTrafficPermitted="true">` which must be removed for production. CIDR notation is invalid in `<domain>` tags.
WARNING: Uses `SYSTEM_ALERT_WINDOW` as a workaround for Android 14+ background activity limits.
GOTCHA: `ContentSniffer.kt` deliberately bypasses SSL certificate validation, but ONLY for local network URLs (like `192.168.x.x` or `.local`).

## Current State
_As of 2026-04-15:_
- Working: Core Infrastructure, WebSocket Server, Pairing System (Phase 1 complete), Dual-engine browser setup
- Broken/degraded: nothing critical
- In progress: Phase 2 & 3 tasks (Player UI, Leanback integration, Settings)
- Blockers: Play Store blockers (cleartext traffic config globally enabled, missing Privacy Policy URL, Data Safety section unfilled)
