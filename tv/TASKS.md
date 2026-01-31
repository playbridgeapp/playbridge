# PlayBridge TV App - Tasks

Android TV (Leanback) app that receives video URLs from the phone and plays them using embedded MPV.

---

## Phase 1: Core Infrastructure

### Project Setup
- [x] Configure `build.gradle.kts` with Leanback, Ktor, ZXing dependencies
- [x] Set up AndroidManifest with TV launcher intent and permissions

### WebSocket Server
- [x] Implement `WebSocketServer.kt` with Ktor 3.0
- [x] Create `ServerService.kt` foreground service to keep server running
- [x] Define `Message.kt` protocol data classes (command, status, ping/pong)

### Pairing System
- [x] Create `QRGenerator.kt` using ZXing to generate pairing QR bitmap
- [x] Implement `PairingScreen.kt` to display QR code on screen
- [x] Create `PairingStore.kt` with DataStore for storing paired devices
- [x] Implement `PairedDevice.kt` model class
- [ ] Add token validation for secure pairing

### Main UI
- [x] Create `HomeScreen.kt` with connection status
- [x] Update `MainActivity.kt` with navigation and state

---

## Phase 2: TV App Player

### MPV Integration
- [ ] Add libmpv module/dependency to project
- [ ] Create `MPVLib.kt` JNI wrapper for libmpv
- [ ] Copy native libraries to `jniLibs/` directory

### Player UI
- [ ] Create `PlayerActivity.kt` full-screen Leanback player
- [ ] Implement `PlayerControls.kt` D-pad controls overlay
- [ ] Handle play/pause/seek/stop commands from WebSocket
- [ ] Add playback status broadcasting back to phone

### Browser Fallback
- [ ] Create `BrowserActivity.kt` with WebView for non-video URLs
- [ ] Add D-pad scroll navigation support

### Leanback UI
- [ ] Implement `HomeFragment.kt` main browse fragment
- [ ] Create `StatusFragment.kt` showing connection status
- [ ] Add `SettingsFragment.kt` with Leanback preferences
- [ ] Design TV-optimized layouts and drawables

---

## Phase 3: Integration & Polish

### Playback Features
- [ ] Support custom headers (Referer, User-Agent) for HLS/DASH streams
- [ ] Handle various video types (m3u8, mp4, mpd)
- [ ] Implement playlist/queue support

### Connection Management
- [ ] Auto-connect to known paired phones
- [x] Show connection status indicator on home screen
- [ ] Handle reconnection when phone disconnects

### Settings & History
- [ ] Recently played videos history
- [ ] Manage paired devices (remove/rename)
- [ ] Server port configuration

---

## D-Pad Navigation Reference

| Screen | D-Pad Actions |
|--------|---------------|
| **Home** | Up/Down: Navigate rows, Enter: Select |
| **Pairing** | Back: Return home |
| **Player** | Enter: Play/Pause, Left/Right: Seek, Back: Exit |
| **Browser** | Arrow keys: Scroll, Enter: Click, Back: Exit |

---

## Test Cases

| Test Case | Expected Result |
|-----------|-----------------|
| Fresh install | Shows QR code for pairing |
| App restart | Auto-connects to paired phone |
| Receive m3u8 URL | MPV plays HLS stream |
| Receive mp4 URL | MPV plays MP4 file |
| Receive web page URL | WebView opens the page |
| D-pad during playback | Player controls respond |
| Phone disconnects | Returns to waiting state |
