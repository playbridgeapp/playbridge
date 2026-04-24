# PlayBridge — AI Context
_Last verified: 2026-04-24_

## Ownership
PlayBridge is a system to cast web video from an Android phone (sender) to an Android TV or Apple TV (receiver). This root directory manages the mono-repo configuration and shared documentation. It does NOT own any platform-specific application logic.

## Key Files
- `ARCHITECTURE.md` — project-wide architectural decisions and status
- `AI_CONTEXT.md` — cross-module guidelines and gotchas
- `SHARED_PLANS.md` — roadmap for shared KMP logic

## Module Map
| Module | Path | Role |
|---|---|---|
| Phone | `phone/` | Android sender app with GeckoView browser |
| TV (Android) | `tv/player/` | Android TV receiver app (ExoPlayer/MPV/VLC) |
| TV (Apple) | `tv/apple-tv/` | Apple TV receiver app (AVPlayer/VLC) |
| Shared | `shared/` | KMP logic (Player engines, Stremio, Resume) |
| Protocol | `protocol/` | Legacy shared data classes (migrating to `shared/`) |
| Extension | `extension/` | Desktop web extension (Firefox) |

## Build Commands
- `zsh -c "source ~/.zshrc && ./gradlew :phone:app:assembleDebug"`
- `zsh -c "source ~/.zshrc && ./gradlew :tv:player:app:assembleDebug"`
- `zsh -c "source ~/.zshrc && ./gradlew :shared:build"`
- Apple TV: Open Xcode at `tv/apple-tv/PlayBridge TV/PlayBridge TV.xcworkspace`

## Cross-cutting Gotchas
WARNING: **Protocol ripple:** Any change to `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` (or legacy `protocol/src/main/java/com/playbridge/protocol/Message.kt`) must be reflected in:
1. `tv/player/app/src/main/java/com/playbridge/player/server/ServerService.kt`
2. `phone/app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt`
3. `tv/apple-tv/PlayBridge TV/PlayBridge TV/Network/WebSocketServer.swift`
4. `extension/src/background.js` (Manual JSON parsing/formatting)
WARNING: **GeckoView version must stay in sync:** Phone and TV both depend on GeckoView. If the version is bumped in `gradle/libs.versions.toml`, it must be bumped in both or behavior diverges.

## Current State
_As of 2026-04-24:_
- Working: KMP shared logic, Android phone/tv apps, Apple TV receiver app, Stremio addon resolution, Watchlist tracking, Cloud backup.
- Broken/degraded: nothing critical.
- In progress: Migrating all logic from `protocol` to `shared` (KMP); Apple TV player parity.
- Blockers: TV App Play Store compliance (cleartext traffic, Privacy Policy, Data Safety).
