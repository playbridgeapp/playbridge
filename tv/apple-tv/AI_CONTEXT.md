# Apple TV App — AI Context
_Last verified: 2026-04-24_

## Ownership
The `tv/apple-tv/` directory contains the Apple TV (tvOS) version of PlayBridge. It is a native Swift project using SwiftUI. It provides a receiver interface for Apple TV, supporting video playback via AVPlayer and TVVLCKit. It handles WebSocket communication and stream resolution via the shared KMP module.

## Key Files
- `PlayBridge TV/PlayBridge_TVApp.swift` — main entry point and app lifecycle
- `PlayBridge TV/Player/PlayerView.swift` — unified player view controller supporting multiple engines
- `PlayBridge TV/Network/WebSocketServer.swift` — handles incoming commands from the phone
- `PlayBridge TV/Network/VLCProxyServer.swift` — custom HTTP proxy for VLC playback support
- `PlayBridge TV/UI/Views/PairingView.swift` — UI for pairing with the phone app
- `PlayBridge TV/Data/HistoryStore.swift` — local persistence for watch history
- `PlayBridge TV/Player/VLCPlayerView.swift` — specific view for TVVLCKit-based playback

## Inter-module Contracts
- Calls into: `shared/` (Kotlin Multiplatform framework) for business logic, Stremio resolution, and player state management.
- Called by: none (receiver app).
- Communication mechanism: WebSockets for receiving commands; local HTTP proxy for VLC stream manipulation and header injection.

## Gotchas
WARNING: Uses a custom `VLCProxyServer` to handle connection lifecycles, backpressure, and arbitrary HTTP header injection for VLC.
WARNING: Any changes to `shared/` models or interfaces require a framework rebuild (e.g., via `./gradlew :shared:embedAndSignAppleFrameworkForXcode`) before they are visible in Xcode.
GOTCHA: Uses `TVVLCKit` via CocoaPods; ensure `pod install` is run if dependencies change.

## Current State
_As of 2026-04-24:_
- Working: SwiftUI navigation, AVPlayer integration, VLC playback via proxy, history tracking, phone pairing.
- Broken/degraded: nothing critical.
- In progress: Implementing progressive scrubbing for VLC to match native Apple TV behavior.
- Blockers: none.
