# PlayBridge — Phone App

The **sender** in the PlayBridge suite. An Android phone app (`com.playbridge.sender`) that finds content, then bridges it to a PlayBridge receiver (Android TV, tvOS, or desktop) while doubling as a remote control.

## What it does

- Built-in web browser (GeckoView) with automatic media detection
- Library & discovery via Stremio-style addons and TMDB / OMDB / TVDB metadata
- Debrid integration — Real-Debrid, AllDebrid, Premiumize, TorBox
- Casts to the receiver and drives playback remotely (play/pause/seek/queue, now-playing sync)
- In-app local playback (`PlayerActivity`)

## Tech

- Kotlin + Jetpack Compose
- Consumes the shared [`:shared`](../shared/) Kotlin Multiplatform module
- `minSdk 24`, `compileSdk 35`, JDK 17

## Build

```bash
cd mobile/android
./gradlew :app:assembleDebug     # debug APK -> app/build/outputs/apk/
./gradlew :app:installDebug      # build + install on a connected device
```

Gradle root project: **PlayBridgePhone** (`mobile/android/settings.gradle.kts`, modules `:app` and `:shared`).
