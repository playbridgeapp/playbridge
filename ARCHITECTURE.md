# PlayBridge Architecture Review & Open-Source Recommendations

This document provides a comprehensive architecture review of the PlayBridge project and actionable recommendations before open-sourcing.

---

## Project Overview

**PlayBridge** is a casting solution enabling Android phones to send video URLs and browser control commands to Android TV devices. The project consists of two independent Android applications and a shared protocol module:

| Module | Package | Purpose |
|--------|---------|---------|
| **Phone (Sender)** | `com.playbridge.sender` | GeckoView-based browser with video detection, downloads, remote control, sends commands to TV |
| **TV (Receiver)** | `com.playbridge.receiver` | WebSocket server + ExoPlayer + WebView browser, receives and plays video streams |
| **Protocol** | `com.playbridge.protocol` | Shared protocol: NSD constants, message classes, command parser, and helper functions |

---

## Architecture Diagram

```mermaid
graph TB
    subgraph Phone App
        Browser[GeckoView Browser]
        Extension[Video Detector Extension]
        WSClient[WebSocket Client]
        Connection[Connection Screen<br/>NSD Discovery + QR + Manual]
        RemoteControl[Remote Control UI]
        HLS[HLS Parser]
        Downloads[Download Manager]
        HistoryDB[Room History DB]
    end
    
    subgraph TV App
        WSServer[WebSocket Server]
        Player[ExoPlayer + Subtitles]
        TVBrowser[TV Browser + Ad Blocker]
        TrackSel[Track Selection Dialog]
        History[History Store]
        ServerSvc[Server Foreground Service]
    end
    
    Extension --> Browser
    Browser --> WSClient
    Browser --> Downloads
    Browser --> HistoryDB
    HLS --> Browser
    Connection --> WSClient
    RemoteControl --> WSClient
    WSClient <-->|Play/Control/Remote/Mouse/Browser Commands| WSServer
    ServerSvc --> WSServer
    WSServer --> Player
    WSServer --> TVBrowser
    WSServer --> History
    Player --> TrackSel
```

---

## Phone App Architecture

### Package Structure
```
com.playbridge.sender/
├── browser/                # GeckoView browser, video detection, extensions, downloads
│   ├── AddonInstallDialog.kt       (extension install confirmation dialog)
│   ├── BrowserActivity.kt          (~1555 lines - VERY LARGE)
│   ├── BrowserToolbar.kt           (custom Compose toolbar with URL bar, navigation, menu)
│   ├── Components.kt               (singleton DI container for GeckoRuntime, BrowserStore)
│   ├── DetectedVideosSheet.kt      (bottom sheet for detected videos + quality selection)
│   ├── DownloadManagerSingleton.kt  (Media3 ExoPlayer download manager for HLS)
│   ├── DownloadsScreen.kt          (downloads list UI with progress tracking)
│   ├── DownloadUtils.kt            (download helpers: enqueue, file size, error strings)
│   ├── ExtensionsScreen.kt         (addon management screen)
│   ├── FindOnPageBar.kt            (UI for in-page text search)
│   ├── HistoryScreen.kt            (browsing history list UI)
│   ├── HlsParser.kt               (HLS manifest parsing for quality selection)
│   ├── MediaDownloadService.kt     (foreground service for HLS/media downloads)
│   ├── RemoteControlSheet.kt       (TV remote control: D-pad, touchpad, player controls)
│   ├── SettingsScreen.kt           (browser settings: inbuilt extension visibility)
│   ├── TabsScreen.kt               (tab management overlay)
│   └── VideoDetector.kt            (video content type detection via request interception)
├── connection/             # WebSocket client + service discovery
│   ├── ConnectionStore.kt          (DataStore persistence for connection history)
│   ├── NsdHelper.kt                (Network Service Discovery to find TV services)
│   └── WebSocketClient.kt          (OkHttp-based client with auto-retry)
├── data/                   # Local data persistence
│   └── history/
│       ├── DatabaseProvider.kt      (Room database singleton provider)
│       ├── HistoryDao.kt            (Room DAO for browsing history CRUD)
│       ├── HistoryDatabase.kt       (Room database definition)
│       └── HistoryEntity.kt         (History entry data class: url, title, timestamp)
├── model/                  # App-specific models
│   ├── Message.kt                   (QRCodeData + parseQRCode — phone-only)
│   └── TvDevice.kt                  (TV device connection info)
└── ui/                     # Compose UI screens
    ├── ConnectionScreen.kt          (NSD discovery + QR scan + manual IP + PIN auth)
    ├── HomeScreen.kt                (main home with device connection)
    └── theme/
```

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Browser Engine | [Components.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/Components.kt) | Singleton DI container for GeckoRuntime, BrowserStore, AddonManager |
| Browser UI | [BrowserActivity.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt) | Main browser activity with tabs, extensions, context menus, downloads, history |
| Browser Toolbar | [BrowserToolbar.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/BrowserToolbar.kt) | Custom Compose toolbar with navigation, URL bar (full-width on edit), menu |
| Tab Management | [TabsScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/TabsScreen.kt) | Tab list/grid overlay with thumbnails |
| Extension Management | [ExtensionsScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/ExtensionsScreen.kt) | Addon installation and management UI |
| Addon Install | [AddonInstallDialog.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/AddonInstallDialog.kt) | Extension installation confirmation dialog |
| Remote Control | [RemoteControlSheet.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/RemoteControlSheet.kt) | Context-aware D-pad, touchpad, and player controls for TV |
| HLS Parser | [HlsParser.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/HlsParser.kt) | Parses HLS manifests for quality selection with audio track support |
| Video Detection | [VideoDetector.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/VideoDetector.kt) | Video content type detection via request interception |
| Downloads | [DownloadsScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/DownloadsScreen.kt) | Download list UI (system + HLS/ExoPlayer downloads) |
| Download Utils | [DownloadUtils.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/DownloadUtils.kt) | Download helper: standard files via DownloadManager, HLS via ExoPlayer DownloadService |
| HLS Download Manager | [DownloadManagerSingleton.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/DownloadManagerSingleton.kt) | Media3 ExoPlayer download manager singleton for HLS offline downloads |
| Media Download Service | [MediaDownloadService.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/MediaDownloadService.kt) | Foreground service for HLS media downloads with notifications |
| Browser Settings | [SettingsScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/SettingsScreen.kt) | Browser settings (e.g., toggle inbuilt extension visibility) |
| Browser History | [HistoryScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/HistoryScreen.kt) | Browsing history list with clear functionality |
| History Database | [DatabaseProvider.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/data/history/DatabaseProvider.kt) | Room database singleton with HistoryDao for CRUD |
| Find on Page | [FindOnPageBar.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/browser/FindOnPageBar.kt) | UI for finding text within web pages |
| WebSocket | [WebSocketClient.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/connection/WebSocketClient.kt) | OkHttp-based client with auto-retry (60 attempts, 5s intervals) |
| Connection | [ConnectionScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/ui/ConnectionScreen.kt) | NSD auto-discovery, QR scanning, manual IP entry, PIN authentication |
| Service Discovery | [NsdHelper.kt](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/java/com/playbridge/sender/connection/NsdHelper.kt) | Network Service Discovery to find TV services on local network |
| Video Detection Extension | [background.js](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/assets/extensions/video_detector/background.js) | Browser extension detecting video content types via webRequest |
| Content Script | [content.js](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/assets/extensions/video_detector/content.js) | Content script for in-page video element detection |

### Dependencies
- **GeckoView** (Mozilla) v147 — Full Firefox engine
- **Mozilla Android Components** v147 — Tabs, toolbar, extensions, sessions, prompts support
- **OkHttp** v4.12 — WebSocket client
- **CameraX** v1.4 + **ML Kit Barcode** v17.3 — QR code scanning
- **Jetpack Compose** — UI (Material3)
- **Kotlin Serialization** v1.7 — JSON protocol
- **DataStore** v1.1 — Preferences persistence
- **Room** — SQLite persistence for browsing history
- **Media3 ExoPlayer** — HLS offline download support
- **Coil** — Image loading

---

## TV App Architecture

### Package Structure
```
com.playbridge.receiver/
├── MainActivity.kt                # Compose navigation + screen state management (~206 lines)
├── browser/                       # TV WebView browser
│   ├── AdBlocker.kt               (WebView ad blocking with filter list parsing)
│   └── BrowserActivity.kt         (WebView-based TV browser with remote input)
├── data/                          # Persistence
│   └── HistoryStore.kt            (DataStore-based playback history)
├── model/                         # App-specific models
│   └── PairedDevice.kt            (paired device info)
├── pairing/                       # QR code display, token management
│   ├── PairingStore.kt            (DataStore persistence for auth tokens)
│   └── QRGenerator.kt             (ZXing QR code bitmap generation)
├── player/                        # Video playback
│   ├── PlayerActivity.kt          (~1125 lines, ExoPlayer with HLS/DASH/RTSP)
│   ├── SubtitleManager.kt         (SRT/VTT subtitle parser + sync engine)
│   └── TrackSelectionDialog.kt    (Compose TV dialog for audio/video/subtitle track selection)
├── server/                        # WebSocket server
│   ├── ServerService.kt           (foreground service + command routing, ~385 lines)
│   └── WebSocketServer.kt         (Ktor-based WebSocket server)
└── ui/                            # Compose TV UI screens
    ├── HistoryScreen.kt
    ├── HomeScreen.kt
    ├── PairingScreen.kt
    ├── SettingsScreen.kt
    └── theme/
```

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| WebSocket Server | [WebSocketServer.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/server/WebSocketServer.kt) | Ktor Netty server on port 8765 with auth |
| Server Service | [ServerService.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/server/ServerService.kt) | Foreground service managing server lifecycle, command routing, NSD registration, context broadcasting |
| Video Player | [PlayerActivity.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/player/PlayerActivity.kt) | ExoPlayer with HLS/DASH/RTSP support, custom controls, D-pad navigation, content sniffing, progress saving |
| Subtitle Manager | [SubtitleManager.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/player/SubtitleManager.kt) | External subtitle support (SRT/VTT parsing, download, timed sync with player position) |
| Track Selection | [TrackSelectionDialog.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/player/TrackSelectionDialog.kt) | Compose TV dialog for selecting audio, video, and subtitle tracks (embedded + external) |
| Protocol & Commands | [Message.kt](file:///Users/atulmehla/repos/personal/PlayBridge/protocol/src/main/java/com/playbridge/protocol/Message.kt) | Shared protocol: sealed `Command` class, message parsing, JSON helpers (in `protocol` module) |
| Ad Blocker | [AdBlocker.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/browser/AdBlocker.kt) | WebView request interception with domain-aware filter list parsing |
| QR Generator | [QRGenerator.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/pairing/QRGenerator.kt) | ZXing-based QR code generation for pairing (includes IP, port, token, name) |
| Settings | [SettingsScreen.kt](file:///Users/atulmehla/repos/personal/PlayBridge/tv/app/src/main/java/com/playbridge/receiver/ui/SettingsScreen.kt) | TV app settings UI |

### Dependencies
- **Ktor** v3.0 (Netty) — WebSocket server
- **Media3 ExoPlayer** v1.5 — Full streaming suite (HLS, DASH, RTSP, Smooth Streaming)
- **Media3 Session** — Media session support
- **Media3 DataSource OkHttp** — HTTP performance with OkHttp backend
- **ZXing** v3.5 — QR code generation
- **Jetpack Compose TV** — TV-optimized UI (tv-foundation, tv-material)
- **Coil** v3.3 — Image loading (with OkHttp network backend)
- **OkHttp** v4.12 — HTTP client for ExoPlayer data source + URL connections
- **Kotlin Serialization** v1.7 — JSON protocol
- **DataStore** v1.1 — Preferences persistence

---

## Protocol Module

The `protocol` module (`com.playbridge.protocol`) is a shared Kotlin JVM library consumed by both apps via `implementation(project(":protocol"))`. It contains:

| File | Contents |
|------|----------|
| [NsdConstants.kt](file:///Users/atulmehla/repos/personal/PlayBridge/protocol/src/main/java/com/playbridge/protocol/NsdConstants.kt) | NSD service type and key constants |
| [Message.kt](file:///Users/atulmehla/repos/personal/PlayBridge/protocol/src/main/java/com/playbridge/protocol/Message.kt) | All shared protocol classes, sealed `Command` class, `parseCommand()`, and 14 helper functions |

**Dependencies:** Kotlin JVM, `kotlinx-serialization-json:1.7.3`

---

## Communication Protocol

Commands flow bidirectionally between Phone ↔ TV via WebSocket JSON messages:

### Connection & Authentication Flow

```mermaid
sequenceDiagram
    participant Phone
    participant TV
    
    Phone->>TV: WebSocket connect to ws://ip:8765
    Phone->>TV: {"type": "auth", "pin": "1234"} or {"type": "auth", "token": "..."}
    TV->>Phone: {"type": "auth_response", "success": true, "token": "..."}
    Note over Phone,TV: Authenticated session established
    Phone->>TV: Commands...
    TV->>Phone: Status updates...
```

### Phone → TV Commands

```json
// Play video (with optional headers, content type, and external subtitles)
{"type": "command", "action": "play", "payload": {"url": "...", "title": "...", "headers": {...}, "contentType": "...", "subtitles": ["url1.srt", "url2.vtt"]}}

// Open browser on TV
{"type": "command", "action": "browser", "payload": {"url": "..."}}

// Player control
{"type": "command", "action": "control", "payload": {"command": "pause"}}

// Remote control (D-pad navigation)
{"type": "command", "action": "remote", "payload": {"key": "dpad_up"}}

// Mouse/touchpad control
{"type": "command", "action": "mouse", "payload": {"event": "move", "dx": 10.5, "dy": -3.2}}

// Browser control (refresh, toggle extensions)
{"type": "command", "action": "browser_control", "payload": {"action": "refresh"}}

// Context query (ask TV what screen it's on)
{"type": "command", "action": "context_query"}

// Heartbeat
{"type": "ping"}
```

### TV → Phone Responses

```json
// Authentication response
{"type": "auth_response", "success": true, "token": "generated-token"}

// Playback status
{"type": "status", "state": "playing", "position": 12345, "duration": 60000, "title": "..."}

// Context response
{"type": "context", "active": "player"}  // "player", "browser", or "idle"

// Heartbeat
{"type": "pong"}
```

---

## Issues & Refactoring Recommendations

### ✅ Resolved Issues

#### ~~1. Duplicated Protocol Code~~ ✅ RESOLVED
- **Resolved**: All shared protocol code has been migrated to `protocol/src/main/java/com/playbridge/protocol/Message.kt`. Phone `model/Message.kt` now only contains `QRCodeData`. TV `model/Message.kt` has been deleted.

### 🔴 Critical Issues

#### 2. Very Large File: BrowserActivity.kt (~1555 lines)
- **Problem**: Single file handles tabs, extensions, video detection, context menus, downloading, find on page, history, settings navigation, toolbar integration, and Compose UI
- **Impact**: Hard to test, maintain, and extend
- **Note**: Some extraction has been done (`FindOnPageBar.kt`, `BrowserToolbar.kt`, `DownloadsScreen.kt`, `HistoryScreen.kt`, `SettingsScreen.kt`, etc.), but `BrowserActivity.kt` continues to grow
- **Recommendation**: Extract tab lifecycle, download handling, and extension management into separate manager classes

#### 3. Large File: PlayerActivity.kt (~1125 lines)
- **Problem**: Handles player initialization, SSL bypass, content sniffing, playback, controls, D-pad navigation, seek, track selection, subtitle management, progress saving, and bitmap capture
- **Recommendation**: Extract control handling, content sniffing, and progress management into separate classes

### 🟡 Moderate Issues

#### 4. Unsafe SSL in PlayerActivity
- **Problem**: `getUnsafeOkHttpClient()` trusts all certificates
- **Impact**: Security vulnerability for MITM attacks
- **Recommendation**: Make this optional/configurable with clear warnings

#### 5. Hardcoded Values
- Port `8765` hardcoded across both apps
- Retry counts (60), delays (5s) embedded in code
- **Recommendation**: Move to a `config` object or DataStore preferences

#### 6. Missing Error Handling in Extensions
- Browser extension silently catches errors in [background.js:76-89](file:///Users/atulmehla/repos/personal/PlayBridge/phone/app/src/main/assets/extensions/video_detector/background.js#L76) (3 separate silent catches)
- **Recommendation**: Add proper error logging/reporting

### 🟢 Minor Improvements

#### 7. Components.kt is Not True DI
- Uses lazy singletons, not proper dependency injection
- **Recommendation**: Consider Hilt/Koin for testability, or keep as-is if testing isn't a priority

#### 8. ProGuard Rules Minimal
- Default ProGuard rules may strip needed Kotlin serialization classes
- **Recommendation**: Add rules for kotlinx.serialization, Ktor, etc.

---

## Open-Source Preparation Checklist

### ✅ Already Good
- [x] `.gitignore` properly configured (34 entries covering build, IDE, keystore, OS files)
- [x] GitHub Actions CI exists ([android_build.yml](file:///Users/atulmehla/repos/personal/PlayBridge/.github/workflows/android_build.yml))
- [x] Clean package structure with clear separation
- [x] Well-documented protocol messages with KDoc
- [x] Sealed class pattern for type-safe command handling (shared protocol module)
- [x] Unified protocol module — single source of truth for message classes
- [x] Context-aware remote control (phone queries TV for active screen)
- [x] Authentication implemented (Token/PIN validation via QR code pairing)
- [x] README.md created
- [x] LICENSE file added
- [x] Room database for browsing history (phone)
- [x] Subtitle support (SRT/VTT) with external URLs

### ❌ Missing for Open-Source

#### 1. CONTRIBUTING.md
Guidelines for:
- Code style
- Pull request process
- Issue templates

#### 2. Security Considerations Documentation
Document:
- Local network assumption
- SSL bypass option and when to use it

#### 3. Remove/Review Sensitive Data
- Check `local.properties` is gitignored ✅
- Remove any hardcoded API keys or tokens
- Review commit history for accidentally committed secrets

#### 4. Build Configuration
- Both apps have `isMinifyEnabled = false` for release
- Consider enabling for production releases with proper ProGuard rules

---

## Suggested Project Structure (Refactored)

```
PlayBridge/
├── README.md
├── LICENSE
├── CONTRIBUTING.md              # NEW
├── .github/
│   ├── workflows/
│   │   └── android_build.yml
│   └── ISSUE_TEMPLATE/          # NEW
├── protocol/                    # Shared module
│   ├── build.gradle.kts
│   └── src/main/java/com/playbridge/protocol/
│       ├── NsdConstants.kt
│       └── Message.kt           # Unified protocol messages + sealed Command class
├── phone/
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/playbridge/sender/
│   │       │   ├── browser/
│   │       │   │   ├── BrowserActivity.kt    (slimmed down)
│   │       │   │   ├── BrowserToolbar.kt
│   │       │   │   ├── TabsScreen.kt
│   │       │   │   ├── ExtensionsScreen.kt
│   │       │   │   ├── RemoteControlSheet.kt
│   │       │   │   ├── DownloadsScreen.kt
│   │       │   │   ├── HistoryScreen.kt
│   │       │   │   ├── SettingsScreen.kt
│   │       │   │   ├── HlsParser.kt
│   │       │   │   └── ...
│   │       │   ├── connection/
│   │       │   ├── data/history/
│   │       │   ├── model/
│   │       │   └── ui/
│   │       └── assets/extensions/
│   └── build.gradle.kts
└── tv/
    ├── app/
    │   └── src/main/
    │       ├── java/com/playbridge/receiver/
    │       │   ├── browser/
    │       │   ├── pairing/
    │       │   ├── player/
    │       │   │   ├── PlayerActivity.kt   (slimmed down)
    │       │   │   ├── SubtitleManager.kt
    │       │   │   └── TrackSelectionDialog.kt
    │       │   ├── server/
    │       │   ├── ui/
    │       │   └── model/
    │       └── res/layout/
    └── build.gradle.kts
```

---

## Priority Recommendations

| Priority | Task | Effort |
|----------|------|--------|
| 🔴 High | Add CONTRIBUTING.md | 30 minutes |
| ~~🟡 Medium~~ | ~~Migrate messages to shared protocol module~~ | ✅ Done |
| 🟡 Medium | Further slim BrowserActivity.kt (~1555 lines) | 2-4 hours |
| 🟡 Medium | Extract PlayerActivity.kt logic (~1125 lines) | 2-4 hours |
| 🟢 Low | Enable ProGuard for release | 2-4 hours |

---

## Summary

**Strengths:**
- Clean architecture with clear separation between sender/receiver
- Modern tech stack (Compose, Kotlin Serialization, Coroutines, GeckoView v147, Media3 v1.5)
- Well-designed protocol with extensible command structure (play, browser, control, remote, mouse, browser_control, context_query)
- Good use of sealed classes for type-safe command handling
- Feature-rich phone app with remote control, touchpad, HLS quality parsing, extension management, tab management, download support (standard + HLS), browsing history (Room DB), and settings
- TV app has ad blocking, subtitle support (SRT/VTT), track selection dialog, context broadcasting, settings, and foreground service architecture
- Authentication fully implemented with PIN + token flow via QR code pairing
- NSD auto-discovery for seamless phone-to-TV connection

**Key Actions Before Open-Sourcing:**
1. Add CONTRIBUTING.md
2. ~~Expand .gitignore~~ ✅
3. ~~Extract shared protocol module~~ ✅
4. Document security considerations (SSL bypass, local network assumptions)

The codebase is in good shape for open-sourcing with relatively minor documentation additions.
