# Shared Module Architecture

The `shared/` module is a Kotlin Multiplatform (KMP) library that serves as the central core of the PlayBridge ecosystem. It contains the protocol definitions, player engines, and business logic shared across the Phone (Android), TV (Android), and Apple TV (tvOS) applications.

## Package Structure
```
shared/src/
├── androidMain/           (Android-specific player engines: ExoPlayer, LibVLC, MPV)
│   ├── assets/
│   │   └── shaders/
│   │       ├── color_matrix_fragment.glsl
│   │       └── color_matrix_vertex.glsl
│   └── kotlin/
│       └── com/
│           └── playbridge/
│               └── shared/
│                   ├── SharedContext.kt
│                   ├── io/                (Cloud backup, S3 clients, file utilities)
│                   │   ├── FileSystem.kt
│                   │   └── Paths.android.kt
│                   ├── logging/           (Unified logging interface)
│                   │   └── Logger.android.kt
│                   ├── network/           (Shared Ktor HTTP clients)
│                   │   └── SharedHttpClient.android.kt
│                   └── player/            (Shared player ViewModels, M3U parser, playback settings)
│                       ├── AndroidBufferConfig.kt
│                       ├── ColorMatrixEffect.kt
│                       ├── ExoPlayerEngine.kt
│                       ├── MpvPlayerEngine.kt
│                       ├── VideoFilterExtensions.kt
│                       ├── VideoFilterManager.kt
│                       └── VlcPlayerEngine.kt
├── appleMain/             (Apple-specific player engines: AVPlayer, LibVLC)
│   └── kotlin/
│       └── com/
│           └── playbridge/
│               └── shared/
│                   ├── io/                (Cloud backup, S3 clients, file utilities)
│                   │   ├── FileSystem.kt
│                   │   └── Paths.apple.kt
│                   ├── logging/           (Unified logging interface)
│                   │   └── Logger.apple.kt
│                   ├── network/           (Shared Ktor HTTP clients)
│                   │   └── SharedHttpClient.apple.kt
│                   └── player/            (Shared player ViewModels, M3U parser, playback settings)
│                       └── AVPlayerEngine.kt
├── commonMain/
│   └── kotlin/
│       └── com/
│           └── playbridge/
│               └── shared/
│                   ├── io/                (Cloud backup, S3 clients, file utilities)
│                   │   ├── FileSystem.kt
│                   │   └── Paths.kt
│                   ├── logging/           (Unified logging interface)
│                   │   └── Logger.kt
│                   ├── network/           (Shared Ktor HTTP clients)
│                   │   └── SharedHttpClient.kt
│                   ├── player/            (Shared player ViewModels, M3U parser, playback settings)
│                   │   ├── M3uParser.kt
│                   │   ├── PlaybackEngine.kt
│                   │   ├── PlaybackSettings.kt
│                   │   ├── PlayerUiState.kt
│                   │   └── PlayerViewModel.kt
│                   ├── protocol/          (Message classes, JSON serialization, Binary protocol)
│                   │   ├── BinaryProtocol.kt
│                   │   ├── BluetoothConstants.kt
│                   │   ├── Config.kt
│                   │   ├── Message.kt
│                   │   └── NsdConstants.kt
│                   ├── resume/            (Playback resume logic)
│                   │   └── ResumeStore.kt
│                   └── stremio/           (Addon resolution, quality ranking, series navigation)
│                       ├── QualityRanker.kt
│                       └── SourceTypeRanker.kt
├── commonTest/
│   └── kotlin/
│       └── com/
│           └── playbridge/
│               └── shared/
│                   └── player/
│                       ├── PlaybackEngineTest.kt
│                       ├── PlayerViewModelTest.kt
│                       └── TestDoubles.kt
├── nativeInterop/
│   └── cinterop/
│       └── TVVLCKit.def
└── tvosMain/              (tvOS-specific player engines and native interop)
    └── kotlin/
        └── com/
            └── playbridge/
                └── shared/
                    └── player/
                        └── TVVLCKitEngine.kt
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
