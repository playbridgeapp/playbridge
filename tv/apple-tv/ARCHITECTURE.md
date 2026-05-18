# Apple TV (tvOS) App Architecture

The Apple TV application is a native tvOS receiver built using Swift and SwiftUI. It mirrors the functionality of the Android TV receiver, implementing the PlayBridge protocol to receive and play video streams from the Phone sender or Firefox extension.

## Package Structure
```
tv/apple-tv/PlayBridge TV/PlayBridge TV/
├── PlayBridge_TVApp.swift (App entry point and lifecycle)
├── Network/                (WebSocket server, VLC proxy server, mDNS registration)
├── Player/                 (Native AVPlayer and LibVLC integration, overlays)
│   ├── NativePlayerView.swift
│   ├── VLCPlayerView.swift
│   └── VLCControlsOverlay.swift
├── UI/                     (SwiftUI screens and components)
│   ├── AppScreen.swift
│   ├── Views/              (Home, Settings, Pairing)
│   └── Components/         (Remote indicators, status badges)
└── Data/                   (Local persistence, settings, pairing store)
```

## Key Components

| Component | Purpose |
|-----------|---------|
| **WebSocket Server** | Swift-based server that listens for PlayBridge protocol commands (typically on port 8765). |
| **mDNS (NSD)** | Registers the service as `_playbridge._tcp` for auto-discovery by the phone app. |
| **Native Player** | Uses `AVFoundation` for high-performance HLS and MP4 playback. |
| **VLC Player** | Uses `MobileVLCKit` as a fallback engine for formats not supported by Apple (e.g., MKV, AVI). |
| **VLC Proxy Server** | A local HTTP proxy that handles header injection and stream stabilization for VLC playback. |
| **SwiftUI UI** | Built with Focus Engine support for the Apple TV Siri Remote. |

## Dependencies
- **MobileVLCKit** — Fallback video player for advanced codecs.
- **Starscream** — WebSocket client/server support.
- **Swifter** — Local HTTP proxy server for VLC.
- **PlayBridge Shared (KMP)** — (Future) Intention to integrate the shared logic and protocol directly via XCFramework.

## Communication Flow
1. **NSD Discovery**: Phone finds Apple TV via `_playbridge._tcp`.
2. **WebSocket Connection**: Phone connects and sends a `PIN` for pairing.
3. **Command Processing**: `WebSocketServer.swift` receives JSON commands.
4. **Playback**: App selects the appropriate engine (Native or VLC) based on the URL and starts playback.
