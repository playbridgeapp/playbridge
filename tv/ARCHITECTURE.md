# TV App Architecture

## Package Structure
```
com.playbridge.receiver/
├── BootReceiver.kt                (broadcast receiver for starting the app on boot)
├── MainActivity.kt                # Compose navigation + screen state management + AdBlocker preload (~209 lines)
├── PlayBridgeApplication.kt       (application class for coil image loader initialization)
├── browser/                       # Dual-engine TV browser
│   ├── AdBlocker.kt               (Singleton ad blocker: EasyList, EasyPrivacy, cosmetic filtering, popup blocking, ~649 lines)
│   ├── BrowserActivity.kt         (TV browser activity with remote input, video maximize/restore, ~773 lines)
│   ├── BrowserEngine.kt           (Browser engine interface: loadUrl, reload, goBack, evaluateJavascript, etc.)
│   ├── GeckoViewEngine.kt         (GeckoView engine with bundled uBlock Origin)
│   └── SystemWebViewEngine.kt     (Android WebView engine with JS popup/redirect blocking, cosmetic CSS injection)
├── data/                          # Persistence
│   └── HistoryStore.kt            (DataStore-based playback history)
├── model/                         # App-specific models
│   └── PairedDevice.kt            (paired device info)
├── pairing/                       # QR code display, token management
│   ├── PairingStore.kt            (DataStore persistence for auth tokens)
│   └── QRGenerator.kt             (ZXing QR code bitmap generation)
├── player/                        # Video playback
│   ├── ContentSniffer.kt          (SSL-bypass OkHttpClient + content type sniffing)
│   ├── InputHandler.kt            (D-pad, phone remote, control command handling)
│   ├── PlayerActivity.kt          (~648 lines, ExoPlayer with HLS/DASH/RTSP)
│   ├── PlayerControlsManager.kt   (custom controls overlay, seekbar, scrubbing)
│   ├── ProgressManager.kt         (progress save/restore, thumbnail capture)
│   ├── SubtitleManager.kt         (SRT/VTT subtitle parser + sync engine)
│   └── TrackSelectionDialog.kt    (Compose TV dialog for audio/video/subtitle track selection)
├── server/                        # WebSocket server
│   ├── OverlayWindowHelper.kt     (helper for drawing invisible overlay to keep WebView active in background)
│   ├── ServerService.kt           (foreground service + command routing, ~388 lines)
│   └── WebSocketServer.kt         (Ktor-based WebSocket server)
└── ui/                            # Compose TV UI screens
    ├── HistoryScreen.kt
    ├── HomeScreen.kt
    ├── PairingScreen.kt
    ├── SettingsScreen.kt
    └── theme/
```

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| WebSocket Server | WebSocketServer.kt | Ktor Netty server on port 8765 with auth |
| Server Service | ServerService.kt | Foreground service managing server lifecycle, command routing, NSD registration, context broadcasting |
| Video Player | PlayerActivity.kt | ExoPlayer activity with media source construction, track selection |
| Player Controls | PlayerControlsManager.kt | Custom controls overlay, seekbar, play/pause, scrubbing |
| Input Handler | InputHandler.kt | D-pad, phone remote, control commands |
| Progress Manager | ProgressManager.kt | Playback progress save/restore, thumbnail capture |
| Content Sniffer | ContentSniffer.kt | SSL-bypass OkHttpClient, pre-flight content type detection |
| Subtitle Manager | SubtitleManager.kt | External subtitle support (SRT/VTT parsing, download, timed sync with player position) |
| Track Selection | TrackSelectionDialog.kt | Compose TV dialog for selecting audio, video, and subtitle tracks (embedded + external) |
| Overlay Window | OverlayWindowHelper.kt | Helper for drawing invisible overlay to keep WebView active in background |
| Protocol & Commands | Message.kt | Shared protocol: sealed `Command` class, message parsing, JSON helpers (in `protocol` module) |
| Browser Engine Interface | BrowserEngine.kt | Abstraction for swappable browser engines (loadUrl, reload, evaluateJavascript, etc.) |
| SystemWebView Engine | SystemWebViewEngine.kt | Android WebView engine with JS-based popup/redirect blocking, cosmetic CSS injection, ad request interception |
| GeckoView Engine | GeckoViewEngine.kt | GeckoView engine with bundled uBlock Origin for advanced ad blocking |
| Ad Blocker | AdBlocker.kt | Singleton ad blocker preloaded at app startup; EasyList + EasyPrivacy + Adblock Warning Removal List, cosmetic filtering, popup/document blocking |
| TV Browser | BrowserActivity.kt | TV browser with dual-engine switching, remote input, fullscreen handling, JS-based video maximize/restore, cursor control |
| QR Generator | QRGenerator.kt | ZXing-based QR code generation for pairing (includes IP, port, token, name) |
| Settings | SettingsScreen.kt | TV app settings UI |

## Dependencies
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
