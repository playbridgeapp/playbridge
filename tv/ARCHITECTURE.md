# TV App Architecture

## Package Structure

### Browser App
```
com.playbridge.browser/
├── AdBlocker.kt
├── BrowserActivity.kt
├── BrowserEngine.kt
├── GeckoViewEngine.kt
├── PlayBridgeBrowserApplication.kt
├── SystemWebViewEngine.kt
└── logging
    └── FileLogger.kt
```

### Player App
```
com.playbridge.player/
├── BootReceiver.kt
├── MainActivity.kt
├── PlayBridgeApplication.kt
├── data
│   └── HistoryStore.kt
├── logging
│   └── FileLogger.kt
├── model
│   └── PairedDevice.kt
├── pairing
│   ├── PairingStore.kt
│   └── QRGenerator.kt
├── player
│   ├── BufferSeekBar.kt
│   ├── ColorMatrixEffect.kt
│   ├── ContentSniffer.kt
│   ├── ExoPlayerActivity.kt
│   ├── InputHandler.kt
│   ├── M3uParser.kt
│   ├── MpvControlsManager.kt
│   ├── MpvPlayerActivity.kt
│   ├── MpvTrackSelectionDialog.kt
│   ├── PlayerActivity.kt
│   ├── PlayerControlsManager.kt
│   ├── PlaylistPickerDialog.kt
│   ├── PlaylistStore.kt
│   ├── ProgressManager.kt
│   ├── SubtitleManager.kt
│   ├── TrackSelectionDialog.kt
│   ├── VideoFilter.kt
│   ├── VideoFilterDialog.kt
│   ├── VideoFilterManager.kt
│   ├── VlcControlsManager.kt
│   ├── VlcPlayerActivity.kt
│   └── VlcTrackSelectionDialog.kt
├── server
│   ├── BluetoothServer.kt
│   ├── OverlayWindowHelper.kt
│   ├── ServerService.kt
│   └── WebSocketServer.kt
└── ui
    ├── HomeScreen.kt
    ├── LibraryScreen.kt
    ├── PairingScreen.kt
    ├── SettingsScreen.kt
    └── theme
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| WebSocket Server | WebSocketServer.kt | Ktor Netty server on port 8765 with auth |
| Bluetooth Server | BluetoothServer.kt | Bluetooth RFCOMM socket server fallback |
| Server Service | ServerService.kt | Foreground service managing server lifecycle, command routing, external player intents, NSD registration, context broadcasting |
| Video Player Base | PlayerActivity.kt | Abstract base class for players, playlist queue, auto-advance |
| ExoPlayer | ExoPlayerActivity.kt | ExoPlayer implementation with HLS/DASH/RTSP support |
| LibVLC Player | VlcPlayerActivity.kt | LibVLC implementation for unsupported ExoPlayer formats (AVI, SWF, etc.) |
| Player Controls | PlayerControlsManager.kt | Custom controls overlay, seekbar, play/pause, prev/next episode buttons, filter button, dynamic scrubbing intervals |
| VLC Controls | VlcControlsManager.kt | Custom controls overlay specific to VLC player |
| Input Handler | InputHandler.kt | D-pad, phone remote, control commands |
| Progress Manager | ProgressManager.kt | Playback progress save/restore, thumbnail capture |
| Content Sniffer | ContentSniffer.kt | SSL-bypass OkHttpClient, pre-flight content type detection |
| M3U Parser | M3uParser.kt | Custom parser for IPTV M3U playlists, bypassing ExoPlayer's default HLS parser for compatibility |
| Subtitle Manager | SubtitleManager.kt | External subtitle support (SRT/VTT parsing, download, timed sync with player position) |
| Track Selection | TrackSelectionDialog.kt | Compact side-panel overlay for selecting audio, video, subtitle tracks; shows preferred language auto-selections |
| VLC Track Selection | VlcTrackSelectionDialog.kt | Compact side-panel overlay for selecting VLC audio, video, subtitle tracks |
| Playlist Picker | PlaylistPickerDialog.kt | Compact side-panel overlay listing playlist episodes with current/watched indicators |
| Playlist Store | PlaylistStore.kt | In-memory singleton `currentPlaylist` passed to player activities |
| Video Filters | VideoFilter.kt | 9 preset filters (HDR, Night, Movie, Cinema, Action, Deep Black, Grayscale, Vivid) + Custom with brightness/contrast/saturation |
| Video Filter Manager | VideoFilterManager.kt | Applies ColorMatrix filters to PlayerView hardware layer (GPU-accelerated, zero decode overhead) |
| Video Filter Dialog | VideoFilterDialog.kt | Compact bottom-panel filter picker with live preview on focus, D-pad custom sliders |
| Overlay Window | OverlayWindowHelper.kt | Helper for drawing invisible overlay to keep WebView active in background |
| AdBlocker | AdBlocker.kt | Singleton ad blocker preloaded at app startup; EasyList + EasyPrivacy, cosmetic filtering, popup/redirect blocking |
| GPU Video Filters | VideoFilterManager.kt | Applies live ColorMatrix filters (HDR, Vivid, Action, etc.) directly to the SurfaceView/TextureView without CPU penalties |
| Playlist Picker | PlaylistPickerDialog.kt | Side-wall UI for navigating series episodes and jumping within a live queue |
| M3U Parser | M3uParser.kt | Custom parser for IPTV M3U playlists, bypassing ExoPlayer restrictions |
| Protocol & Commands | Message.kt | Shared protocol: sealed `Command` class, message parsing, JSON helpers (in `protocol` module) |
| Browser Engine Interface | BrowserEngine.kt | Abstraction for swappable browser engines (loadUrl, reload, evaluateJavascript, etc.) |
| SystemWebView Engine | SystemWebViewEngine.kt | Android WebView engine with JS-based popup/redirect blocking, cosmetic CSS injection, ad request interception |
| GeckoView Engine | GeckoViewEngine.kt | GeckoView engine with bundled uBlock Origin for advanced ad blocking |
| Ad Blocker | AdBlocker.kt (~662 lines) | Singleton ad blocker preloaded at app startup; EasyList + EasyPrivacy + Adblock Warning Removal List, cosmetic filtering, popup/document blocking |
| TV Browser | BrowserActivity.kt (~728 lines) | TV browser with dual-engine switching, remote input, fullscreen handling, JS-based video maximize/restore, cursor control |
| QR Generator | QRGenerator.kt | ZXing-based QR code generation for pairing (includes IP, port, token, name) |
| Settings | SettingsScreen.kt | TV app settings UI (including external player selection and dynamically reading packageManager info for version) |
| File Logger | FileLogger.kt | Mirrored Android Log that persists entries to a rolling file in internal storage |
| Color Matrix | ColorMatrixEffect.kt | Media3 GlEffect applying custom ColorMatrix for filters via GLSL |

## Dependencies
- **Ktor** v3.0.3 (Netty) — WebSocket server
- **Media3 ExoPlayer** v1.9.2 — Full streaming suite (HLS, DASH, RTSP, Smooth Streaming)
- **Media3 Session** — Media session support
- **Media3 DataSource OkHttp** — HTTP performance with OkHttp backend
- **ZXing** v3.5.2 — QR code generation
- **Jetpack Compose TV** — TV-optimized UI (tv-foundation, tv-material)
- **Coil** v3.3.0 — Image loading (with OkHttp network backend)
- **OkHttp** v4.12.0 — HTTP client for ExoPlayer data source + URL connections
- **Kotlin Serialization** v1.7.3 — JSON protocol
- **DataStore** v1.1.1 — Preferences persistence
- **GeckoView** (Mozilla) v147.0.20260105210555 — Alternative browser engine with uBlock Origin
- **LibVLC** v4.0.0-eap24 — Fallback video player for unsupported formats

---

## Play Store Readiness

### 🔴 Critical Issues (Likely to Cause Rejection)

#### 2. Cleartext Traffic Globally Enabled (`network_security_config.xml`)
- **Problem**: `<base-config cleartextTrafficPermitted="true">` allows HTTP on ALL domains (comment says "Remove this in production builds")
- **Impact**: Security review flag
- **Fix**: Remove the `<base-config>` block; keep only `<domain-config>` for local network addresses

#### 3. `SYSTEM_ALERT_WINDOW` Permission
- **Problem**: Used by `OverlayWindowHelper.kt` to work around Android 14+ Background Activity Launch restrictions
- **Impact**: Triggers **manual review** — must provide justification in Play Console
- **Fix**: Keep (legitimately needed), but prepare a clear explanation for Google reviewers

#### 5. Bundled Ad Blocking (uBlock Origin + `AdBlocker.kt`)
- **Problem**: App bundles uBlock Origin extension + custom AdBlocker singleton with EasyList/EasyPrivacy/cosmetic filtering
- **Policy**: Google restricts apps that interfere with other apps. In-app browser blocking is more defensible than system-wide, but may still be flagged
- **Impact**: Gray area — reviewers may flag it, especially since filter lists are downloaded at runtime
- **Mitigation**: Ensure blocking is scoped only to own WebView/GeckoView; don't advertise "ad blocking" as primary feature in store listing; consider making opt-in

### 🟡 Moderate Issues

| Issue | Details |
|---|---|
| **No test directory** | No tests exist under `tv/app/src/test/` or `tv/app/src/androidTest/` |
| **No crash reporting** | No Firebase Crashlytics or equivalent. Hard to debug post-launch issues |
| **`largeHeap="true"`** | May cause ANR issues on low-memory TV devices |
| **`isMinifyEnabled = false`** | No R8/ProGuard minification for release builds. App size is large due to GeckoView |
| **ProGuard rules empty** | Default template only — needs rules for kotlinx.serialization, Ktor, protocol classes if minification is enabled |
| **ABI splits exclude x86** | `armeabi-v7a` and `arm64-v8a` only — Intel-based Android TV devices unsupported |

### ✅ Already Good
- **Unsafe SSL in ContentSniffer (TV App)**

### ✅ Play Store Publishing Checklist

#### Google Play Console (Non-Code)
- [ ] Google Play Developer account ($25 one-time fee)
- [ ] Privacy Policy URL (mandatory for INTERNET, CAMERA, RECORD_AUDIO permissions) — must be a hosted web page
- [ ] Data Safety Section — declare what data is collected/shared (Play Console form)
- [ ] Content Rating — IARC questionnaire in Play Console
- [ ] Target Audience declaration — must declare NOT for children (COPPA/GDPR-K)
- [ ] Store listing: app title, short description, full description
- [ ] Store listing assets: feature graphic (1024×500), 2+ screenshots, icon (512×512)

#### Build Configuration (Code)
- [ ] Switch from APK to AAB (Android App Bundle) — **required for new apps since Aug 2021**
  - Change `android_build.yml`: `assembleRelease` → `bundleRelease`
  - Update artifact path: `app/build/outputs/bundle/release/`
- [ ] Enroll in Play App Signing (upload signing key or let Google manage)
- [ ] Consider enabling R8 minification with proper ProGuard keep rules
