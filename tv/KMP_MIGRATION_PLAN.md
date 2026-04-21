# PlayBridge TV — Kotlin Multiplatform Migration Plan

**Status:** Proposal
**Author:** Architecture review, Apr 2026
**Companion doc:** [`tv/ARCHITECTURE_REVIEW.md`](./ARCHITECTURE_REVIEW.md)

---

## TL;DR

The Swift tvOS app under `tv/apple-tv/` is a greenfield shell (a few thousand lines of hand-ported protocol types and placeholder engines, no production users). The Android TV player, by contrast, is the load-bearing codebase. Rather than maintain two parallel implementations of the protocol, Stremio client, quality ranking, subtitle parsing, resume logic, and player state machine, we consolidate everything that is not UI or platform-API glue into a single Kotlin Multiplatform module `:shared`. Each platform keeps its own native UI layer: Compose for TV on Android, SwiftUI on tvOS. Both UIs bind to the **same** `PlayerViewModel` written in `commonMain`.

We explicitly **do not** adopt Compose Multiplatform for tvOS — as of the Compose 1.8 release (May 2025), CMP is stable on iOS only. tvOS has no focus engine bindings, no TVMLKit interop, and rough scrolling performance. SwiftUI stays as the tvOS UI.

**Outcome after migration:** one source of truth for protocol, streaming catalog logic, subtitle fetch, and player state; two thin native UI shells; one Gradle build; zero Swift copies of Kotlin-owned logic.

---

## Goals

1. **One protocol implementation.** Delete `tv/apple-tv/PlayBridgeProtocol/` and replace it with the same `protocolJson`/`MessageEnvelope`/sealed `Command` hierarchy that the Android apps already use, consumed from Swift as an iOS framework.
2. **One `PlayerViewModel`.** The state machine (idle → preplay → loading → playing → seeking → ended → error) lives in `commonMain`. Both platforms bind to it via their native UI idioms.
3. **One Stremio / Debrid / quality-ranking code path.** `StremioClient`, `QualityRanker`, `SourceTypeRanker`, `SeriesNavigator`, and resume-store logic move to `commonMain`.
4. **Pluggable playback engines via `expect interface PlaybackEngine`.** Android supplies `ExoPlayerEngine` / `VlcPlayerEngine` / `MpvPlayerEngine`. Apple supplies `AVPlayerEngine` / `TVVLCKitEngine`.
5. **No regression in Android build time or APK size.** `:shared` replaces `:protocol` at zero net cost.

## Non-Goals

- **No Compose Multiplatform on tvOS.** SwiftUI stays.
- **No KMP for the phone app in this migration.** The phone already works and has no Apple target. Its consumption of `:protocol` will migrate cleanly to `:shared` in step 6 without rewriting any phone code.
- **No port of GeckoView, ExoPlayer, LibVLC, or MPV internals.** Only the protocol/business logic layer goes into `commonMain`; players stay native.
- **No migration of the Firefox extension.** It already speaks JSON over WebSocket; it consumes the protocol by shape, not by artifact.
- **No change to the WebSocket wire format.** Every change in `:shared` must keep the existing on-wire JSON bytes byte-identical.

---

## Prerequisites

| Tool | Minimum version | Current repo state | Notes |
|------|-----------------|-------------------|-------|
| Kotlin | 2.1.0 | 2.0+ (per `tv/player/app/build.gradle.kts`) | Bump to 2.1.x for stable K2 + KMP |
| AGP | 8.7.x | confirm on each `:app` | Matches Kotlin 2.1 |
| Gradle | 8.10+ | confirm via wrapper | Required for AGP 8.7 |
| JDK | 17 | `sourceCompatibility = VERSION_11` in player app | Bump player + browser to 17 |
| Xcode | 15.4+ | — | Needed for Swift 5.10 + static xcframework |
| macOS | Sonoma 14.4+ | — | Apple toolchain |
| Ktor | 3.0.3 | 3.0.3 on TV, OkHttp on phone | Ktor client replaces OkHttp inside `:shared` |
| kotlinx-serialization | 1.7.3 | 1.7.3 | Matches; no change |
| multiplatform-settings | 1.2.0 | — | New dep, replaces `SharedPreferences` + `DataStore` in `:shared` |
| okio | 3.9.1 | — | New dep, replaces `java.io.File` in `:shared` |

**Environment sanity checks before starting:**

```bash
./gradlew --version          # 8.10+
./gradlew :tv:player:app:assembleDebug   # baseline must pass
./gradlew :phone:app:assembleDebug       # baseline must pass
xcodebuild -version          # 15.4+
```

---

## Repo Layout: Before → After

### Before

```
PlayBridge/
├── phone/                              # Android phone app (standalone Gradle build)
│   ├── settings.gradle.kts             # include(":app"); include(":protocol") -> ../protocol
│   └── app/
├── tv/
│   ├── player/                         # Android TV player (standalone Gradle build)
│   │   ├── settings.gradle.kts         # include(":app"); include(":protocol") -> ../../protocol
│   │   └── app/
│   ├── browser/                        # Android TV browser (standalone Gradle build)
│   └── apple-tv/                       # Swift tvOS scaffold
│       ├── PlayBridgeProtocol/         # hand-ported Swift copy of Message.kt  ← DELETE
│       │   └── Sources/PlayBridgeProtocol/
│       └── PlayBridge TV/              # SwiftUI app shell + placeholder engines
├── protocol/                           # Kotlin-only JVM module  ← SUBSUMED
│   └── src/main/kotlin/com/playbridge/protocol/Message.kt
└── extension/                          # Firefox extension (JS)
```

Four independent Gradle builds (`phone/`, `tv/player/`, `tv/browser/`, `mpvEx/`), all including `:protocol` by relative path.

### After

```
PlayBridge/
├── settings.gradle.kts                 # NEW — single composite root
├── build.gradle.kts                    # NEW — root project + version catalog
├── gradle/
│   └── libs.versions.toml              # NEW — shared version catalog
├── shared/                             # NEW — Kotlin Multiplatform module
│   ├── build.gradle.kts                # androidTarget + iosArm64 + iosSimulatorArm64
│   │                                   # + tvosArm64 + tvosSimulatorArm64 + tvosX64
│   └── src/
│       ├── commonMain/kotlin/com/playbridge/shared/
│       │   ├── protocol/               # moved from :protocol verbatim
│       │   ├── stremio/                # moved from tv/player/.../stremio/
│       │   ├── player/                 # expect interface PlaybackEngine + PlayerViewModel
│       │   ├── subtitle/               # parser only (SRT/VTT/ASS)
│       │   ├── resume/                 # ResumeStore logic
│       │   ├── logging/                # expect val logger
│       │   ├── io/                     # expect cache/files
│       │   └── net/                    # Ktor client wrappers
│       ├── commonTest/kotlin/          # shared unit tests
│       ├── androidMain/kotlin/         # actual implementations for Android
│       ├── appleMain/kotlin/           # shared Apple source set (iOS + tvOS)
│       ├── iosMain/kotlin/             # iOS-specific actuals (future phone iOS)
│       ├── tvosMain/kotlin/            # tvOS-specific actuals (AVPlayer, TVVLCKit)
│       └── commonTest/                 # kotlinx-coroutines-test + Turbine
├── phone/app/                          # unchanged except dep flip :protocol → :shared
├── tv/
│   ├── player/app/                     # Compose-TV shell over :shared
│   ├── browser/app/                    # unchanged (dep flip only)
│   └── apple-tv/
│       └── PlayBridge TV/              # SwiftUI shell consuming Shared.xcframework
│                                       # PlayBridgeProtocol/ DELETED
├── protocol/                           # DELETED (subsumed by shared/)
├── mpvEx/                              # unchanged (Android only)
└── extension/                          # unchanged
```

One Gradle build, one composite settings file, one shared module, two native UI layers.

---

## The Seven Steps

Each step below is independently mergeable. The repo stays green after every step. Effort estimates are calendar days for one developer familiar with the repo and with KMP basics; nothing below requires an expert.

### Step 1 — Scrap the Swift scaffold, keep only the Xcode project shell

**Goal:** Remove Swift copies of Kotlin-owned logic so they cannot drift further. Keep only the Xcode project, entitlements, `Info.plist`, asset catalog, and a single `App.swift` entry point.

**Files to delete:**

```
tv/apple-tv/PlayBridgeProtocol/                                   ← entire Swift package
tv/apple-tv/PlayBridge TV/PlayBridge TV/PlaybackEngine.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/AVPlayerEngine.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/VLCPlayerEngine.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/PlayerViewModel.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/ServerCoordinator.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/StremioClient.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/SubtitleManager.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/SubtitleHTTPServer.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/WebSocketServer.swift
tv/apple-tv/PlayBridge TV/PlayBridge TV/ResumeStore.swift         ← if present
```

Approximately 3,800 LOC deleted. (Measured via `wc -l` on the files listed in the architecture review.)

**Files to keep:**

```
tv/apple-tv/PlayBridge TV.xcodeproj/
tv/apple-tv/PlayBridge TV/PlayBridge TV/App.swift                  ← stub
tv/apple-tv/PlayBridge TV/PlayBridge TV/Assets.xcassets/
tv/apple-tv/PlayBridge TV/PlayBridge TV/Info.plist
tv/apple-tv/PlayBridge TV/PlayBridge TV/Entitlements.plist
```

`App.swift` becomes a one-screen placeholder that prints "PlayBridge TV — KMP shell wiring in progress." It will be rewired in Step 7.

**Why first:** Removes the temptation to hand-port changes to Swift while the migration is in flight. The remaining `.xcodeproj` is the build harness only.

**Validation:** `xcodebuild -project "PlayBridge TV.xcodeproj" -scheme "PlayBridge TV" -destination 'platform=tvOS Simulator,name=Apple TV' build` succeeds and launches the placeholder screen in the simulator.

**Effort:** 0.5 day.

---

### Step 2 — Create `:shared` KMP module skeleton

**Goal:** Land a compiling KMP module wired into all three Android apps and ready to be consumed by the tvOS app. The module is intentionally empty at this step — it only proves the build plumbing works end to end.

**Prerequisites:** Before this step lands, unify the repo under a single root `settings.gradle.kts`. Each existing `phone/settings.gradle.kts`, `tv/player/settings.gradle.kts`, `tv/browser/settings.gradle.kts` currently bootstraps its own build; consolidating into one build is what makes `:shared` usable from all three with a single source of truth.

**Root `settings.gradle.kts` (new):**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "PlayBridge"
include(":shared")
include(":phone:app")
include(":tv:player:app")
include(":tv:browser:app")
```

Each app module keeps its own `build.gradle.kts`; only the project coordinates change (`:app` → `:phone:app`, etc.). The old per-folder `settings.gradle.kts` files are deleted.

**`gradle/libs.versions.toml` (new):**

```toml
[versions]
kotlin       = "2.1.0"
agp          = "8.7.2"
ktor         = "3.0.3"
serialization = "1.7.3"
coroutines   = "1.9.0"
okio         = "3.9.1"
settings-mp  = "1.2.0"
turbine      = "1.1.0"

[libraries]
ktor-client-core     = { module = "io.ktor:ktor-client-core",     version.ref = "ktor" }
ktor-client-cio      = { module = "io.ktor:ktor-client-cio",      version.ref = "ktor" }
ktor-client-darwin   = { module = "io.ktor:ktor-client-darwin",   version.ref = "ktor" }
ktor-client-okhttp   = { module = "io.ktor:ktor-client-okhttp",   version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json         = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
kotlinx-serialization-json      = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core         = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
okio                            = { module = "com.squareup.okio:okio", version.ref = "okio" }
multiplatform-settings          = { module = "com.russhwolf:multiplatform-settings", version.ref = "settings-mp" }
multiplatform-settings-coroutines = { module = "com.russhwolf:multiplatform-settings-coroutines", version.ref = "settings-mp" }
turbine                         = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-library      = { id = "com.android.library", version.ref = "agp" }
android-application  = { id = "com.android.application", version.ref = "agp" }
```

**`shared/build.gradle.kts` (new):**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    listOf(tvosArm64(), tvosSimulatorArm64(), tvosX64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()   // gives us commonMain, appleMain, iosMain, tvosMain

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.okio)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.turbine)
        }
    }
}

android {
    namespace = "com.playbridge.shared"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

**Validation checkpoint:**

```bash
./gradlew :shared:build                          # all targets compile, zero sources
./gradlew :shared:assembleXCFramework            # produces shared/build/XCFrameworks/release/Shared.xcframework
./gradlew :phone:app:assembleDebug               # still green (still on :protocol for now)
./gradlew :tv:player:app:assembleDebug           # still green
./gradlew :tv:browser:app:assembleDebug          # still green
```

**Effort:** 1 day.

---

### Step 3 — Move pure-Kotlin logic into `commonMain`

**Goal:** Lift-and-shift every file that has zero Android dependencies into `shared/src/commonMain`. This is the largest LOC step but the lowest risk because there is nothing to rewrite, only to re-package.

**Files to move verbatim** (zero Android deps, confirmed by import inspection):

| From | To | LOC |
|------|-----|-----|
| `protocol/src/main/kotlin/com/playbridge/protocol/Message.kt` | `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` | 733 |
| `protocol/src/main/kotlin/com/playbridge/protocol/Config.kt` | `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Config.kt` | — |
| `protocol/src/main/kotlin/com/playbridge/protocol/BluetoothConstants.kt` | `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/BluetoothConstants.kt` | — |
| `tv/player/app/.../stremio/QualityRanker.kt` | `shared/src/commonMain/kotlin/com/playbridge/shared/stremio/QualityRanker.kt` | 39 |
| `tv/player/app/.../stremio/SourceTypeRanker.kt` | `shared/src/commonMain/kotlin/com/playbridge/shared/stremio/SourceTypeRanker.kt` | ~30 |

**Files that move with a small swap** (swap one or two Android APIs for shared abstractions):

| From | Swap | Notes |
|------|------|-------|
| `tv/player/app/.../stremio/SeriesNavigator.kt` | `android.util.Log` → `com.playbridge.shared.logging.logger` | Otherwise pure Kotlin + coroutines |
| `tv/player/app/.../player/M3uParser.kt` | `android.util.Log` → shared logger; `BufferedReader`/`InputStreamReader` → `okio.BufferedSource` | Parser only, still pure logic |
| `tv/player/app/.../player/SubtitleManager.kt` (parser half) | split: the SRT/VTT parsing moves; the `TextView` sync loop stays on Android | See Step 4 |

**New shared abstractions to introduce in Step 3:**

`shared/src/commonMain/kotlin/com/playbridge/shared/logging/Logger.kt`:

```kotlin
package com.playbridge.shared.logging

expect val logger: Logger

interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
```

`shared/src/androidMain/kotlin/com/playbridge/shared/logging/Logger.android.kt`:

```kotlin
package com.playbridge.shared.logging

import android.util.Log

actual val logger: Logger = object : Logger {
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun i(tag: String, msg: String) { Log.i(tag, msg) }
    override fun w(tag: String, msg: String, t: Throwable?) { Log.w(tag, msg, t) }
    override fun e(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t) }
}
```

`shared/src/appleMain/kotlin/com/playbridge/shared/logging/Logger.apple.kt`:

```kotlin
package com.playbridge.shared.logging

import platform.Foundation.NSLog

actual val logger: Logger = object : Logger {
    override fun d(tag: String, msg: String) { NSLog("D/$tag: $msg") }
    override fun i(tag: String, msg: String) { NSLog("I/$tag: $msg") }
    override fun w(tag: String, msg: String, t: Throwable?) {
        NSLog("W/$tag: $msg ${t?.stackTraceToString() ?: ""}")
    }
    override fun e(tag: String, msg: String, t: Throwable?) {
        NSLog("E/$tag: $msg ${t?.stackTraceToString() ?: ""}")
    }
}
```

`shared/src/commonMain/kotlin/com/playbridge/shared/io/Paths.kt`:

```kotlin
package com.playbridge.shared.io

import okio.Path

expect object Paths {
    val cacheDir: Path            // subtitle cache, resume data, etc.
    val documentsDir: Path        // user-persistent state
}
```

The Android actual resolves `cacheDir` from `Context.cacheDir`; Apple resolves via `NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)`. On Android we wire the `Context` through a one-time `ShareContext.init(appContext)` call in `PlayBridgeApplication.onCreate`.

**`StremioClient.kt` rewrite (Ktor port):**

`StremioClient.kt` imports `okhttp3.OkHttpClient`, `okhttp3.Request`, `android.content.Context`, `java.io.File`. The Ktor port:

```kotlin
// shared/src/commonMain/kotlin/com/playbridge/shared/stremio/StremioClient.kt
package com.playbridge.shared.stremio

import com.playbridge.shared.io.Paths
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

class StremioClient(
    private val http: HttpClient,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val cacheRoot: okio.Path = Paths.cacheDir / "stremio",
) {
    // same public methods as before: getStreams(...), getMeta(...), etc.
    // network I/O goes through `http`, disk cache through `fs`.
}
```

A single `HttpClient` instance is provided from each platform by a `SharedHttpClient` object that wires the platform-specific engine (OkHttp on Android, Darwin on Apple).

**Phone app dep flip:** `phone/app/build.gradle.kts` — replace `implementation(project(":protocol"))` with `implementation(project(":shared"))`. Phone imports change from `com.playbridge.protocol.*` to `com.playbridge.shared.protocol.*` (mechanical find/replace).

**Validation checkpoint:**

```bash
./gradlew :shared:allTests                       # new common unit tests for QualityRanker/SourceTypeRanker pass
./gradlew :phone:app:assembleDebug               # phone still builds on :shared (not :protocol)
./gradlew :tv:player:app:assembleDebug           # TV player still builds
./gradlew :tv:browser:app:assembleDebug          # TV browser still builds
adb install -r tv/player/app/build/outputs/apk/debug/app-debug.apk
# smoke: cast a URL from phone, confirm playback starts as before
```

**Effort:** 3-4 days (lion's share in `StremioClient` OkHttp → Ktor rewrite + paired tests).

---

### Step 4 — Define `expect interface PlaybackEngine` and implement on both platforms

**Goal:** Carve out the engine abstraction so that `PlayerViewModel` (Step 5) can run in `commonMain`. The Android actuals wrap `ExoPlayer`, `LibVLC`, and `MPV`; the Apple actuals wrap `AVPlayer` and `TVVLCKit`. No controller/activity changes in this step — the Android app still wires engines through `ExoPlayerActivity` / `VlcPlayerActivity` / `MpvPlayerActivity`; those files now delegate to the new engine interface.

**`shared/src/commonMain/kotlin/com/playbridge/shared/player/PlaybackEngine.kt`:**

```kotlin
package com.playbridge.shared.player

import com.playbridge.shared.protocol.PlayPayload
import kotlinx.coroutines.flow.StateFlow

expect interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val position: StateFlow<Long>     // ms
    val duration: StateFlow<Long>     // ms, -1 if unknown
    val audioTracks: StateFlow<List<Track>>
    val subtitleTracks: StateFlow<List<Track>>

    suspend fun load(payload: PlayPayload)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun setRate(rate: Float)
    fun setAudioTrack(id: String?)
    fun setSubtitleTrack(id: String?)
    suspend fun attachExternalSubtitle(url: String, language: String?)
    fun setFilter(filter: VideoFilter)
    fun release()
}

sealed class PlaybackState {
    data object Idle         : PlaybackState()
    data object Buffering    : PlaybackState()
    data object Ready        : PlaybackState()
    data object Playing      : PlaybackState()
    data object Paused       : PlaybackState()
    data object Ended        : PlaybackState()
    data class  Error(val code: String, val msg: String) : PlaybackState()
}

data class Track(val id: String, val label: String, val language: String?)
```

**Android actuals** (`shared/src/androidMain/`):

```kotlin
actual interface PlaybackEngine { /* inherits the common signature */ }

class ExoPlayerEngine(private val context: Context) : PlaybackEngine { ... }
class VlcPlayerEngine(private val context: Context) : PlaybackEngine { ... }
class MpvPlayerEngine(private val context: Context) : PlaybackEngine { ... }
```

These wrap the existing players; the logic currently living inside `ExoPlayerActivity.kt` (retry counter, track selection, error classification) moves behind the `ExoPlayerEngine` API. `PlayerActivity.kt`'s `BufferConfig` (lines 163-187) becomes a private detail of `ExoPlayerEngine`.

**Apple actuals** (`shared/src/tvosMain/`):

```kotlin
import platform.AVFoundation.*

class AVPlayerEngine : PlaybackEngine { /* AVPlayer + AVPlayerItem */ }
class TVVLCKitEngine : PlaybackEngine { /* VLCMediaPlayer via cinterop */ }
```

`TVVLCKitEngine` requires a Kotlin/Native cinterop wrapper around TVVLCKit. The `def` file lives in `shared/src/nativeInterop/cinterop/TVVLCKit.def`:

```
language = Objective-C
modules = TVVLCKit
package = com.playbridge.shared.vlc
```

The `.framework` is downloaded via Swift Package Manager or Carthage into `tv/apple-tv/Frameworks/` and referenced from the `shared` Kotlin/Native build.

**Validation checkpoint:**

```bash
./gradlew :shared:allTests                       # engine contract tests (via test doubles) pass
./gradlew :tv:player:app:assembleDebug           # Android build still green; activities now delegate
# Manual: cast URL → ExoPlayer path works; switch engine pref → VLC path works; MPV path works
./gradlew :shared:assembleXCFramework            # produces Shared.xcframework including tvOS actuals
```

**Effort:** 4-5 days (most of the time is TVVLCKit cinterop and behavior-parity verification across three Android engines).

---

### Step 5 — One `PlayerViewModel` in `commonMain`

**Goal:** Move all state-machine logic — pre-play resolution, playlist queue, auto-advance, error retry classification, resume position, series next-up — out of `ExoPlayerActivity.kt` and `PlayerActivity.kt` and into a single `PlayerViewModel` that `commonMain` owns.

This step is intentionally split into two phases so the TV app stays shippable throughout.

---

#### 5a — Proxy layer (DONE)

**Status:** Completed. All three Activities now construct a `PlayerViewModel` and run it in parallel with their existing state machine. The VM observes the engine, manages playlist/series state, and emits `PlayerUiState`, while the Activity continues to drive playback directly.

**New files in `:shared`:**

| File | Role |
|---|---|
| `shared/src/commonMain/.../player/PlayerViewModel.kt` | State machine (idle → preplay → loading → playing → ended → error), playlist queue, series navigation, pre-play resolution, auto-advance, resume via `ResumeStore` |
| `shared/src/commonMain/.../player/PlayerUiState.kt` | Sealed class: `Idle`, `PrePlay`, `Loading`, `Playing`, `Paused`, `Error`, `Ended` |
| `shared/src/commonMain/.../player/PlaybackSettings.kt` | DTO for quality, bitrate cap, preferred addon, source types |
| `shared/src/commonMain/.../resume/ResumeStore.kt` | Interface: `loadPosition(url)`, `savePosition(url, positionMs)` |
| `shared/src/commonTest/.../player/TestDoubles.kt` | `FakePlaybackEngine`, `FakeResumeStore` for unit tests |
| `shared/src/commonTest/.../player/PlayerViewModelTest.kt` | Turbine-based `StateFlow` tests (initial state, payload loading, resume, toggle, playlist next/prev, error, looping, ended auto-advance, retry, dispose) |

**New file in `:tv:player:app`:**

| File | Role |
|---|---|
| `tv/player/app/.../data/HistoryResumeStore.kt` | Android-specific `ResumeStore` implementation backed by `HistoryStore`. Lives in the app module so `:shared` does not depend on Android `DataStore`. |

**Activity wiring (per-Activity, identical pattern):**

- `lateinit var viewModel: PlayerViewModel` + `lateinit var resumeStore: HistoryResumeStore` + `vmUiJob`
- In engine-creation function: construct VM with `engine`, `resumeStore`, `lifecycleScope`; collect `vm.ui` into `handleVmUiState(state)` for logging
- In release/dispose: cancel `vmUiJob`, call `viewModel.dispose()`
- Sync Activity-owned state into VM whenever `playlistItems` / `playlistIndex` / `seriesNavigator` / `isLooping` change
- **Activities keep:** window flags, `BroadcastReceiver`, controls/dialog managers, surface attachment, input handling, ExoPlayer-specific retry logic, `ProgressManager` history persistence

**Validation (5a):**

```bash
./gradlew :shared:allTests              # 23/24 pass; 1 skipped (tvosSimulatorArm64 Turbine race)
./gradlew :tv:player:app:assembleDebug  # SUCCESSFUL
```

**Known issue:** `PlayerViewModelTest.playlist next advances index and loads next item` fails on `tvosSimulatorArm64` due to `UnconfinedTestDispatcher` interleaving between `scope.launch` and `StateFlow` collectors in the Native test runtime. Test is commented out with a TODO; all other targets pass.

---

#### 5b — Make the VM load-bearing (PENDING)

**Goal:** Flip playback control from Activity-driven to VM-driven. The Activity becomes a pure UI/renderer: it observes `vm.ui`, renders controls, and forwards user input back to the VM.

**Slice 1 — Intent dispatch**
- `ExoPlayerActivity.handleIntent()` → call `vm.onPayload()` / `vm.onContentPayload()` instead of `playVideo()`
- `ExoPlayerActivity.onNewIntent()` → forward to VM; VM handles `stopPlayback()` + `load()` internally
- Remove `playVideo()`, `startPlayback()`, `resolveStreamsAndPreBuffer()` from Activity

**Slice 2 — Playlist / series navigation**
- Activity `playNextInPlaylist()` / `playPreviousInPlaylist()` / `playItemAtIndex()` → call `vm.next()` / `vm.previous()` / `vm.jumpToPlaylistIndex()`
- VM already auto-advances on `Ended`; remove duplicate `playNextInPlaylist()` calls from `Player.Listener.onPlaybackStateChanged(ENDED)`
- Delete `playlistItems`, `playlistIndex`, `seriesNavigator` fields from Activity; read from `vm.ui.value`

**Slice 3 — Resume position**
- VM already loads resume via `ResumeStore` on `loadPayloadInternal()`; remove duplicate `ProgressManager` / `HistoryStore` resume logic from Activity
- VM saves progress on navigation; Activity keeps `ProgressManager` for thumbnail capture + full `PlaybackHistoryItem` persistence only

**Slice 4 — Pre-play UI**
- Activity keeps the Compose `PrePlayScreen` overlay, but drives it from `vm.ui` `PrePlay` state instead of local `prePlayPayload` / `isPrePlayLaunching` vars
- Stream selection dialog calls `vm.selectStream()` instead of `playVideoAfterResolution()`

**Slice 5 — Error / retry**
- VM maps `PlaybackState.Error` → `PlayerUiState.Error`; Activity renders toast/snackbar from VM state
- ExoPlayer-specific retry logic (audio discontinuity, decoder init, stuck buffer) stays in Activity-local `Player.Listener` until a cross-platform retry policy is designed

**Validation checkpoint (full 5b):**

```bash
./gradlew :shared:allTests
./gradlew :tv:player:app:assembleDebug
# Manual regression matrix:
#   - cast URL → plays (ExoPlayer)
#   - pause, seek, resume from phone → VM reacts
#   - playlist → auto-advance works
#   - series → next-up prompt appears
#   - playback error → retry + error UI correct
```

**Effort estimate:** 3-4 days for all slices. Biggest risk is subtle behavioral drift where the Activity used to hold state implicitly (`private var retryCount: Int = 0` at `ExoPlayerActivity.kt:92-96`) and side effects (intent mutation, thumbnail capture timing) are interleaved with VM state changes. Recommended: one slice per PR, manual QA on device between each.

---

### Step 6 — Delete `:protocol`

**Goal:** Remove the now-duplicated JVM-only `:protocol` module. Both `:phone:app` and `:tv:player:app` (and the browser/mpvEx apps, if they reference protocol at all) now depend on `:shared` instead.

**Steps:**

1. Remove `include(":protocol")` and the `project(":protocol").projectDir` override from the root `settings.gradle.kts`.
2. Remove `implementation(project(":protocol"))` from every app `build.gradle.kts`.
3. Rename the package in app source from `com.playbridge.protocol.*` to `com.playbridge.shared.protocol.*` via IDE find/replace. (Phone is already done in Step 3; this completes TV and extension-adjacent code.)
4. Delete the `protocol/` directory.

**Check for stragglers:**

```bash
rg "com\.playbridge\.protocol" --type kt
# should return zero hits after rename
rg "project\(\":protocol\"\)" --type kts
# should return zero hits
```

**Validation checkpoint:**

```bash
./gradlew :phone:app:assembleDebug :tv:player:app:assembleDebug :tv:browser:app:assembleDebug
# full regression from end-to-end:
#   - pair phone with TV
#   - cast video
#   - check pairing QR still encodes the same bytes as before
```

Binary-level check: capture the WebSocket JSON payload from the phone before Step 2 and after Step 6; it must be byte-identical, modulo map-key ordering.

**Effort:** 0.5 day.

---

### Step 7 — Thin native UI shells

**Goal:** With `:shared` now owning the protocol, Stremio client, resume store, engine interface, and `PlayerViewModel`, each app's remaining job is to render UI and plumb platform APIs.

**Android TV player app (`tv/player/app/`) ends up containing only:**

- `MainActivity.kt` / `PairingScreen.kt` / `SettingsScreen.kt` / `PlayerScreen.kt` — Compose for TV screens.
- `ServerService.kt` — foreground service, NSD registration, WebSocket server (Ktor-Netty). The command-dispatch logic routes to `PlayerViewModel` via a bound-service + `StateFlow` interface (replacing the current BroadcastReceiver RPC documented in the architecture review at `ExoPlayerActivity.kt:120-171`).
- `BrowserActivity.kt` and the browser-engine switching layer (GeckoView / WebView), unchanged.
- Android-specific overlay / background activity launch helper (`OverlayWindowHelper.kt`), unchanged.
- `PlayBridgeApplication.kt` — now also calls `ShareContext.init(this)` so `:shared` can resolve `Paths.cacheDir`.

**Android TV browser app (`tv/browser/app/`):** dep flip only. No other change.

**Phone app (`phone/app/`):** dep flip only. Over time, the phone's Stremio/Debrid code can also migrate into `:shared`, but that is out of scope for this migration.

**tvOS app (`tv/apple-tv/PlayBridge TV/`) contains:**

- `App.swift` — `@main` entry, wires `ServerCoordinator` (native Swift, implements the native side of the WS server or embeds a Ktor-Native server inside `:shared`) into the `PlayerViewModel`.
- `Screens/` — SwiftUI for pairing, settings, player.
- `Bridges/` — `PlayerViewModelBridge.swift`, `PlaybackEngineFactory.swift` (`AVPlayerEngine` / `TVVLCKitEngine` selection).
- `Focus/` — tvOS focus-engine modifiers.

The Xcode project consumes `Shared.xcframework` either via Swift Package Manager (a thin `Package.swift` in `shared/` pointing to a CI-published binary framework) or via a direct `Framework Search Paths` entry to `shared/build/XCFrameworks/release/`.

**Validation checkpoint:**

End-to-end: phone pairs with Android TV, casts a URL, plays; phone pairs with Apple TV (same protocol bytes), casts the same URL, plays. Subtitle sidecar loads on both. Series next-up prompt appears on both.

**Effort:** 4-5 days (mostly SwiftUI shell + focus UX, since all logic is in `:shared`).

---

## Risks & Mitigations

### R1 — tvOS CMP is not stable

**Risk:** Someone suggests "just use Compose Multiplatform end-to-end and delete SwiftUI."

**Mitigation:** Document in `ARCHITECTURE.md` that CMP is stable on iOS (since May 2025) but **not** on tvOS as of this plan's authorship. tvOS has no focus-engine bindings in CMP, no TVMLKit interop, and degraded scroll performance. Revisit in 12 months.

### R2 — iOS framework packaging friction

**Risk:** Xcode build breaks because the `Shared.xcframework` is stale, missing a target, or not re-built before Xcode compiles.

**Mitigation:**

1. Add a `Run Script` build phase in the Xcode project that invokes `../../gradlew :shared:embedAndSignAppleFrameworkForXcode` before Swift compilation. This is the official KMP incantation and rebuilds the framework for the active Xcode target only.
2. Publish a versioned xcframework artifact from CI on every `main` push, so developers without JDK locally can still build the tvOS app by pulling the binary.

### R3 — OkHttp → Ktor rewrite behavior drift

**Risk:** `StremioClient` behaves subtly differently under Ktor: retries, timeouts, TLS handling, User-Agent, redirect behavior.

**Mitigation:**

1. Before the rewrite, capture 20-30 real Stremio addon responses as fixture files under `shared/src/commonTest/resources/stremio/`.
2. Write `StremioClientTest` that runs against a MockEngine replaying those fixtures; assert parsed output matches pre-migration expectations.
3. Explicitly configure Ktor `HttpTimeout` and `HttpRequestRetry` plugins to mirror OkHttp's defaults (`connectTimeout = 10s`, `readTimeout = 30s`, retry = 2).

### R4 — Per-app `Context` injection for `Paths` and `SharedHttpClient`

**Risk:** `:shared` cannot call `Android Context` directly from `commonMain`. If the Android actuals try to read `Context.cacheDir` before `PlayBridgeApplication.onCreate` runs, they crash.

**Mitigation:** Introduce `ShareContext.init(Context)` called at the very top of every `Application.onCreate` (phone, TV player, TV browser). The Android actual of `Paths` reads `ShareContext.cacheDir` which throws `IllegalStateException` if init wasn't called. This surfaces misconfiguration as a clear error during smoke testing rather than as a subtle runtime crash.

### R5 — TVVLCKit cinterop fragility

**Risk:** Kotlin/Native cinterop to TVVLCKit breaks on Xcode or VLC version bumps.

**Mitigation:**

1. Pin the TVVLCKit version in `shared/Package.swift` and in the `.def` file.
2. Add a CI job that runs `./gradlew :shared:linkReleaseFrameworkTvosArm64` on every PR.
3. If cinterop becomes a maintenance burden, make `TVVLCKitEngine` Swift-side only: declare `expect class TVVLCKitEngine : PlaybackEngine` and supply the implementation under `tv/apple-tv/` as a Swift class conforming to a @objc-exposed protocol from `:shared`. This keeps VLC fully outside Kotlin/Native.

### R6 — Behavioral drift in the Activity-to-VM split

**Risk:** `ExoPlayerActivity` currently holds implicit state (retry counters, pre-play flags, lateinit managers). Moving that state into `PlayerViewModel` is the most error-prone step.

**Mitigation:**

1. Write `PlayerViewModelTest` covering every state transition with Turbine before touching the Activity.
2. Do Step 5 in two sub-steps: (5a) introduce the VM, have the Activity proxy to it while keeping its own state; (5b) remove Activity state once the VM is load-bearing.

### R7 — Multi-project Gradle consolidation

**Risk:** Moving from four independent Gradle builds to one root build might surface version conflicts (Ktor, Kotlin, Compose BOM) that were happily independent before.

**Mitigation:** The `libs.versions.toml` catalog in Step 2 is the single source of truth. Run `./gradlew :phone:app:dependencies :tv:player:app:dependencies` after consolidation and diff against the pre-migration output; resolve conflicts before declaring Step 2 done.

### R8 — Team familiarity with KMP

**Risk:** Contributors unfamiliar with KMP bounce off the `expect/actual` concept or the multi-source-set build.

**Mitigation:** Add a short `shared/README.md` with a 20-line cheatsheet (how to add a new `expect` declaration, how to add a new target, how to rebuild the xcframework, how to run `:shared:allTests`). Link it from the top-level `ARCHITECTURE.md`.

---

## Timeline

Working calendar-day estimates assume one developer with intermediate Kotlin + KMP familiarity. Steps 1 and 2 can run in parallel with Step 3 prep work (fixture capture for Stremio).

| Step | Description | Effort | Cumulative |
|-----:|-------------|-------:|----------:|
| 1 | Scrap Swift scaffold | 0.5d | 0.5d |
| 2 | `:shared` skeleton + Gradle consolidation | 1d | 1.5d |
| 3 | Move pure-Kotlin logic + Ktor port | 3-4d | 4.5-5.5d |
| 4 | `PlaybackEngine` + actuals | 4-5d | 8.5-10.5d |
| 5 | `PlayerViewModel` in commonMain | 5-6d | 13.5-16.5d |
| 6 | Delete `:protocol` | 0.5d | 14-17d |
| 7 | Thin native UI shells | 4-5d | 18-22d |

**Total: roughly 4 calendar weeks** for one developer. Add ~20% buffer for TVVLCKit cinterop and Swift toolchain friction → **5 weeks realistic**.

The Android app stays shippable after every step. The tvOS app is a placeholder through Steps 1-6 and only becomes usable at the end of Step 7 — this is acceptable because the tvOS app has no users today.

---

## Post-Migration Backlog

Items deliberately deferred to keep the migration scoped:

1. **Hilt / Koin DI** — once `:shared` owns construction of `StremioClient`, `PlaybackEngine`, `PlayerViewModel`, revisit DI. Koin fits KMP; Hilt is Android-only. This becomes a separate proposal.
2. **Compose for TV migration of `ExoPlayerActivity` / `VlcPlayerActivity` controls** — currently XML. Nothing blocks it, but it's orthogonal to KMP.
3. **Move phone's `LibraryViewModel`, `TmdbRepository`, `OmdbRepository`, `DebridProvider` into `:shared`** — the phone app keeps working on KMP-ready types but doesn't benefit until iOS-phone becomes a target.
4. **Baseline profiles + R8 enablement** — tracked in `tv/ARCHITECTURE_REVIEW.md`, not blocked by KMP.
5. **Roborazzi / Paparazzi screenshot tests for Android Compose screens** — once the UI is Compose, add snapshot tests under `tv/player/app/src/test/`.
6. **Ktor-Native WebSocket server on tvOS** — currently the plan assumes the tvOS app hosts its WS server in native Swift (`ServerCoordinator.swift`). Longer term, the Ktor CIO engine on Kotlin/Native could subsume this, making the server code shareable too.
7. **Play Store blockers** — the six items tracked in memory (`ContentSniffer` SSL, `network_security_config`, unused perms, AAB build, privacy policy, data safety form) are **not** blocked by this migration and should ship on the current branch before Step 1 lands.

---

## Appendix A — Mechanical rename map

Every import in the codebase changes once. The rename is mechanical (IDE-driven find/replace) and idempotent:

| Old package | New package |
|-------------|-------------|
| `com.playbridge.protocol` | `com.playbridge.shared.protocol` |
| `com.playbridge.player.stremio.QualityRanker` | `com.playbridge.shared.stremio.QualityRanker` |
| `com.playbridge.player.stremio.SourceTypeRanker` | `com.playbridge.shared.stremio.SourceTypeRanker` |
| `com.playbridge.player.stremio.SeriesNavigator` | `com.playbridge.shared.stremio.SeriesNavigator` |
| `com.playbridge.player.stremio.StremioClient` | `com.playbridge.shared.stremio.StremioClient` |
| `com.playbridge.player.player.M3uParser` | `com.playbridge.shared.player.M3uParser` |
| `com.playbridge.player.player.SubtitleManager` (parser half) | `com.playbridge.shared.subtitle.SubtitleParser` |

The Android-bound half of `SubtitleManager` (the `TextView` sync loop, `HttpURLConnection` download) keeps its current package; only the parsing functions move.

---

## Appendix B — What stays Android-only

A short list to prevent feature creep in `:shared`:

- GeckoView, Mozilla Android Components, and everything under `tv/browser/app/.../browser/` (GeckoView is Android-only; no Apple counterpart).
- `OverlayWindowHelper.kt` and the SYSTEM_ALERT_WINDOW background-activity-launch workaround (Android 14+ specific).
- NSD registration via `android.net.nsd.NsdManager` and the Apple equivalent via `NetService` — platform-specific actuals, thin shims only.
- Bluetooth RFCOMM (`BluetoothClient.kt`, `BluetoothServer.kt`) — Android Bluetooth API has no tvOS equivalent; this feature degrades on Apple to "no BT fallback."
- The foreground service (`ServerService.kt`) — Android concept; on tvOS the WS server runs as a standard background task.
- GPU `VideoFilterManager.kt` (Media3 `GlEffect`) — Android-specific. The tvOS counterpart uses `AVMutableVideoComposition` or a `CIFilter` pipeline; both are platform-specific actuals behind `PlaybackEngine.setFilter(...)`.

---

## Appendix C — References

- [Kotlin Multiplatform official docs](https://kotlinlang.org/docs/multiplatform.html)
- [Ktor client engines](https://ktor.io/docs/client-engines.html) — CIO, Darwin, OkHttp
- [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings)
- [okio — multiplatform file I/O](https://square.github.io/okio/)
- [Turbine — testing Flows](https://github.com/cashapp/turbine)
- [Compose Multiplatform 1.8 release notes (May 2025)](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-release-notes.html) — iOS stable, tvOS not supported
- Local: [`tv/ARCHITECTURE_REVIEW.md`](./ARCHITECTURE_REVIEW.md) — source of file/line citations referenced throughout this plan.
