# tv/app/libs

Prebuilt native libraries bundled for the TV player app.

## mpv-android.aar — Internal (MPV) player mode

`MpvPlayerActivity` depends on the `is.xyz.mpv.MPVLib` class from the mpv-android prebuilt AAR.
It is committed here and wired up in `tv/android/player/app/build.gradle.kts`:

```kotlin
implementation(files("libs/mpv-android.aar"))
```
