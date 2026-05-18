# MPV Player Comparison: PlayBridge vs. mpvEx

This document compares the MPV player implementation in **PlayBridge TV** (`tv/player`) with the **mpvEx** project (`mpvEx`). It serves as a reference for future feature parity and architectural improvements.

## Overview

| Player | Focus | Architecture | UI Paradigm |
| :--- | :--- | :--- | :--- |
| **PlayBridge TV** | Specialized Receiver | Receiver-centric (Ktor/WebSocket) | Leanback (D-pad/TV) |
| **mpvEx** | Feature-rich Standalone | Clean Architecture (MVVM/Koin) | Hybrid (Mobile Gestures + Sheets) |

---

## Feature Matrix

| Feature | PlayBridge TV | mpvEx |
| :--- | :--- | :--- |
| **Subtitle Rendering** | Basic (Staged Roboto fallback) | Advanced (Custom fonts, colors, size, scale) |
| **Config Customization** | Hardcoded options | Full sync of `mpv.conf`, `input.conf`, `scripts/` |
| **Shaders** | Not supported | Integrated **Anime4K** manager and shaders |
| **System Integration** | Background service for Server | **MediaSession**, Advanced PiP, Noisy Audio handling |
| **Navigation** | SeriesNavigator (Stremio optimized) | Windowed Playlist Loading, Shuffle persistence |
| **Gestures** | D-pad focus only | Brightness, Volume, and Precise Seeking gestures |
| **Video Filters** | Basic via SwitchPlayerDialog | Persistent Video Filter management |
| **Persistence** | HistoryStore (position, speed) | Full PlaybackState database (tracks, delays, zoom) |

---

## Key Architectural Differences

### 1. MPV Lifecycle & Configuration
- **mpvEx** treats MPV as a fully customizable engine. It syncs a user's entire `.mpv` configuration directory (including scripts and shaders) from external storage to the app's internal files.
- **PlayBridge** uses a more "embedded" approach with fixed configuration tailored specifically for the TV casting experience.

### 2. UI Framework
- **mpvEx** heavily utilizes **Jetpack Compose** for its controls overlay, using a "Sheets" and "Panels" system for settings.
- **PlayBridge** uses a **Classic Android View** layout for the player controls (optimized for focus-based D-pad navigation) but uses Compose for the Pre-Play and Stream Selection overlays.

### 3. Media Session & Controls
- **mpvEx** implements a full `MediaSession` with `MediaPlaybackService`. This allows it to be controlled via system notifications, lock screen, and external devices (Bluetooth, Android Auto).
- **PlayBridge** relies on its internal WebSocket server for remote control from the phone app.

---

## Opportunities for PlayBridge

Based on the "richer" implementation in `mpvEx`, the following features are high-value candidates for porting to PlayBridge TV:

1.  **User Shaders (Anime4K):** Utilizing the `MPVView.kt` patterns from `mpvEx` to allow TV users to upscale low-resolution streams.
2.  **Advanced Subtitle Styling:** Porting the `SubtitleSettingsTypographyCard` logic to provide a UI for subtitle font/color adjustment.
3.  **MediaSession Integration:** Adding `MediaSession` support to PlayBridge would allow TV remote "Media" keys (Play/Pause) to work more reliably across different TV OS implementations.
4.  **Config Sync:** Allowing users to provide their own `mpv.conf` would enable power users to tweak the engine (e.g., custom debanding or interpolation).

---

## Reference Files
- **PlayBridge Player:** `tv/player/app/src/main/java/com/playbridge/player/player/MpvPlayerActivity.kt`
- **mpvEx Player:** `mpvEx/app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt`
- **mpvEx Engine View:** `mpvEx/app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt`
