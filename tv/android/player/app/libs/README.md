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

The current experimental AAR is built from the PlayBridge mpv-android fork and
contains `armeabi-v7a` and `arm64-v8a`. Its SHA-256 is:

```text
01d1dfec32d24bef6ee73f73897f7ace82fa7344c7c31de2ce6f804fa3dc524d
```

It retains PNG decoding while disabling FFmpeg's `image_png_pipe` demuxer for
the disguised-PNG MPEG-TS regression. See the
[Android libmpv AAR fork plan](../../../../../docs/design-android-mpv-source-build.md).

The fork revision, moving native dependency revisions, and expected AAR
checksum are pinned in `mpv-android-build.env`. The dedicated
`Verify Android libmpv AAR` workflow rebuilds both ABIs from those inputs and
requires the generated AAR to be byte-identical to this committed artifact.
