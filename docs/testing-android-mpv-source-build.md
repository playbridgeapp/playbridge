# Android libmpv Manual Test Plan

This document validates the source-built `mpv-android.aar` used by the
PlayBridge Android TV player. It is intended for human testing on real Android
TV hardware before the AAR or its pinned fork revision is promoted.

The primary risks are native ABI compatibility, a blank or stale video
surface, broken remote controls, playback regressions, repeated ExoPlayer
fallbacks, and regression of the disguised-PNG MPEG-TS fix used by `test.ts`.

## Scope

This plan covers:

- the TV player consuming the committed source-built AAR;
- both supported ABIs: `armeabi-v7a` and `arm64-v8a`;
- ordinary local and network media;
- protected and ordinary HLS playback;
- the local `test.ts` disguised-PNG MPEG-TS fixture;
- MPV controls, tracks, subtitles, playlists, and lifecycle behavior; and
- the single-transition MPV-to-ExoPlayer fallback path.

It does not require retesting every browser, Desktop, Apple, or web feature.
The Android phone sender only needs enough coverage to confirm that commands
and cast payloads still reach the TV player correctly.

## Release gates

Use these priorities when deciding how much testing is required:

| Gate | Required coverage | Result needed |
|---|---|---|
| P0 smoke | Install, launch, ordinary video, `test.ts`, play/pause, seek, stop, surface/lifecycle | All pass on at least one physical TV |
| P0 ABI | P0 smoke on one armv7 TV and one arm64 TV | Both pass before calling the dual-ABI AAR production-ready |
| P1 playback | HLS, protected headers, audio/subtitle tracks, external subtitles, playlist transitions, fallback | All applicable cases pass |
| P2 compatibility | Additional codecs, long playback, poor-network recovery, repeated lifecycle stress | No release-blocking regression |

An AAR must not be promoted if any P0 test fails. A P1 failure needs either a
fix or a documented decision that the behavior already exists in the legacy
AAR. P2 findings may be tracked separately when they are not regressions.

## Test record

Create one copy of this table for each device:

| Field | Value |
|---|---|
| Date and tester | |
| TV manufacturer and model | |
| Android TV version / API | |
| Device ABI | |
| APK build or commit | |
| AAR SHA-256 | |
| mpv-android fork revision | |
| Phone sender version | |
| Network type | Ethernet / Wi-Fi |

Record each test as `PASS`, `FAIL`, `BLOCKED`, or `N/A`. For failures, include
the media type, the action that failed, whether audio continued, whether the
screen was black or frozen, and the first relevant log error. Do not include
authenticated stream URLs, tokens, cookies, or request headers in reports.

## Preparation

### 1. Confirm the device ABI

With ADB connected:

```shell
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
```

Expected: the device reports either `armeabi-v7a` or `arm64-v8a` as a
supported ABI. Do not assume an arm64 device proves that the armv7 native
libraries work; the two AAR paths require separate runtime tests.

### 2. Build or obtain the test APK

From `tv/android`:

```shell
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleFossDebug"
```

Use the ABI-specific APK when validating one architecture. The universal APK
is useful for general testing but does not replace checking which native ABI
Android selected at runtime.

Before installation, run the repository verifier from the repository root:

```shell
scripts/ci/verify-mpv-android-aar.sh
```

Record the printed AAR checksum in the test record.

### 3. Install cleanly

For the first pass on each ABI, uninstall the existing player so stale native
libraries or settings cannot hide an integration problem:

```shell
adb uninstall com.playbridge.player
adb install path/to/test.apk
```

An upgrade installation with `adb install -r` should be tested separately
after the clean-install smoke test passes.

### 4. Prepare media

Have the following available:

- `~/test.ts`, the known disguised-PNG MPEG-TS fixture;
- a normal H.264/AAC MP4 file with working audio;
- an HTTPS MP4 URL;
- an ordinary HLS master playlist with multiple qualities;
- a live HLS stream requiring `Origin`, `Referer`, and `User-Agent` headers;
- media with at least two audio tracks;
- media with an embedded subtitle track;
- an external SRT or WebVTT subtitle URL or file;
- a two-item playlist; and
- an intentionally invalid or unsupported media URL for fallback testing.

Use only streams you are authorized to test. Signed URLs are usually
short-lived, so confirm the protected HLS fixture still works in its control
player immediately before treating a PlayBridge failure as a regression.

### 5. Capture useful logs

Clear old logs immediately before reproducing a problem:

```shell
adb logcat -c
adb logcat -v time MpvPlayerActivity:V MpvPlayerEngine:V mpv:V ExoPlayerActivity:V ExoPlayerEngine:V '*:S'
```

If the filtered log omits the cause, collect a full log for the shortest
possible reproduction and redact URLs, headers, tokens, and personal data
before sharing it.

## P0 smoke tests

Run every test in this section on both an armv7 and arm64 physical TV.

### P0-1: Clean install and launch

1. Install the APK after uninstalling the previous version.
2. Launch PlayBridge from the Android TV launcher.
3. Pair or connect the phone sender.
4. Select MPV as the playback engine.

Pass criteria:

- the application launches without a native linker or missing-library error;
- pairing and the idle player UI remain responsive; and
- logs do not report a missing `libmpv`, FFmpeg, C++ runtime, or JNI symbol.

### P0-2: Ordinary local video

1. Cast the normal H.264/AAC MP4 from the phone to the TV using MPV.
2. Watch at least 30 seconds.
3. Confirm video motion, audio, aspect ratio, and lip sync.

Pass criteria:

- the first frame appears without a persistent black screen;
- video and audio continue smoothly;
- the image is correctly scaled and oriented; and
- MPV remains the active engine without an automatic fallback.

### P0-3: Disguised-PNG `test.ts`

1. Cast `~/test.ts` from the phone to the TV using MPV.
2. Watch through several seconds of moving video and audio.
3. Stop and replay it once.

Pass criteria:

- MPV plays actual video rather than treating the file as a 1x1 PNG;
- video is visible, not merely audible behind a black screen;
- the stream does not end immediately with `Invalid data found when processing input`;
- replay works; and
- MPV does not fall back to ExoPlayer.

The expected probe behavior is MPEG-TS. A log selecting `png_pipe` or a PNG
video decoder for this fixture is a failure.

### P0-4: Remote play/pause

Test each available input separately:

1. Press the remote's dedicated media play/pause key.
2. Resume with the same key.
3. Open the full controls with D-pad Center/Enter.
4. Select the on-screen play/pause control and resume.
5. If the phone remote exposes play/pause, pause and resume from the phone.

Pass criteria:

- playback state changes exactly once per input;
- paused video remains on the last frame rather than becoming black;
- the control icon/state agrees with actual playback; and
- no input launches a second player activity or restarts the media.

### P0-5: Seek and volume

1. With controls hidden, press D-pad Right and commit the seek.
2. Press D-pad Left and commit the seek.
3. Hold Left or Right to exercise accelerated scrubbing.
4. Use D-pad Up/Down where supported to adjust volume.

Pass criteria:

- the seek preview and final position move in the requested direction;
- playback resumes after seeking without a frozen or black surface;
- audio returns and remains synchronized; and
- volume changes once per command.

Live streams without a seekable window may mark seek cases `N/A`; they do not
replace the seek test on a normal file.

### P0-6: Stop, Back, and replay

1. Stop playback with the media-stop key or phone remote.
2. Start the same media again.
3. Press Back once while controls are visible, then again after they hide.

Pass criteria:

- Stop exits playback cleanly;
- Back first closes overlays/controls where appropriate and then exits;
- replay starts a fresh working session; and
- logs do not show repeated `release()`, repeated fallback launches, or an
  `onNewIntent` storm.

### P0-7: Surface and lifecycle

While ordinary video is playing:

1. Press Home, wait five seconds, and return to PlayBridge.
2. Turn the TV display off/on or trigger the device's equivalent surface
   recreation.
3. Open and close the settings or player overlay several times.
4. Start another video after returning.

Pass criteria:

- video is visible after every return;
- audio does not continue behind a permanently black surface;
- playback does not create overlapping audio sessions;
- controls remain usable; and
- a subsequent cast starts normally.

## P1 playback regression tests

### P1-1: HTTPS MP4

Cast a normal HTTPS MP4, play for one minute, seek, pause, and resume.

Pass criteria: startup, TLS download, seeking, audio/video, and controls all
work without fallback.

### P1-2: Ordinary adaptive HLS

1. Cast an HLS master playlist with at least two variants.
2. Confirm playback begins and continues across several segment boundaries.
3. Change quality if the UI permits it, or verify the requested default
   quality was selected.

Pass criteria: the master and media playlists load, segments advance, the
selected resolution is sensible, and no variant switch leaves a black screen.

### P1-3: Protected live HLS and disguised segment URLs

1. Confirm the live URL currently works in the known control player.
2. Cast the master playlist with its required `Origin`, `Referer`,
   `User-Agent`, and other non-secret request headers.
3. Watch for at least two minutes so the media playlist refreshes repeatedly.
4. Pause/resume if supported and allow at least one buffer recovery.

Pass criteria:

- headers are applied to the master playlist, child playlist, and segments;
- segments served as `image/png` but containing MPEG-TS play as video;
- playlist refresh continues rather than ending after the initial window;
- audio/video remain synchronized; and
- MPV does not select a PNG stream or immediately reach EOF.

An expired signed URL or an HTTP 403 in the control player is `BLOCKED`, not a
PlayBridge failure.

### P1-4: Audio tracks

1. Play media containing at least two audio tracks.
2. Open the track picker and switch tracks twice.
3. Pause/resume and seek after switching.

Pass criteria: labels are present, the selected language/audio changes, the
selection remains stable after seek, and playback does not restart from zero.

### P1-5: Embedded and external subtitles

1. Enable an embedded subtitle track, then disable it.
2. Load an external SRT or WebVTT subtitle.
3. Confirm text is visible and glyphs render correctly.
4. Adjust subtitle delay in both directions.
5. Seek to another cue and switch between tracks/off.

Pass criteria: subtitles render over video, the fallback font works, delay is
observable, seeking updates cues, and disabling subtitles removes them.

### P1-6: Playback settings

Exercise every MPV setting exposed by the current UI, including playback
speed, video scaling/aspect mode, and audio boost when available.

Pass criteria: each setting takes effect without restarting playback, remains
controllable, and does not blank the surface or permanently distort audio.

### P1-7: Playlist and transitions

1. Cast a two-item playlist.
2. Use Next and Previous.
3. Let the first item reach natural EOF and auto-advance.
4. Switch to a different stream while the current item is still loading.

Pass criteria:

- each action advances exactly once;
- aborted-item `END_FILE` events do not skip additional entries;
- item-specific headers, titles, and subtitles follow the selected item; and
- the final item exits cleanly at EOF.

### P1-8: MPV-to-ExoPlayer fallback

1. Select MPV and cast an intentionally invalid or unsupported URL.
2. Observe the transition to ExoPlayer.
3. After fallback settles, stop playback and cast a valid MPV item.

Pass criteria:

- exactly one automatic fallback is launched;
- only one ExoPlayer activity/session remains active;
- there is no repeated “Failing over to ExoPlayer” loop;
- surfaces do not churn continuously; and
- the next valid MPV cast still works.

If the invalid URL causes both engines to fail, that is acceptable provided
the fallback happens once and the UI returns to a stable state.

### P1-9: Upgrade installation

1. Install the previous released APK.
2. Upgrade with the new APK using `adb install -r`.
3. Launch and repeat P0-2 through P0-4.

Pass criteria: the upgrade succeeds, MPV loads the new native libraries, user
settings remain usable, and ordinary video plus `test.ts` still play.

## P2 compatibility and stress tests

Run the cases for which representative media and device support are
available:

- H.265/HEVC video;
- VP9/WebM;
- MKV with multiple tracks;
- MPEG-TS that does not use disguised PNG naming or MIME types;
- fMP4 HLS;
- 4K and high-frame-rate content;
- HDR content on an HDR-capable TV;
- software-decoding fallback for a codec unsupported by MediaCodec;
- a still PNG image, if the casting path exposes image playback;
- 30–60 minutes of continuous live playback;
- repeated pause/resume and 20 sequential seeks;
- ten sequential casts without restarting the app;
- Wi-Fi disconnect/reconnect during playback; and
- low-bandwidth buffering followed by recovery.

Pass criteria: behavior is no worse than the legacy AAR on the same TV and
fixture. Hardware decoding should be used where expected, software fallback
must remain functional where supported, and no case should leak activities,
audio sessions, or permanently lose the video surface.

## Phone sender regression checks

The phone does not currently consume libmpv, so it does not need the complete
native playback matrix. Confirm only that it can:

- discover and connect to the TV;
- select MPV as the requested player;
- cast an ordinary file, `test.ts`, an HTTPS URL, and protected HLS headers;
- send play, pause, seek, stop, and track commands;
- send a two-item playlist; and
- reconnect and cast again after the TV player exits.

## Expected log signals

Healthy MPV playback should include a start/file-loaded sequence, selected
audio/video tracks, decoded frames, and playback restart after seeks. The
following are release-blocking when reproducible:

- native linker errors or missing JNI symbols;
- `png_pipe` or the PNG decoder selected for `test.ts`/MPEG-TS HLS segments;
- immediate repeated `Invalid data found when processing input` followed by
  EOF for the disguised-PNG fixture;
- persistent audio with a black video surface;
- repeated `END_FILE`-driven fallback launches;
- repeated ExoPlayer `onNewIntent`/surface creation caused by one failure; or
- a crash, ANR, or watchdog timeout during ordinary playback.

Warnings from device-specific codecs, buffer queues during a deliberate
activity teardown, or malformed third-party transport packets are not by
themselves failures when playback remains stable. Record them when they
coincide with visible or audible problems.

## Comparison procedure for failures

When a test fails:

1. Reproduce once with the same APK and collect a short redacted log.
2. Confirm the fixture still works in its control player or source.
3. Install the previous PlayBridge APK containing the legacy AAR on the same
   device and repeat the exact steps.
4. Classify the result as a new source-built-AAR regression, an existing
   PlayBridge issue, a device-specific issue, or a broken/expired fixture.
5. Record the first bad build or AAR checksum if known.

Do not replace a failing AAR or change multiple player settings mid-test
without recording the change; reproducible comparisons require one variable
at a time.

## Final sign-off

The source-built AAR is ready for promotion when:

- all P0 tests pass on physical armv7 and arm64 TVs;
- `test.ts` and the protected disguised-PNG HLS case select MPEG-TS and play;
- ordinary HLS and MP4 playback have no regression;
- play/pause, seek, stop, tracks, subtitles, and playlist transitions pass;
- lifecycle tests do not produce a black screen;
- an MPV failure triggers no more than one ExoPlayer fallback;
- upgrade installation works; and
- all unresolved P1/P2 findings are documented with owner and disposition.

Attach the completed device records and redacted logs to the release issue or
pull request. Keep authenticated media URLs and headers out of the repository.
