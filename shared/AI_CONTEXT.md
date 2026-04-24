# Shared Module — AI Context
_Last verified: 2026-04-24_

## Ownership
The `shared/` module is a Kotlin Multiplatform (KMP) library that contains the core business logic, playback engine abstractions, and cross-platform utilities. It aims to unify logic between Android and Apple TV platforms. It owns shared state (ViewModels), Stremio resolution, and platform-specific engine implementations (ExoPlayer, VLC, MPV, AVPlayer).

## Key Files
- `src/commonMain/kotlin/com/playbridge/shared/player/PlaybackEngine.kt` — interface for video player engines
- `src/commonMain/kotlin/com/playbridge/shared/player/PlayerViewModel.kt` — shared state management for playback
- `src/commonMain/kotlin/com/playbridge/shared/stremio/StremioClient.kt` — shared logic for resolver addons
- `src/androidMain/kotlin/com/playbridge/shared/player/ExoPlayerEngine.kt` — Android implementation using ExoPlayer
- `src/appleMain/kotlin/com/playbridge/shared/player/AVPlayerEngine.kt` — Apple implementation using AVPlayer
- `src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` — shared protocol definitions (migrated from `protocol/`)
- `src/commonMain/kotlin/com/playbridge/shared/resume/ResumeStore.kt` — shared logic for persistence of playback positions

## Inter-module Contracts
- Calls into: none (pure logic/platform APIs).
- Called by: `phone/`, `tv/player/app`, and `tv/apple-tv/PlayBridge TV`.
- Communication mechanism: Included as a Gradle dependency for Android (`:shared`); compiled into a framework for Apple TV (CocoaPods/Swift Package).

## Gotchas
WARNING: Uses `expect`/`actual` for platform-specific logic (e.g., `FileSystem`, `Logger`, `SharedHttpClient`).
WARNING: `Message.kt` here must be kept in sync with `extension/src/background.js` as they share the same protocol structure.
WARNING: Any change to `PlaybackEngine` interface requires updates in all platform-specific implementations.

## Current State
_As of 2026-04-24:_
- Working: KMP setup, shared Stremio resolution, Android player engines (Exo, VLC, MPV), Apple AVPlayer engine, ResumeStore logic.
- Broken/degraded: nothing critical.
- In progress: Completing the migration of all protocol logic from `protocol/` to `shared/`.
- Blockers: none.
