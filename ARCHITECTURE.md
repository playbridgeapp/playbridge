# PlayBridge Architecture Review & Open-Source Recommendations

This document provides a comprehensive architecture review of the PlayBridge project and actionable recommendations before open-sourcing.

---

## Project Overview

**PlayBridge** is a casting solution enabling Android phones to send video URLs and browser control commands to Android TV devices. The project consists of two independent Android applications and a shared protocol module:

| **Phone (Sender)** | `phone/` | Android sender app with GeckoView, Stremio resolution, and remote control |
| **TV (Receiver)** | `tv/` | Android TV receiver (Ktor WebSocket server, Dual-player) and Apple TV (tvOS) receiver |
| **Shared (Core)** | `shared/` | KMP library: Protocol, Stremio logic, shared player engines (Android/Apple/tvOS) |
| **Extension** | `extension/` | Desktop Firefox extension for casting browser video |

---

## Architecture Diagram

```mermaid
graph TB
    subgraph Phone App
        Browser[GeckoView Browser]
        Extension[Video Detector Extension]
        WSClient[WebSocket Client]
        Connection[Connection Screen<br/>NSD Discovery + PIN]
        RemoteControl[Remote Control UI]
        HLS[HLS Parser]
        Addons[Stremio Addons Protocol<br/>Stream/Catalog/Meta/Subs]
        Tracking[Watchlist & Tracking<br/>Room DB]
        Backup[Cloud Backup<br/>S3 Compatible]
    end
    
    subgraph TV App
        WSServer[WebSocket Server]
        Player[ExoPlayer / LibVLC]
        TVBrowser[Dual-Engine Browser<br/>SystemWebView / GeckoView]
        AdBlock[Singleton AdBlocker<br/>EasyList + Cosmetic Filtering]
        Filters[GPU Video Filters<br/>ColorMatrix Presets]
        PlaylistStore[Playlist Store<br/>Episodes / Queue]
        ServerSvc[Server Foreground Service]
    end
    
    Extension --> Browser
    Browser --> WSClient
    Browser --> Addons
    Addons --> Tracking
    Tracking --> Browser
    Connection --> WSClient
    RemoteControl --> WSClient
    WSClient <-->|Play/Playlist/Remote/Mouse/Control| WSServer
    ServerSvc --> WSServer
    WSServer --> Player
    WSServer --> TVBrowser
    WSServer --> PlaylistStore
    Player --> Filters
```

---

## Phone App Architecture
👉 [Phone App Architecture](phone/ARCHITECTURE.md)

---

## TV App Architecture
👉 [Android TV Architecture](tv/ARCHITECTURE.md)
👉 [Apple TV Architecture](tv/apple-tv/ARCHITECTURE.md)

---

## Shared Module Architecture
👉 [Shared Architecture](shared/ARCHITECTURE.md)

---

## Standalone Browser Extension
👉 [Extension Architecture](extension/ARCHITECTURE.md)

---

## Project Structure

```
PlayBridge/
├── phone/               # Android Sender App
├── tv/
│   ├── player/          # Android TV Player App
│   ├── browser/         # Android TV Browser App
│   └── apple-tv/        # Native Apple TV (tvOS) App
├── shared/              # Kotlin Multiplatform Core
├── extension/           # Firefox Desktop Extension
├── scripts/             # Maintenance & automation scripts
├── libs/                # Local libraries (mpv-android, etc.)
└── docs/                # Project documentation
```

---

## Issues & Refactoring Recommendations

### 🔴 Critical Issues (Play Store Blockers)
(None currently identified)

### 🟡 Moderate Issues

#### 5. Missing Error Handling in Extensions
- Browser extension silently catches errors in `background.js`
- **Recommendation**: Add proper error logging/reporting

### 🟢 Minor Improvements

#### 6. Components.kt is Not True DI
- Uses lazy singletons, not proper dependency injection
- **Recommendation**: Consider Hilt/Koin for testability

#### 7. ProGuard Rules Minimal
- Default ProGuard rules may strip needed Kotlin serialization classes
- **Recommendation**: Add rules for kotlinx.serialization, Ktor, etc.

---

## Open-Source Preparation Checklist

### ✅ Already Good
- [x] Network security config scoped to local network only
- [x] Fix SSL bypass for Play Store (scope to private IPs)
- [x] Unified shared module — single source of truth for protocol and shared logic
- [x] PIN + Token authentication for discovery
- [x] GitHub Actions CI for all modules
- [x] Multi-engine support on both Phone and TV
- [x] Cross-platform support (Android, tvOS, Firefox)

### ❌ Missing for Open-Source
- [ ] Review commit history for accidentally committed secrets

### ❌ Missing for Play Store (TV App)
---

## Priority Recommendations

| Priority | Task | Effort |
|----------|------|--------|
| 🟡 Medium | Create & host Privacy Policy | 2-4 hours |
| 🟡 Medium | Fill out Play Console (data safety, content rating, listing) | 2-3 hours |
| 🟢 Low | Enable ProGuard for release | 2-4 hours |

---

## Summary

**Strengths:**
- Clean architecture with clear separation between sender/receiver
- Modern tech stack (Compose, Kotlin Serialization, Coroutines, GeckoView v150, Media3 v1.9)
- Well-designed protocol with extensible command structure (play, browser, control, remote, mouse, browser_control, context_query)
- Good use of sealed classes for type-safe command handling
- Feature-rich phone app with remote control, touchpad, HLS quality parsing, extension management, tab management, download support (standard + HLS), browsing history (Room DB), bookmarks, tab persistence, desktop mode, SSL lock indicator, and native Debrid integration
- TV app has dual-engine browser (SystemWebView/GeckoView) with runtime switching, comprehensive ad blocking (EasyList + cosmetic filtering + popup blocking), video maximize/restore via JS injection, subtitle support (SRT/VTT), track selection dialog, context broadcasting, settings, and foreground service architecture
- AdBlocker is a singleton preloaded at app startup for instant protection when browser opens
- Authentication fully implemented with PIN + token flow via mDNS/NSD pairing
- NSD auto-discovery for seamless phone-to-TV connection

**Key Actions Before Open-Sourcing:**
1. Review commit history for accidentally committed secrets

**Key Actions Before Play Store (TV App):**
1. Remove global cleartext traffic permission
2. Remove unused CAMERA/RECORD_AUDIO permissions
3. Create and host a Privacy Policy
4. Complete Play Console setup (data safety, content rating, store listing)

The codebase is in good shape for open-sourcing with relatively minor documentation additions. Play Store publishing requires addressing several security policy items first — see `tv/ARCHITECTURE.md` for the full readiness checklist.
