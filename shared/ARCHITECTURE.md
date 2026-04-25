# Shared Module Architecture

The `shared/` module is a Kotlin Multiplatform (KMP) library that serves as the central core of the PlayBridge ecosystem. It contains the protocol definitions, player engines, and business logic shared across the Phone (Android), TV (Android), and Apple TV (tvOS) applications.

## Package Structure
```
shared/src/
├── commonMain/kotlin/com/playbridge/shared/
│   ├── io/                (Cloud backup, S3 clients, file utilities)
│   ├── logging/           (Unified logging interface)
│   ├── network/           (Shared Ktor HTTP clients)
│   ├── player/            (Shared player ViewModels, M3U parser, playback settings)
│   ├── protocol/          (Message classes, JSON serialization, Binary protocol)
│   ├── resume/            (Playback resume logic)
│   └── stremio/           (Addon resolution, quality ranking, series navigation)
├── androidMain/           (Android-specific player engines: ExoPlayer, LibVLC, MPV)
├── appleMain/             (Apple-specific player engines: AVPlayer, LibVLC)
└── tvosMain/              (tvOS-specific player engines and native interop)
```

## Key Components

| Component | Purpose |
|-----------|---------|
| **Protocol** | Single source of truth for all communication messages (`Message`, `Command`) using Kotlin Serialization. |
| **Binary Protocol** | High-performance 9-byte packet structure for low-latency mouse/cursor control. |
| **Player ViewModels** | Shared playback logic that translates protocol commands into engine actions. |
| **Stremio Logic** | Unified resolution of Stremio addons, source ranking, and automatic series progression. |
| **Player Engines** | Abstracted interaction with platform-specific video players (Media3 on Android, AVFoundation on Apple). |
| **Cloud Backup** | Shared S3-compatible logic for synchronizing settings and history across devices. |

## Supported Platforms
- **Android**: Phone and TV modules.
- **Apple (tvOS)**: Native Apple TV application.
- **iOS**: (Future compatibility for iPhone sender).

## Dependencies
- **Kotlin Serialization** v1.7.3 — Shared JSON protocol.
- **Ktor** v3.0.3 — Multiplatform HTTP client.
- **Coroutines** v1.9.0 — Async logic management.
- **Multiplatform Settings** v1.2.0 — Shared key-value storage.
- **Okio** v3.9.1 — Multiplatform file and buffer management.
