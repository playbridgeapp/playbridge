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
| Phone | `phone/` | Android shell: hosts the Hub UI via GeckoView |
| Hub | `hub/` | Integrated Smart Hub: Go Backend + Svelte UI |
| TV (Android) | `tv/player/` | Android TV receiver: Dumb player (ExoPlayer/MPV/VLC) |
| TV (Apple) | `tv/apple-tv/` | Apple TV receiver: Dumb player (AVPlayer/VLC) |
| Shared | `shared/` | KMP logic: Protocol and cross-platform bridge |
| Extension | `extension/` | Desktop web extension (Firefox) |

## Build Commands
- `zsh -c "source ~/.zshrc && ./gradlew :phone:app:assembleDebug"`
- `zsh -c "source ~/.zshrc && ./gradlew :tv:player:app:assembleDebug"`
- `zsh -c "source ~/.zshrc && ./gradlew :shared:build"`
- **Hub Server**: `cd hub/server && go build`
- **Hub UI**: `cd hub/ui && pnpm build`
- **Apple TV**: Open Xcode at `tv/apple-tv/PlayBridge TV/PlayBridge TV.xcworkspace`

## Cross-cutting Gotchas
WARNING: **Protocol ripple:** Any change to `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` must be reflected in:
1. `tv/player/app/src/main/java/com/playbridge/player/server/ServerService.kt`
2. `phone/app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt`
3. `tv/apple-tv/PlayBridge TV/PlayBridge TV/Network/WebSocketServer.swift`
4. `extension/src/background.js` (Manual JSON parsing/formatting)
5. `hub/ui/src/routes/+page.svelte` (JS Bridge payload)

WARNING: **Dumb Receiver Rule:** Receivers (TV) MUST NOT perform content resolution. They must follow redirects from the Hub.

WARNING: **GeckoView version must stay in sync:** Phone and TV both depend on GeckoView. If the version is bumped in `gradle/libs.versions.toml`, it must be bumped in both or behavior diverges.

_As of 2026-04-25:_
- **Working**: KMP shared logic, Android phone/tv apps, Apple TV receiver app (AVPlayer/VLC), Hub project (Go aggregator + SvelteKit mobile-first UI).
- **Architecture Shift**: Transitioning to "Headless Content Hub." The Hub handles scrapers, debrid resolution, and metadata. Receivers (TV) only receive a single, resolved redirect URL.
- **New Feature**: "Live Discovery" Engine implemented in Go for real-time catalog querying and adaptive capability filtering.
- **Streaming Preferences**: System now supports user-defined quality, bitrate, and source type preferences stored on the Hub.
- **In progress**: Finalizing Single Stream Redirect logic and unified history sync.
- **Blockers**: none.
