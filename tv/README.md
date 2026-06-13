# PlayBridge — TV Apps

The **receiver** side of PlayBridge: it runs on the big screen, plays the content the phone sends, and hosts a web browser the phone can drive.

## Apps

- **Player** — `tv/android/player` (`com.playbridge.player`). Android TV player with multi-engine playback: Media3/ExoPlayer and an internal MPV (libmpv) engine, plus bundled software decoders (FFmpeg, AV1, IAMF, MPEG-H).
- **GeckoView Plugin** — `tv/android/geckoview-plugin` (`com.playbridge.geckoview.plugin`). GeckoView browser engine plugin for Android TV with a bundled uBlock Origin, designed for D-pad navigation.
- **tvOS** — `tv/apple` (`PlayBridge TV`). Apple TV variant in Swift with an mpv-based player.

## Build — Android

```bash
cd tv/android
./gradlew :player:app:assembleDebug
./gradlew :geckoview-plugin:app:assembleDebug
```

Gradle root project: **PlayBridgeTV** (`tv/android/settings.gradle.kts`, modules `:player:app`, `:geckoview-plugin:app`, `:shared`).

## Build — tvOS

```bash
cd "tv/apple/PlayBridge TV"
pod install
open "PlayBridge TV.xcworkspace"   # build & run from Xcode
```

The prebuilt native player libraries live in [`android/player/app/libs/`](android/player/app/libs/).
