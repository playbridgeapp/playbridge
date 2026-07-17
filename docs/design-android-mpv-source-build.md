# Android libmpv AAR Fork Plan

Status: Experiment validated; migration pending
Last updated: 2026-07-16

## Decision

Build PlayBridge's Android MPV AAR from the
[`playbridgeapp/mpv-android`](https://github.com/playbridgeapp/mpv-android)
fork of
[`mpv-android/mpv-android`](https://github.com/mpv-android/mpv-android).
Preserve mpv-android's Kotlin API, JNI bridge, native build scripts, surface
lifecycle, and packaged runtime libraries as the starting point. Do not replace
them with another minimal JNI wrapper.

The fork will be the source of truth for the Android MPV library. It will
produce one versioned AAR that Android TV and, if needed later, Android phone
can consume. Ordinary PlayBridge builds may use the published AAR for speed;
F-Droid and reproducibility checks must be able to build the same artifact
from the pinned fork and native dependency sources.

The local clone currently lives at:

```text
inspirations/mpv-android
```

It points to `https://github.com/playbridgeapp/mpv-android.git` and was at
commit `3b65d17f3526098aac39c39d34bff6724cd4d253` when this plan was written.
The entire `inspirations/` directory is gitignored, so this clone is a working
copy for the experiment, not yet a source input visible to PlayBridge CI or
F-Droid.

## Why this baseline

The current PlayBridge AAR is byte-for-byte identical to
`inspirations/mpvEx/app/libs/mpv-android-lib-v0.0.1.aar`. That AAR contains
more than `libmpv.so`:

- `MPVLib`, `BaseMPVView`, `MPVNode`, utilities, and event/property APIs;
- `libplayer.so`, the JNI bridge used by those APIs;
- libmpv and its FFmpeg/runtime dependencies for four Android ABIs; and
- the CA bundle and fallback font assets.

That complete integration explains why the legacy AAR worked immediately.
The earlier PlayBridge source-build experiment retained native libmpv but
replaced the mature wrapper with a smaller bridge. It then had to rediscover
surface ordering and lost the initial `pause` state before the activity
registered its observer, causing blank output and broken play/pause behavior.

The upstream project explicitly identifies
[`MPVLib`](https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/MPVLib.kt),
[`BaseMPVView`](https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/BaseMPVView.kt),
the [native JNI code](https://github.com/mpv-android/mpv-android/tree/master/app/src/main/jni),
and the [native build scripts](https://github.com/mpv-android/mpv-android/tree/master/buildscripts)
as the relevant pieces for applications embedding libmpv. Copying only
`buildscripts/` would therefore be insufficient; the fork gives us the whole
known architecture.

## Important compatibility gap

The current upstream fork is close to the legacy AAR, but it is not a
drop-in replacement yet. The mpvEx AAR extends the API with functionality such
as:

- `MPVNode` and node-valued commands/properties;
- an event callback carrying `eventId` and `MPVNode` data;
- Kotlin Flow property and event helpers; and
- additional thumbnail functions.

PlayBridge currently compiles against `MPVNode` and the two-argument event
callback. Before the first replacement AAR is tested, either port the required
mpvEx extensions into the fork or adapt PlayBridge to the current upstream API.
For the initial migration, preserving the legacy API is lower risk because it
separates integration compatibility from changes to mpv and FFmpeg.

Only APIs actually used by PlayBridge need to be carried forward. Record the
result as an explicit compatibility surface and add compile tests so later
upstream merges cannot remove it accidentally.

## Target architecture

```text
playbridgeapp/mpv-android fork
  buildscripts/                 pinned native toolchain and dependencies
  app/                          upstream player and integration test harness
  libmpv-android/               new com.android.library AAR producer
    MPVLib + required mpvEx compatibility API
    upstream JNI bridge (libplayer.so)
    native libraries and assets
               |
               +--------------------+
               |                    |
               v                    v
       published, checksummed AAR    pinned source checkout
       ordinary PlayBridge builds    CI/F-Droid source builds
               |                    |
               +----------+---------+
                          v
                 app.playbridge:mpv-android
                          |
                    +-----+-----+
                    |           |
                    v           v
                Android TV   Android phone
```

Add a `com.android.library` module to the fork instead of converting the
existing application module. The app remains valuable as an upstream-like
test harness. Shared MPV sources should live in the library module, and the app
should consume the library so both exercise the same Kotlin/JNI code.

Use one Maven coordinate for both consumption modes, for example:

```text
app.playbridge:mpv-android:<version>
```

After the AAR producer is proven, add the fork to PlayBridge at
`shared/mpv-android` as a pinned Git submodule or equivalent scanner-visible
source checkout. Both Android Gradle roots can use a conditional composite
build to substitute the source project for the same Maven coordinate:

| Mode | Dependency source | Purpose |
|---|---|---|
| Published | PlayBridge-built AAR | Fast ordinary development and release builds |
| Source | `shared/mpv-android` checkout | Native development and reproducibility CI |
| F-Droid | Pinned source checkout plus native sources | Build app and AAR from reviewable source |

The ignored `inspirations/mpv-android` clone should not be wired directly into
Gradle. It can be moved or recloned as the tracked submodule once the fork
contains a usable library module.

## Native build baseline

The fork currently provides the complete native build path:

- `download.sh` prepares the SDK, NDK, and dependency sources;
- `buildall.sh` builds dependency graphs and supported ABIs;
- `scripts/ffmpeg.sh` defines FFmpeg's Android configuration;
- `scripts/mpv.sh` builds libmpv;
- `scripts/mpv-android.sh` builds `libplayer.so` and the Android project; and
- `app/src/main/jni/Android.mk` packages libmpv, FFmpeg libraries, and the JNI
  bridge.

At the recorded fork revision, the build declares Android NDK r29, native API
23, SDK 35, and FFmpeg `n8.1.2` for CI. These are the initial experimental
inputs, not permanent promises. Pin the exact fork commit, dependency commits
or release tags, tool versions, configure flags, and patches for every
published AAR.

Support `armeabi-v7a` and `arm64-v8a` first because they cover the current TV
hardware requirements. Build x86 and x86_64 only when PlayBridge emulator or
product requirements justify their build and testing cost.

## `test.ts` and disguised-PNG HLS regression

This plan explicitly covers both the downloaded `test.ts` fixture and the
live HLS stream whose segment URLs and HTTP responses claim to be PNG images.
The upstream fork is the integration baseline, but it is not expected to fix
this media by itself: mpvEx, mpvNova, desktop mpv, and FFmpeg all selected the
PNG path for the fixture, while QuickTime, ExoPlayer, and the working
media-kit-based experiment reached the MPEG-TS content.

Treat this as a second, isolated native change after the control AAR works:

1. Build an unmodified fork AAR and confirm the expected failure.
2. Capture `ffprobe`, mpv, and FFmpeg probe logs for the local fixture without
   storing authenticated stream URLs.
3. Compare enabled demuxers, probe scores, and relevant configure flags in the
   upstream, legacy, and known-working media-kit builds.
4. Implement the narrowest reproducible fix. Candidate experiments include
   excluding the image/PNG pipe demuxer from the playback build or adjusting
   probing for HLS segments; do not globally force all HLS content to MPEG-TS.
5. Verify that the patch selects MPEG-TS for the fixture while ordinary PNG,
   HLS, fMP4 HLS, and local media behavior remains acceptable.
6. Keep the change as a small attributed patch in the fork with a regression
   test and an explanation of why upstream behavior is insufficient.

Do not combine this experiment with a Kotlin API rewrite, surface rewrite, or
mpv version upgrade. One variable at a time is the central risk-control rule.

### Experiment result (2026-07-16)

The source-built armv7 AAR was rebuilt with FFmpeg's `image_png_pipe` demuxer
disabled (`CONFIG_IMAGE_PNG_PIPE_DEMUXER=0`). PNG decoding and encoding remain
enabled. The resulting AAR was integrated into the TV app, installed on the
connected armv7 TV, and verified to play `/Users/atulmehla/test.ts` correctly.
Ordinary HLS playback also remained functional. The initial armv7-only AAR
checksum was:

```text
4383012bb68a0d1805b3127b113541d84f5e795b972454914508804fb28dc036
```

This validates the narrow probe fix, but does not complete the migration.

The follow-up build produced a combined `armeabi-v7a` and `arm64-v8a` AAR with
16 KiB-aligned load segments for both architectures. It is currently integrated
into the TV app with SHA-256:

```text
01d1dfec32d24bef6ee73f73897f7ace82fa7344c7c31de2ce6f804fa3dc524d
```

The arm64 artifact has passed build and ELF inspection but still needs runtime
testing on arm64 hardware. The reviewed library and CI changes are published
through commit `e55dd6a` in
[playbridgeapp/mpv-android pull request #1](https://github.com/playbridgeapp/mpv-android/pull/1).

The PlayBridge provenance record lives in
`tv/android/player/app/libs/mpv-android-build.env`. The mpv-android fork owns
the exact moving dependency revisions, dual-ABI native build, FFmpeg feature
checks, and AAR/checksum publication. PlayBridge's lightweight
`.github/workflows/mpv_android_aar.yml` job validates the committed AAR's
checksum, ABI set, and required libraries without rebuilding the native stack.
Ordinary Android builds continue consuming that committed artifact.

## Migration phases

### Phase 0: Freeze evidence and preserve rollback

- Keep `tv/android/player/app/libs/mpv-android.aar` unchanged.
- Record its SHA-256 and its identity with the mpvEx AAR.
- Record the fork URL, commit, toolchain versions, and dependency versions.
- Preserve the earlier PlayBridge experiment in stash
  `a408c782e8c34dc31cbd71e8d05747d495288f7f` for reference only.
- Record sanitized test media hashes and expected results.

Exit gate: the legacy build and runtime behavior can be restored without
reconstructing any generated files.

### Phase 1: Produce an upstream control AAR

- Add `:libmpv-android` to the fork using the upstream Kotlin, JNI, assets,
  and native outputs.
- Make the upstream app depend on that module.
- Build armv7 and arm64 AARs from a clean native prefix.
- Inspect `classes.jar`, the ABI directories, and ELF `NEEDED` entries.
- Run the fork app with ordinary local and network media.

Exit gate: the fork app plays ordinary media and its controls and surface
lifecycle work while consuming the new AAR.

### Phase 2: Restore the PlayBridge compatibility API

- Inventory the public API of the legacy AAR with `javap`.
- Inventory all PlayBridge calls to `MPVLib`, `MPVNode`, and `BaseMPVView`.
- Port the required node/event extensions from mpvEx or implement a small
  source-compatible adapter over upstream internals.
- Add JVM compile/API tests and JNI round-trip tests where practical.
- Avoid unrelated media-kit bridge or PlayBridge-native API work.

Exit gate: PlayBridge compiles against the control AAR with no application
source changes beyond dependency selection.

### Phase 3: Validate legacy-equivalent runtime behavior

- Install a TV build using the control AAR behind an explicit experimental
  dependency flag.
- Test initialization, headers, HLS, hardware decoding, track parsing,
  subtitles, seeking, speed, pause, resume, stop, and release.
- Test surface create/change/destroy, activity recreation, background and
  foreground transitions, and repeated file loads.
- Assert initial state explicitly instead of assuming a property observer will
  replay a value registered before the observer.
- Confirm fallback to ExoPlayer occurs once, without repeated intents.

Exit gate: normal-media and lifecycle behavior matches the legacy AAR on TV.

### Phase 4: Add the disguised-PNG MPEG-TS fix

- Add the smallest FFmpeg/mpv patch identified by the probe comparison.
- Test the local `test.ts` fixture first.
- Test a sanitized or locally replayed HLS manifest whose segment response is
  `Content-Type: image/png` but contains the fixture structure.
- Test live HLS only as a final manual check because signed URLs expire and
  must not enter logs, fixtures, commits, or CI.
- Re-run the complete normal-media matrix.

Exit gate: the target fixture and HLS case play without regressing control
media.

### Phase 5: Publish and consume the experimental AAR

- Add fork CI for every supported ABI and the library release variant.
- Publish AAR checksums, source revision metadata, license notices, and
  separate debug symbols.
- Pin the experimental AAR version in PlayBridge.
- Keep the legacy AAR available behind a simple rollback during field testing.
- Cache native dependency builds in the fork CI; do not make ordinary
  PlayBridge CI rebuild mpv.

Exit gate: clean PlayBridge TV CI consumes the pinned AAR and the device matrix
passes.

### Phase 6: Add the source and F-Droid path

- Add the fork as the pinned `shared/mpv-android` source checkout.
- Add conditional source substitution to both Android settings files.
- Make source preparation distinct from native compilation: Gradle/native
  build tasks must not download executable artifacts or floating sources.
- Adapt F-Droid metadata to provide pinned dependency sources using supported
  `srclibs`, submodules, or equivalent reviewable inputs.
- Reuse the official mpv-android F-Droid recipe as precedent, then update it
  for the fork's current dependencies and NDK rather than copying its old
  versions blindly.
- Run the source build with network disabled after source preparation and run
  F-Droid scanner checks.

F-Droid references:

- [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Current mpv-android metadata](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/is.xyz.mpv.yml)

Exit gate: F-Droid can build the AAR and PlayBridge app from publicly visible,
pinned source without relying on the published native AAR.

### Phase 7: Cut over and remove the legacy binary

- Make the PlayBridge-built coordinate the default dependency.
- Remove `tv/android/player/app/libs/mpv-android.aar` and its packaging rules.
- Remove the temporary rollback and source-selection flags after a stable
  release window.
- Update shared/TV documentation and third-party license records.
- Keep the fork commit, dependency lock, patches, and regression evidence.

Exit gate: no PlayBridge build or runtime path references the mpvEx AAR, while
published and source-built modes expose the same API and behavior.

## Verification matrix

### AAR and native artifact checks

- Expected `classes.jar` API, assets, ABIs, and native library names only.
- No duplicate `libmpv`, FFmpeg, `libplayer`, or C++ runtimes in the final APK.
- Expected ELF architecture, minimum API, 16 KiB page alignment, and `NEEDED`
  libraries.
- Release symbols are stripped; matching debug symbols are retained
  separately.
- Clean rebuilds record the same source/tool inputs and normalized AAR
  contents.
- The AAR cannot be assembled successfully if required source inputs are
  missing; it must never silently fall back to the legacy AAR.

### Runtime checks

Follow the tester-ready procedures and acceptance criteria in
[`testing-android-mpv-source-build.md`](testing-android-mpv-source-build.md).
Run on armv7 and arm64 hardware where available:

- ordinary H.264/AAC local file;
- ordinary HTTPS MP4;
- HLS master/media playlists and variant selection;
- live HLS refresh with required request headers;
- `test.ts` disguised-PNG fixture;
- locally replayed `image/png` HLS segment case;
- hardware decoding and software fallback;
- audio, video, and subtitle track selection;
- external subtitle load, delay, and visibility;
- play, pause, resume, seek, speed, stop, and end-of-file;
- initial pause-state synchronization;
- surface attach, resize, detach, reattach, and activity recreation; and
- a failed MPV load followed by exactly one ExoPlayer fallback.

### Consumer builds

From `tv/android`:

```shell
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew lint"
```

From `mobile/android`, after source substitution is introduced:

```shell
zsh -c "source ~/.zshrc && ./gradlew :app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:lintFossDebug"
```

The phone does not need to ship libmpv merely because it can resolve the
shared module. Add it to a phone variant only when the phone product actually
uses MPV.

## CI and build-time policy

Native builds will remain substantially slower than Kotlin/Gradle builds.
Keep them out of ordinary PlayBridge CI:

- fork CI builds and tests native code when the fork, build scripts, patches,
  dependency lock, or toolchain changes;
- fork release CI publishes the AAR and debug symbols;
- PlayBridge CI consumes a pinned AAR and performs normal app builds/tests;
- a scheduled or manually triggered PlayBridge job rebuilds from the pinned
  source checkout and compares it with the declared artifact; and
- F-Droid always follows the source-build route.

Use per-ABI and per-dependency caches keyed by source revision, NDK, API level,
configure flags, and patches. Never let a cache key hide changes to native
inputs.

## Risks and controls

| Risk | Control |
|---|---|
| Upstream AAR lacks mpvEx APIs used by PlayBridge | Freeze the legacy API inventory and port only required compatibility extensions |
| A new wrapper repeats blank-video/control regressions | Retain upstream JNI and surface lifecycle; test control and initial property state before customization |
| The MPEG-TS fix damages ordinary images or HLS | Isolate the probe change and test both positive and negative fixtures |
| Native builds slow every PlayBridge build | Publish the source-built AAR and reserve source builds for fork CI, reproducibility, and F-Droid |
| Fork drifts too far from upstream | Keep custom patches small, documented, and rebased through reviewed upstream merges |
| F-Droid cannot audit downloaded native binaries | Provide pinned source through metadata/submodules and build the AAR inside the F-Droid recipe |
| GPL/LGPL obligations are missed | Record enabled features, licenses, source revisions, notices, and source availability with each release |
| Duplicate FFmpeg/runtime libraries reach the APK | Inspect AAR/APK contents and ELF dependency graphs in CI |

## Definition of done

The migration is complete when:

- the fork builds a versioned AAR from pinned source inputs;
- its library module preserves the PlayBridge-required legacy API;
- the fork app and PlayBridge TV pass the normal playback/control/lifecycle
  matrix on supported ABIs;
- `test.ts` and the locally replayed disguised-PNG HLS case play correctly;
- ordinary PlayBridge builds consume the PlayBridge-published AAR;
- the source checkout can build the same library for reproducibility and
  F-Droid;
- Android phone can opt into the same coordinate without maintaining another
  native implementation; and
- the mpvEx-derived legacy AAR and temporary experiments are no longer needed.
