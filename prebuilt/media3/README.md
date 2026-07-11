# Shared Media3 decoder AARs

Prebuilt Android archives used by more than one app module.

| File | Used by |
|---|---|
| `lib-decoder-ffmpeg-release.aar` | **Phone** (`mobile/android`) and **TV** (`tv/android/player`) |

## Version alignment (required)

| Piece | Version / note |
|---|---|
| Media3 catalog (`gradle/libs.versions.toml`) | **1.9.2** |
| This AAR package | `androidx.media3.decoder.ffmpeg` (Media3 **1.9.x** extension build) |
| `minCompileSdk` (aar-metadata) | **35** |
| ABIs in AAR | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Phone/TV packaging | app `ndk.abiFilters` keep **arm64-v8a** + **armeabi-v7a** only |

Phone and TV must keep the catalog Media3 version and this AAR in lockstep. Bumping
`media3 = "…"` without rebuilding/replacing this AAR can make
`FfmpegLibrary.isAvailable()` false or break `FfmpegAudioRenderer` registration.

**Runtime check (phone):** logcat `PB_PLAYER` on player open:

```
ExoPlayer factory ffmpeg=true version=<ffmpeg-lib-version> maxBufferMs=…
```

If you see `ffmpeg=false`, audio-only failures on DTS/E-AC3/etc. are expected — fix
packaging or rebuild the AAR against the current Media3 version (same recipe TV used).

Wire-up (paths relative to each Gradle `rootProject.projectDir`):

```kotlin
// Phone — rootProject = mobile/android
implementation(files("${rootProject.projectDir}/../../prebuilt/media3/lib-decoder-ffmpeg-release.aar"))

// TV — rootProject = tv/android
implementation(files("${rootProject.projectDir}/../../prebuilt/media3/lib-decoder-ffmpeg-release.aar"))
```

TV-only AARs (mpv, AV1, IAMF, mediainfo) stay under `tv/android/player/app/libs/`.
