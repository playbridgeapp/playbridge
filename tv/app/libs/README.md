# tv/app/libs

## mpv-android.aar — required for Internal (MPV) player mode

`MpvPlayerActivity` depends on the `is.xyz.mpv.MPVLib` class from the mpv-android prebuilt AAR.
The AAR is not bundled here because it contains large platform-specific `.so` binaries.

### How to obtain it

**Option A — mpv-android GitHub releases (recommended)**

1. Go to https://github.com/mpv-android/mpv-android/releases
2. Download the latest `mpv-android-*.apk` for `arm64-v8a`
3. Rename / extract the AAR from the release artifacts, or use the source + buildscripts:
   ```
   git clone https://github.com/mpv-android/mpv-android
   cd mpv-android/buildscripts
   ./download.sh
   ./buildall.sh
   # The AAR is produced at app/build/outputs/aar/
   ```

**Option B — mpvEx prebuilt**

The mpvEx project ships a prebuilt AAR:
https://github.com/marlboro-advance/mpvEx/blob/main/app/libs/mpv-android-lib-v0.0.1.aar

Download it, rename it to `mpv-android.aar`, and place it here.

### Enabling the dependency

Once the file is in place, open `tv/app/build.gradle.kts` and uncomment:

```kotlin
implementation(files("libs/mpv-android.aar"))
```
