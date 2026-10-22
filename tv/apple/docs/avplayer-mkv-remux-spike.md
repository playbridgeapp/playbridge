# Spike: AVPlayer playback of Matroska via on-device HLS remux

Status: **parked** (2026-10-22). Branch `spike/appletv-avplayer-mkv-remux`, not merged.

## Goal

Play MKV sources in AVPlayer instead of mpv on Apple TV, mainly to get what
AVPlayer does and mpv can't on tvOS:

- Dolby Vision output (mpv shows profile 8 as HDR10)
- Atmos passthrough (E-AC-3 JOC)
- system features: PiP, AirPlay audio routing, frame-rate/range matching

AVPlayer only opens MP4/MOV/HLS, so the source is **remuxed (no re-encode)**
on the device into HLS fMP4 and served to AVPlayer over loopback.

## Design (on the branch)

| File | Role |
|---|---|
| `Player/Remux/HLSRemuxer.swift` | libavformat (MPVKit's FFmpeg 8.1) reads the MKV and cuts fMP4 segments on the Matroska keyframe index (Cues). Copies H.264/HEVC (`avc1`/`hvc1`/`dvh1`, Dolby Vision config kept via `strict=-1`) and AAC/AC-3/E-AC-3/ALAC. Builds master and media playlists. |
| `Player/Remux/LocalHLSServer.swift` | Minimal NWListener HTTP/1.1 server on `127.0.0.1`. One session per item, lazily opened on the first request, so a rejected source fails the AVPlayer item and the existing AVPlayer→mpv fallback takes over. Read-ahead chain (6 segments / 96 MB). |
| `Player/Remux/RemuxRoute.swift` | Candidate test (`.mkv`/`.mk3d`) and loopback path parsing; host-tested. |
| `NativePlayerView.swift` | Swaps the asset URL when Settings → "Play MKV in Native Player (Experimental)" is on; retries once with the media playlist on AVError -11868. |

Remux details worth keeping:

- **Decode timestamps are rebuilt per segment.** Matroska stores presentation
  times only (`dts=N/A` for B-frame streams). Sorted PTS minus a fixed reorder
  delay (≥ 4 frames) gives monotonic DTS that is continuous across
  independently muxed segments; a constant +1 s shift keeps DTS positive.
- **Init segment via `delay_moov`.** The mp4 muxer needs a parsed AC-3/E-AC-3
  packet before writing `moov`; segment 0 is muxed with `delay_moov`, its
  `ftyp`+`moov` become `init.mp4`, and the media boxes are served as segment 0.
  With `delay_moov` the first flush writes only `moov`: flush twice.
- **One muxer per segment** with `frag_custom+empty_moov+delay_moov+default_base_moof+frag_discont`,
  `use_editlist=0`, increasing `fragment_index`, so any segment can be produced
  on demand (seeking) with an absolute `tfdt`.
- **Sequential reads.** When AVPlayer asks for segment N+1, the remuxer continues
  from the packets that crossed the previous boundary instead of seeking — one
  HTTP connection to a debrid host instead of a reconnect per segment.
- **VIDEO-RANGE from the SPS.** Matroska remuxes often have no Colour element;
  the transfer function is read by opening the decoder on the extradata. A
  wrong `VIDEO-RANGE` makes AVPlayer reject the variant (-12927 on macOS).

## Results (Apple TV 4K 3rd gen, tvOS 26, debrid HTTPS sources)

| Case | Result |
|---|---|
| 1080p H.264 + E-AC-3, 41 min | **Plays in AVPlayer** start to end; seek and resume work |
| Remux speed, 1080p | 20–200 ms per ~4 s segment (sequential) |
| Remux speed, 4K Dolby Vision (~55–60 Mbps peaks) | 1.2–2.5 s per ~4 s segment ≈ 100 Mbps from the host, ~1.7× headroom |
| 4K HEVC Dolby Vision 8.1 + E-AC-3 | **Blocked** — see below; routed to mpv |
| Atmos | Not confirmed (E-AC-3 is passed through untouched; no receiver check yet) |
| Long playback | **Stalls partway** on some episodes — see below |

macOS AVFoundation plays every generated stream (H.264/AAC, H.264/AC-3,
HEVC HDR10/E-AC-3, synthetic Dolby Vision 8.1/E-AC-3) through the master
playlist, including precise seeks.

### Blocker 1: tvOS rejects HDR (PQ) variants — AVError -11868

`AVErrorNoCompatibleAlternatesForExternalDisplay` (underlying CoreMedia -17223)
right after AVPlayer loads the first PQ variant's media playlist, before the
init segment. Observed with:

- `AVPlayer.eligibleForHDRPlayback = true`, `availableHDRModes` = HDR10 + Dolby Vision, 3840×2160 output
- Settings: Format 4K SDR, and both with Match Dynamic Range **off and on**
- both the Dolby Vision variant (`SUPPLEMENTAL-CODECS="dvh1.08.06/db1p"`) and a plain HDR10 variant

A four-variant diagnostic master (DV / HDR10 / HDR10 without RESOLUTION and
FRAME-RATE / CODECS only) showed AVPlayer loading the DV variant, then skipping
every `VIDEO-RANGE=PQ` variant and settling on the one without `VIDEO-RANGE`,
i.e. playing tone-mapped SDR (no HDR/DV badge on the TV). Serving the media
playlist alone also plays, again as SDR. Segment length is not the cause
(13 s targets play on macOS).

Not yet ruled out: the HDMI chain failing the mode switch (HDCP, receiver/TV
EDID). **First next step:** check whether Apple's own TV app switches this TV
to Dolby Vision. If it does, the stream signalling is at fault; if not, no app
can get Dolby Vision through AVPlayer on that setup.

Because of this, the branch's final state declines HDR10/HLG/Dolby Vision at
open (`Failure.highDynamicRange`) so those go straight to mpv; only SDR is
remuxed.

### Blocker 2: playback stalls partway

On a later run AVPlayer requested segments far ahead (to the end of one file,
~18 min ahead in the next), every request was answered, and playback still
stopped. So it is not remux throughput. Suspects, in order:

1. Segment cuts on Matroska "keyframes" that are not IDR/recovery-safe (open
   GOP H.264/HEVC); `#EXT-X-INDEPENDENT-SEGMENTS` promises each segment decodes
   on its own.
2. Timestamp continuity at segment joins (rebuilt DTS, carried boundary packets).
3. Audio/video interleave or gaps where audio packets straddle a boundary.

**Next step:** log AVPlayer's `reasonForWaitingToPlay`, access/error log events
and `AVPlayerItem.loadedTimeRanges` at the stall; cut only at IDR (H.264 NAL 5,
HEVC IRAP 16–21) found by parsing the first NAL units of each candidate packet;
validate a whole file with `mediastreamvalidator` on macOS.

### Fixed during the spike

- Re-seeking every segment (a new HTTP connection each time): sequential reads.
- Idle between requests on high-bitrate files: continuous read-ahead.
- Memory: 256 MB read-ahead plus several copies per segment likely got the app
  killed; now 96 MB and box extraction without copying.
- Crash after the last segment: the read-ahead range trapped when `lower > upper`.

## Simulator caveats

- The tvOS simulator reports `eligibleForHDRPlayback = false`, so it cannot test
  HDR/Dolby Vision variant selection.
- In later runs the simulator app's NWListener bound its loopback port but never
  delivered connections (AVPlayer got -1005, curl got resets/hangs) while the
  same code worked on macOS and on the device. Unresolved; test on hardware.

## Reproducing

Mac prototype (same `HLSRemuxer.swift`, Homebrew FFmpeg 8.1 with module maps for
`Libavformat`/`Libavcodec`/`Libavutil`): remux to a directory, serve with
`python3 -m http.server`, check with `ffmpeg -i …/master.m3u8 -f null -` and an
AVPlayer script (ready / play / seek / tracks / error log).

Dolby Vision 8.1 test file:

```sh
dovi_tool generate -j dv.json -o rpu.bin   # {"cm_version":"V40","length":720,"level6":{...}}
ffmpeg -f lavfi -i testsrc2=size=3840x2160:rate=24:duration=30 -pix_fmt yuv420p10le -strict -1 -f yuv4mpegpipe - \
  | x265 --input - --y4m --output-depth 10 --profile main10 --vbv-bufsize 20000 --vbv-maxrate 20000 \
    --colorprim bt2020 --transfer smpte2084 --colormatrix bt2020nc --hdr10 \
    --dolby-vision-profile 8.1 --dolby-vision-rpu rpu.bin -o dv81.hevc
mkvmerge -o dv81.mkv --default-duration 0:24p dv81.hevc -D audio-source.mkv
```

(FFmpeg 8.1 cannot copy that raw HEVC into MKV — no timestamps — hence mkvmerge.)

## Recommendation

Do not ship. The main payoff (Dolby Vision via AVPlayer) is blocked on tvOS for
reasons not yet isolated, and SDR playback still stalls partway. Resume only if
Blocker 1's TV-app check shows the chain can do Dolby Vision, or if Atmos
passthrough alone (SDR + E-AC-3 JOC) proves worth the code once the stall is
fixed.
