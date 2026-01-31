# PlayBridge Phone App - Tasks

Android phone app with GeckoView browser that detects video URLs and sends them to the TV.

---

## Phase 1: Core Infrastructure

### Project Setup
- [x] Configure `build.gradle.kts` with OkHttp, ML Kit, CameraX, DataStore dependencies
- [x] Set up AndroidManifest with necessary permissions (camera, network)

### WebSocket Client
- [x] Implement `WebSocketClient.kt` with OkHttp
- [x] Create `ConnectionStore.kt` with DataStore for TV connection info
- [x] Define `Message.kt` protocol data classes
- [x] Implement `TvDevice.kt` model class

### QR Scanner
- [x] Create `QRScannerScreen.kt` with CameraX + ML Kit
- [x] Parse QR code data (IP, port, token, name)
- [x] Initiate WebSocket connection after scanning
- [x] Store connection info on successful pairing

### Main UI
- [x] Update `MainActivity.kt` with navigation and permission handling
- [x] Create `HomeScreen.kt` with connection status and actions

---

## Phase 2: GeckoView Browser

### Browser Setup
- [ ] Create `BrowserFragment.kt` with GeckoView
- [ ] Implement `BrowserToolbar.kt` (URL bar, back/forward, refresh)
- [ ] Create `GeckoSessionManager.kt` to manage sessions

### Ad Blocking
- [ ] Implement `AdBlocker.kt` using GeckoView Content Blocking API
- [ ] Add EasyList filter rules to `assets/adblock/`
- [ ] Enable tracking protection and ad filtering

### Video Detection
- [ ] Create `VideoDetector.kt` for WebRequest interception
- [ ] Implement `VideoUrlMatcher.kt` with patterns for m3u8, mp4, mpd, etc.
- [ ] Create `DetectedVideo.kt` model class
- [ ] Build WebExtension for request interception

### JavaScript Fallback
- [ ] Create `web_extension/content.js` for DOM scanning
- [ ] Implement MutationObserver for dynamic content
- [ ] Detect video/source/iframe elements with video sources

---

## Phase 3: Video Detection UI

### FAB & Bottom Sheet
- [ ] Create `VideoFAB.kt` floating action button showing video count
- [ ] Implement `DetectedVideosSheet.kt` bottom sheet with video list
- [ ] Show video URL, type (HLS/MP4/DASH), and page source
- [ ] Add option to send selected video to TV

### Connection Status
- [ ] Create `ConnectionStatusView.kt` floating indicator
- [ ] Show connected/disconnected/connecting states
- [ ] Tap to reconnect or view connection details

---

## Phase 4: Integration & Polish

### Send to TV
- [ ] Send video URL with headers (Referer, User-Agent) to TV
- [ ] Option to send current page URL to TV browser
- [ ] Show confirmation when video sent successfully

### Remote Control
- [ ] Create `RemoteFragment.kt` for TV playback control
- [ ] Play/pause/seek buttons
- [ ] Display current playback status from TV

### Navigation & Settings
- [ ] Implement bottom navigation (Browse, Remote, Settings)
- [ ] Create settings screen for connection management
- [ ] Add-block rules configuration
- [ ] History of sent videos

---

## Video Detection Patterns

| Pattern | Type |
|---------|------|
| `*.m3u8` | HLS |
| `*.mpd` | DASH |
| `*.mp4`, `*.mkv`, `*.webm` | Direct |
| `googlevideo.com/videoplayback` | YouTube CDN |
| `akamaihd.net/*.m3u8` | Akamai CDN |
| `cloudfront.net/*.m3u8` | CloudFront CDN |

---

## Test Cases

| Test Case | Expected Result |
|-----------|-----------------|
| Fresh install | Shows QR scanner |
| Scan TV QR code | Connects to TV |
| App restart | Auto-connects to TV |
| Browse video site | Detects m3u8/mp4 URLs |
| Tap video in list | Sends to TV for playback |
| Browse ad-heavy site | Ads are blocked |
| Network change | Attempts reconnection |
| Use remote controls | TV player responds |
