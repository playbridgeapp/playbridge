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
- [x] Add manual connection dialog (IP/port entry)
- [x] Add camera zoom control for QR scanning
- [x] Add network security config for cleartext traffic

---

## Phase 2: GeckoView Browser

### Browser Setup
- [x] Create `BrowserActivity.kt` with GeckoView
- [x] Implement URL bar with keyboard input and navigation
- [x] Create `Components.kt` singleton for engine/store initialization
- [x] Add hamburger menu with back/forward/refresh buttons
- [ ] Create proper `TabManager` using BrowserStore
- [ ] Wire `TabsScreen.kt` to actual store state

### WebExtension Support
- [x] Add Mozilla Android Components dependencies
- [x] Implement AddonManager with AMO provider
- [x] Create `ExtensionsScreen.kt` with install/uninstall
- [x] Set up PromptDelegate for extension permissions
- [ ] Bundle video detection extension in assets

---

## Phase 3: Video Detection WebExtension

### Extension Structure
Create bundled WebExtension at `assets/extensions/video_detector/`:

```
video_detector/
├── manifest.json     # Extension manifest with webRequest permissions
├── background.js     # Request interception and video detection
└── icons/
    └── icon-48.png   # Extension icon
```

### manifest.json
```json
{
  "manifest_version": 2,
  "name": "PlayBridge Video Detector",
  "version": "1.0",
  "description": "Detects video URLs for PlayBridge",
  "background": { "scripts": ["background.js"] },
  "permissions": [
    "<all_urls>",
    "webRequest"
  ],
  "browser_specific_settings": {
    "gecko": { "id": "video-detector@playbridge" }
  }
}
```

### background.js Logic
```javascript
// Listen for HTTP responses with video content-types
browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const contentType = details.responseHeaders.find(
      h => h.name.toLowerCase() === 'content-type'
    );
    if (!contentType) return;
    
    const type = contentType.value.toLowerCase();
    const isVideo = 
      type.includes('video/') ||
      type.includes('mpegurl') ||      // HLS
      type.includes('application/dash') // DASH
    ;
    
    if (isVideo || details.type === 'media') {
      // Send to native app via messaging
      browser.runtime.sendNativeMessage('video-detector', {
        type: 'video_detected',
        url: details.url,
        tabId: details.tabId,
        contentType: type,
        originUrl: details.originUrl
      });
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

// Also detect by URL patterns (for .m3u8, .mpd)
browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    const url = details.url.toLowerCase();
    if (url.includes('.m3u8') || url.includes('.m3u') || 
        url.includes('.mpd') || url.includes('.mp4')) {
      browser.runtime.sendNativeMessage('video-detector', {
        type: 'video_detected',
        url: details.url,
        tabId: details.tabId,
        detectedBy: 'url_pattern'
      });
    }
  },
  { urls: ["<all_urls>"] }
);
```

### Native App Integration
- [ ] Register native messaging host in GeckoRuntime
- [ ] Create `VideoDetector.kt` to receive messages from extension
- [ ] Store detected videos per tab with metadata
- [ ] Show FAB when videos are detected

---

## Phase 4: Video Detection UI

### FAB & Bottom Sheet
- [ ] Create `VideoFAB.kt` showing detected video count
- [ ] Implement `DetectedVideosSheet.kt` bottom sheet
- [ ] Show video URL, type (HLS/MP4/DASH), and page source
- [ ] Add option to send selected video to TV

### Send to TV
- [ ] Send video URL with headers (Referer, User-Agent) to TV
- [ ] Option to send current page URL to TV browser
- [ ] Show confirmation when video sent successfully

---

## Video Detection Patterns

| Content-Type | Format |
|--------------|--------|
| `video/mp4` | MP4 |
| `video/webm` | WebM |
| `video/x-flv` | FLV |
| `application/vnd.apple.mpegurl` | HLS |
| `application/x-mpegurl` | HLS |
| `application/dash+xml` | DASH |

| URL Pattern | Format |
|-------------|--------|
| `*.m3u8` | HLS |
| `*.m3u` | HLS |
| `*.mpd` | DASH |
| `*.mp4` | MP4 |
| `googlevideo.com/videoplayback` | YouTube CDN |

---

## Test Cases

| Test Case | Expected Result |
|-----------|-----------------|
| Fresh install | Shows QR scanner |
| Scan TV QR code | Connects to TV |
| App restart | Auto-connects to TV |
| Browse video site | FAB shows with video count |
| Tap FAB | Shows detected videos list |
| Tap video in list | Sends to TV for playback |
| Browse ad-heavy site | Ads are blocked by uBlock |
