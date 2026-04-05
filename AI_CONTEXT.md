# PlayBridge — AI Context
_Last verified: 2026-04-05_

## Ownership
PlayBridge is a system to cast web video from an Android phone (sender) to an Android TV (receiver). This root directory manages the mono-repo configuration and shared documentation. It does NOT own any platform-specific application logic.

## Key Files
- `ARCHITECTURE.md` — project-wide architectural decisions and status
- `AI_CONTEXT.md` — cross-module guidelines and gotchas

## Module Map
| Module | Path | Role |
|---|---|---|
| Phone | `phone/` | Android sender app with GeckoView browser |
| TV | `tv/` | Android TV receiver app with MPV player |
| Protocol | `protocol/` | Shared data classes for WebSocket messages |
| Extension | `extension/` | Desktop web extension (Firefox) |

## Build Commands
- `./gradlew app:assembleDebug` (run in `phone/` or `tv/`)
- `./gradlew app:assembleRelease` (run in `phone/` or `tv/`)
- `./gradlew build` (run in `protocol/`)

## Cross-cutting Gotchas
WARNING: **Protocol ripple:** Any change to `protocol/src/main/java/com/playbridge/protocol/Message.kt` must be reflected in BOTH `tv/app/src/main/java/com/playbridge/receiver/server/ServerService.kt` AND `phone/app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt`, and potentially `extension/src/background.js`.
WARNING: **GeckoView version must stay in sync:** Phone and TV both depend on GeckoView. If the version is bumped in one module's `gradle/libs.versions.toml`, it must be bumped in the other or runtime behavior diverges.

## Current State
_As of 2026-04-03:_
- Working: Multi-module Gradle build, protocol sharing, phone/tv apps compiling
- Broken/degraded: nothing critical
- In progress: Web extension integration
- Blockers: TV App Play Store compliance issues (cleartext traffic config, missing Privacy Policy, Data Safety)
