# tv/app/libs

Prebuilt native libraries for the TV player app (TV-only).

## Shared with phone

**FFmpeg decoder** lives at the repo root (single copy):

```
prebuilt/media3/lib-decoder-ffmpeg-release.aar
```

See `prebuilt/media3/README.md`.

## mpv-android.aar — Internal (MPV) player mode

`MpvPlayerActivity` depends on the `is.xyz.mpv.MPVLib` class from the mpv-android prebuilt AAR.
It is committed here and wired up in `tv/android/player/app/build.gradle.kts`:

```kotlin
implementation(files("libs/mpv-android.aar"))
```
