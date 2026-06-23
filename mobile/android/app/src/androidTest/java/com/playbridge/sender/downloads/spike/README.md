# Phase-0 Spike — HLS → MP4 transmux (FFmpeg vs Media3 decision)

This spike answers one question before the download rewrite commits to an approach:

> Can Media3 `Transformer` **transmux** (stream-copy, no re-encode) an HLS stream into a
> single playable MP4 — for MPEG-TS, fMP4/CMAF, and AES-128 — **without FFmpeg**?

If yes, the rewrite ships the `Muxer` seam backed by Media3 (already in our dependency
tree, no native blob, no APK bloat, no FFmpegKit maintenance risk) and keeps FFmpeg only
as a later escape hatch. See `../../../../../../../../../DOWNLOAD_REWRITE_PLAN.md` §4.

## Files

| File | Role |
|------|------|
| `HlsTransmux.kt` | The `Muxer` seam + `Media3Muxer` (Transformer-based) + MP4 probe helpers |
| `HlsTransmuxSpikeTest.kt` | Instrumented test: transmux 3 stream types, verify output is a real MP4 |

## Running it

Needs a **device or emulator** (Transformer drives `MediaCodec`; this won't run as a JVM
unit test) with **network access**.

```bash
cd mobile/android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.playbridge.sender.downloads.spike.HlsTransmuxSpikeTest
```

Then read the summary block in logcat (tag `HlsTransmuxSpike`) or the test stdout:

```
===== HLS→MP4 transmux spike =====
[PASS] TS / H.264+AAC      (12.4 MB, 60000ms, video=true audio=true)
[PASS] fMP4 / CMAF         (9.1 MB, 30000ms, video=true audio=true)
[PASS] AES-128 encrypted   (5.7 MB, 46000ms, video=true audio=true)
==================================
```

## Reading the result

| Outcome | Meaning | Action |
|---------|---------|--------|
| **PASS** | Transmux produced a non-empty MP4 with the expected video/audio tracks | That stream type is viable on Media3 |
| **SKIP** | Network/CDN couldn't be reached (Media3 IO error code 2000–2999) | Inconclusive — swap the URL, re-run. **Not** a pass |
| **FAIL** | Transformer reported a real export/mux error, or output was unplayable | Media3 can't transmux that case → weigh FFmpeg for it |

The test deliberately **fails the build on any FAIL** and also **fails if every stream
SKIPped** (so a network outage can't masquerade as success). A SKIP alone is neither pass
nor fail — it just means "try again / different URL".

> The public test URLs (Mux, Apple, JWPlayer) rotate and occasionally go down. The
> **AES-128** entry especially should be repointed at content you control before you treat
> its result as decisive.

## Mapping to the real strategy

The spike feeds **remote** playlists straight to `Transformer`. Production `HlsStrategy`
will differ in exactly two spots — both small, neither affects the muxing capability this
spike proves:

1. **Local segments.** After our downloader fetches segments (for retry/resume/headers),
   rewrite the media playlist to `file://` segment paths, keep the `#EXT-X-KEY` and
   `#EXT-X-MAP` lines, and hand *that* local `.m3u8` to the same `Media3Muxer.transmux()`.
   Media3 then demuxes, decrypts AES-128, and resolves fMP4 init segments itself.

2. **Header injection** (for the manifest/key fetches that remain remote, e.g. the key URI).
   Set a custom `DataSource.Factory` on the asset loader:

   ```kotlin
   // sketch — validate the ExoPlayerAssetLoader.Factory overload against media3 1.9.2
   val dsf = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
   val transformer = Transformer.Builder(context)
       .setAssetLoaderFactory(
           ExoPlayerAssetLoader.Factory(context).setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
       )
       .build()
   ```

   Verifying this overload compiles/works on 1.9.2 is the **one** extra thing to confirm
   when wiring the real strategy; the transmux itself is already proven here.

## When the spike is done

- All three PASS (with an AES stream you trust) → delete this `spike/` package, move
  `media3-transformer` from `androidTestImplementation` to `implementation`, and build the
  real `Muxer` seam + `HlsStrategy` per the plan (Phase 1).
- Any FAIL → record which case in the plan's risk table and scope FFmpeg to just that case.
