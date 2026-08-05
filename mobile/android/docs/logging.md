# Android phone — logcat tracking

Recipes for live-tailing PlayBridge phone (`mobile/android`) features via `adb logcat`.

## Prerequisites

| Requirement | Notes |
|---|---|
| **Debug APK** | Many detection / proxy / network dumps are gated on `BuildConfig.DEBUG`. Release builds stay quiet on those paths. |
| **zsh** | Always quote `*:S` — unquoted, zsh treats it as a glob (`no matches found: *:S`). |
| **Line buffering** | Use `grep --line-buffered` so lines appear as they arrive through a pipe. |
| **Fresh detections** | `adb logcat -c` clears the log buffer only. In-app state (e.g. already-detected media) can still show in the UI without new log lines. Hard-reload or open a new tab after starting the tail. |

### Shell template

```bash
# Clear buffer, then filter one or more tags; silence everything else with '*:S'
adb logcat -c && adb logcat -v time 'TAG1:D' 'TAG2:D' '*:S' | grep --line-buffered -E 'pattern'
```

Multi-device:

```bash
adb devices
adb -s <serial> logcat -c && adb -s <serial> logcat -v time 'VideoDetector:D' '*:S'
```

Save while watching:

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | tee /tmp/playbridge-detect.log
```

---

## Tag map (high-signal)

| Feature | Log tag(s) | Debug-only payloads? |
|---|---|---|
| Media detection (ingest) | `VideoDetector` | Yes (`debugLog` / pretty JSON) |
| Detector native bridge | `Components` | Yes (full native/port messages) |
| Stream route / Via phone | `StreamRouteService` | Header-name dumps yes |
| Embedded Rust proxy / sender services | `PhoneSenderServices`, `SenderServicesNative`, `JniUpstreamHttp` | Mixed |
| Local proxy (DLNA / fallback) | `LocalProxyServer` | Mixed |
| Google Cast | `GoogleCastTarget`, `RustCastSession`, `RustCastSessionNative` | Proxied URL log yes |
| Cast session orchestration | `CastSessionManager`, `CastSessionService` | Mixed |
| DLNA / UPnP | `AvTransportClient`, `RenderingControl`, `DeviceDescription` | Mixed |
| Roku | `RokuClient` | Mixed |
| Browser cast / receiver host | `BrowserCastTarget`, `BrowserReceiverHostSvc`, `BrowserReceiverRepo` | Mixed |
| TV WebSocket / discovery | `WebSocketClient`, `ConnectionViewModel`, `ConnectionCoordinator`, `RustReceiverDiscovery`, `NetworkStatus` | Mixed |
| Queue / progress | `TvQueueCoordinator`, `ExternalQueueCoordinator`, `ProgressTracker` | Mixed |
| On-device player | `PB_PLAYER`, `PlayerActivity` (and related) | Mixed |
| Media session / playback service | `MediaPlaybackService`, `MediaSessionObserver` | Mixed |
| Downloads | `playbridge-download`, `DownloadWorker`, `FileStrategy`, `HlsStrategy`, `SegmentDownloader` | Mixed |
| Library / addons / debrid | `AddonRepository`, `TmdbRepository`, `TorBoxClient`, `NuvioRepository`, `NuvioScraperRunner` | Mixed |
| Backup | `BackupManager`, `BackupTrigger` | Mixed |
| Debug HTTP dumps | `PBDebugNetworkRaw` | Yes |
| App process bootstrap | `PlayBridgeApplication` | Mixed |
| Browser chrome / sessions | `BrowserActivity`, `TabManager`, `SessionObserver` | Mixed |

Related behaviour guide: [`docs/android-video-detection.md`](../../../docs/android-video-detection.md).

---

## Video / audio / image detection

Tags: `VideoDetector` (+ optional `Components` for the native bridge).

### All detection activity (recommended starter)

Pretty JSON bodies are **multi-line** under `Received message … — payload:` (indent lines follow). Summary lines use `MEDIA DETECTED` / `Updating detection`.

If you still see a single line like `Received message for tab …: {"detectedBy":…}`, the installed APK is older than the pretty-print change — reinstall a **debug** build.

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'Received message|MEDIA DETECTED|Updating detection|Ignoring media|"mediaKind"|"url"|"detectedBy"'
```

### Full pretty detector dump (no grep)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S'
```

### New detections only (any kind)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered 'MEDIA DETECTED'
```

### Video only

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'MEDIA DETECTED.*kind=(video|VIDEO)'
```

### Audio only

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'MEDIA DETECTED.*kind=(audio|AUDIO)'
```

### Image only

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'MEDIA DETECTED.*kind=(image|IMAGE)'
```

### Video + image (exclude audio chrome/SFX)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'MEDIA DETECTED.*kind=(video|VIDEO|image|IMAGE)'
```

### Pretty JSON field peek (multi-line payloads)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' '*:S' | grep --line-buffered -E 'Received message|MEDIA DETECTED|Updating detection|"mediaKind"|"url"|"detectedBy"|"contentType"|"headers"|"hlsRole"|"playlistBody"|"originUrl"'
```

### Bridge + detector (messages entering Kotlin)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' 'Components:D' '*:S' | grep --line-buffered -iE 'media detected|updating detection|received message|processing detector|message sent to videodetector|native message|video_detected|same-document|detector navigation|stale detector'
```

### SPA / navigation lifecycle (clear vs re-rank)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' 'Components:D' '*:S' | grep --line-buffered -iE 'same-document|detector navigation|clearing videos|lifecycle|stale detector|native_replay'
```

### Manifest / quality probe (HLS·DASH)

```bash
adb logcat -c && adb logcat -v time 'VideoDetector:D' 'HlsParser:D' '*:S' | grep --line-buffered -iE 'qualities|hls|dash|playlist|probe|file size|subtitle preview|thumbnail'
```

Example summary line:

```text
D/VideoDetector: MEDIA DETECTED tab=… kind=video by=content_type type=video/mp4 headers=7 lifecycle=0 url=https://…
```

`kind=` is usually lowercase from the extension (`video` / `audio` / `image`), or the enum name (`VIDEO` / …) when `mediaKind` is absent — greps above match both.

---

## Cast routing & phone proxy

```bash
# Route packaging (Direct / Via phone / Via proxy) + header names (debug)
adb logcat -c && adb logcat -v time 'StreamRouteService:D' 'StreamRouteService:I' 'StreamRouteService:W' 'StreamRouteService:E' '*:S'

# Embedded sender services / Rust proxy registration
adb logcat -c && adb logcat -v time 'PhoneSenderServices:D' 'SenderServicesNative:D' 'JniUpstreamHttp:D' '*:S'

# Local proxy (DLNA / legacy Via phone fallback)
adb logcat -c && adb logcat -v time 'LocalProxyServer:D' 'LocalProxyServer:I' 'LocalProxyServer:W' '*:S'

# HLS segment format hints for Cast
adb logcat -c && adb logcat -v time 'HlsSegmentHints:D' 'StreamRouteService:D' '*:S'
```

Via-phone header diagnostics (debug only):

```bash
adb logcat -c && adb logcat -v time 'StreamRouteService:D' '*:S' | grep --line-buffered -iE 'via phone|raw captured headers|registerUrl|upstream headers'
```

---

## Google Cast

```bash
adb logcat -c && adb logcat -v time \
  'GoogleCastTarget:D' 'GoogleCastTarget:I' 'GoogleCastTarget:W' 'GoogleCastTarget:E' \
  'RustCastSession:D' 'RustCastSession:I' 'RustCastSession:W' 'RustCastSession:E' \
  'RustCastSessionNative:D' \
  '*:S'
```

Proxied media load line (debug only; may include full proxy URL):

```bash
adb logcat -c && adb logcat -v time 'GoogleCastTarget:D' '*:S' | grep --line-buffered -iE 'proxied Google Cast media|LOAD |source='
```

---

## Cast session manager (all targets)

```bash
adb logcat -c && adb logcat -v time \
  'CastSessionManager:D' 'CastSessionManager:I' 'CastSessionManager:W' 'CastSessionManager:E' \
  'CastSessionService:D' 'CastSessionService:I' \
  '*:S'
```

---

## DLNA / Roku / browser cast

```bash
# DLNA
adb logcat -c && adb logcat -v time \
  'AvTransportClient:D' 'RenderingControl:D' 'DeviceDescription:D' 'LocalProxyServer:D' \
  '*:S'

# Roku
adb logcat -c && adb logcat -v time 'RokuClient:D' 'RokuClient:I' 'RokuClient:W' 'RokuClient:E' '*:S'

# Browser cast + hosted receiver
adb logcat -c && adb logcat -v time \
  'BrowserCastTarget:D' 'BrowserReceiverHostSvc:D' 'BrowserReceiverRepo:D' \
  '*:S'
```

---

## TV connection, discovery, WebSocket

```bash
adb logcat -c && adb logcat -v time \
  'WebSocketClient:D' 'WebSocketClient:I' 'WebSocketClient:W' 'WebSocketClient:E' \
  'ConnectionViewModel:D' 'ConnectionViewModel:I' \
  'ConnectionCoordinator:D' \
  'RustReceiverDiscovery:D' 'RustReceiverDiscovery:I' \
  'NetworkStatus:D' \
  '*:S'
```

Connect / auto-connect focus:

```bash
adb logcat -c && adb logcat -v time 'ConnectionViewModel:D' 'ConnectionViewModel:I' 'WebSocketClient:D' 'WebSocketClient:I' '*:S' \
  | grep --line-buffered -iE 'connect|pair|wss|pin|disconnect|auto-connect|saved TV'
```

---

## Episode queue & watch progress

```bash
adb logcat -c && adb logcat -v time \
  'TvQueueCoordinator:D' 'ExternalQueueCoordinator:D' 'ProgressTracker:I' 'ProgressTracker:D' \
  '*:S'
```

---

## On-device player

```bash
adb logcat -c && adb logcat -v time 'PB_PLAYER:D' 'PB_PLAYER:I' 'PB_PLAYER:W' 'PB_PLAYER:E' '*:S'
```

---

## Downloads

```bash
adb logcat -c && adb logcat -v time \
  'playbridge-download:D' 'DownloadWorker:D' 'FileStrategy:D' 'HlsStrategy:D' 'SegmentDownloader:D' \
  '*:S'
```

---

## Library, addons, debrid, Nuvio

```bash
adb logcat -c && adb logcat -v time \
  'AddonRepository:D' 'TmdbRepository:D' 'TorBoxClient:D' \
  'NuvioRepository:D' 'NuvioScraperRunner:D' \
  '*:S'
```

---

## Backup

```bash
adb logcat -c && adb logcat -v time 'BackupManager:D' 'BackupTrigger:D' 'BackupManager:I' 'BackupTrigger:I' '*:S'
```

---

## Debug network logger

When code paths call `DebugNetworkLogger` (debug builds only):

```bash
adb logcat -c && adb logcat -v time 'PBDebugNetworkRaw:D' '*:S'
```

---

## “Cast something from the browser” end-to-end

Useful when a cast fails after detection:

```bash
adb logcat -c && adb logcat -v time \
  'VideoDetector:D' 'Components:D' \
  'StreamRouteService:D' 'StreamRouteService:I' \
  'PhoneSenderServices:D' 'JniUpstreamHttp:D' 'LocalProxyServer:D' \
  'GoogleCastTarget:D' 'GoogleCastTarget:I' \
  'RustCastSession:D' 'RustCastSession:I' \
  'CastSessionManager:D' 'CastSessionManager:I' \
  '*:S' \
  | grep --line-buffered -iE 'media detected|updating detection|via phone|proxied google cast|LOAD |registerUrl|error|fail|exception'
```

---

## App-wide catch (noisy)

```bash
adb logcat -c && adb logcat -v time | grep --line-buffered -i playbridge
```

Or by process (PID changes every launch — read it from any line after starting the app):

```bash
adb logcat -c && adb logcat -v time --pid="$(adb shell pidof -s com.playbridge.sender)"
```

Package id may differ by flavor (`foss` / `play`); confirm with:

```bash
adb shell pm list packages | grep -i playbridge
```

---

## In-app logs screen

The phone app also exposes a diagnostics / logcat reader UI (`LogsScreen` / `LogcatReader`) for on-device viewing. Prefer `adb` when you need piping, grepping, or saving files on the host.

---

## Rules of thumb

1. Prefer **tag filters** (`'VideoDetector:D' '*:S'`) over grepping the entire buffer — less noise, less dropped lines.
2. Quote every `*:S` and tag that contains shell metacharacters.
3. Detection and many cast diagnostics need a **debug** install.
4. Never paste release logs that contain authenticated stream URLs or secret header **values** into public channels; debug tails can include full URLs by design.
5. After changing detector TS under `extension/`, rebuild with `pnpm build` and reinstall so Android assets match.
