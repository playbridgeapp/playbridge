# Apple TV playback checks

The receiver has two playback engines: MPV (default) and AVPlayer. VLC and
its proxy/dependency are removed. Auth/capability replies advertise only
`avplayer` and `mpv`. Reconnect a sender to refresh cached capabilities.
Legacy `vlc` player-mode/control requests route to MPV, and the persisted
`preferredPlayer=vlc` setting migrates to `mpv` on app startup.

## Audio verification on the Apple TV

The Elecard CityHall 1080p60 AV1 WebM used for renderer testing has **no audio
track**. It cannot verify sound. Use an MP4/HLS or movie known to include
audio, preferably ordinary AAC stereo first.

- Play a known-audio MP4 and HLS using both engines.
- In MPV, select a secondary audio track, then advance to an item whose
  audio IDs differ or which has only one track. Automatic selection must
  provide sound if the old named preference cannot be found.
- Exercise preplay: audio must stay muted under the preplay curtain, then
  become audible after Start. Repeat casts with and without visual metadata.
- Test pause/resume, seek, looping, manual queue jumps, EOF advance and
  AVPlayer/MPV switching. Stop during buffering and start another cast.
- Check representative HDR, subtitle and multichannel audio content.

MPV supplies per-file `aid=auto` and preplay mute to `loadfile` (with the
mpv 0.38+ insertion-index argument), avoiding a transient track change in
the outgoing item. It explicitly applies preplay mute both ways. Remembered track names are restored after discovery;
volume is not reset. Audio session activation errors are no longer swallowed. The TV MPV session
uses the same default routing policy as AVPlayer rather than long-form
audio routing. MPV requests AVFoundation audio first, which fixed the HDMI
silence caused by the old fork's AudioUnit-only driver; see
[mpv-audio-check.md](mpv-audio-check.md). This is not a certified fix for
every audio codec or output route.

For a Debug run, share `[MPV audio-session]`, audio-output initialization
warnings and audio-session errors, not network headers. Session samples
contain generic port types (never device names/IDs), sample
rate, output channel counts, latency, routing policy and preplay state.
System volume readings do not necessarily reflect an external HDMI receiver's
volume. See [mpv-audio-check.md](mpv-audio-check.md) for the phone/TV build comparison.

## AV1 investigation: deprioritized

On the tested Apple TV 4K (3rd generation, A15, 128 GB), MPV AV1 playback
was visibly choppy at 1080p and worse at 4K. AVPlayer did not play the tested
AV1 sample. Native frame counters reported no drops despite visible stutter;
fixed SDR 60 Hz with both display-matching settings off did not resolve it.
A capped 1080p SDR GPU experiment with measured refresh-rate synchronization
also failed to produce smooth playback. These observations did not establish
whether decoding, frame upload or presentation was the bottleneck.

The user also reported the same AV1 playback issue in **Infuse** on Apple TV.
This is a user-reported comparison, not a controlled benchmark or proof of a
shared root cause. It nevertheless argues against treating the problem as
unique to PlayBridge. The user chose to stop the investigation; further
speculative AV1 tuning is deprioritized unless new profiling evidence or a
concrete upstream fix gives reason to revisit it.

These observations were made with the old fork's native AVFoundation video
output. MPV now renders with `gpu-next`, like the phone; 4K HEVC HDR played
smoothly on the same A15, but AV1 has not been retested. AV1 on the A15
remains software/best-effort; prefer H.264/HEVC when available. Host checks do
not certify playback smoothness.

## Host checks

```bash
bash tv/apple/tests/run-receiver-review-checks.sh
bash tv/apple/tests/run-progress-webhook-checks.sh
cd protocol && ruby scripts/check-spec.rb
```

Build Debug and Release from `PlayBridge TV.xcodeproj` (Swift Package Manager
only; there is no CocoaPods workspace). Generic unsigned
builds validate compilation/linking, not signing, installation or audible
playback. The old VLC proxy test runner no longer exists because that
production path no longer exists.

## Mixed-media receiver parity (manual checks still required)

Host/model checks and unsigned generic tvOS builds do not establish decoding, visible
rendering, sound, focus, or network behavior on a real Apple TV. Run the following
with both MPV and AVPlayer selected, using synthetic/public samples or private
samples kept outside the repository. Reconnect the phone to refresh capabilities.

- Verify auth and protected credentials include `video`, `audio`, `image`, both
  engine descriptors include image transforms, and no browser/mirroring/system-volume
  or diagnostic-log capability appears. Subscribe twice on one authenticated WSS
  socket: each response is a complete `receiver_capabilities` frame with numeric
  revision 1. An unauthenticated or unsubscribed socket receives none; reconnect
  creates a fresh subscription.
- Supply stable item IDs to a playlist and queue append. Check `currentItemId`,
  item IDs in `playlist_status`, and webhook IDs match them. Omitted IDs are assigned.
  Duplicate/blank/over-128-UTF-16-unit IDs reject the entire mutation with
  `invalid_command`, leaving playback and queue revision unchanged.
- Cast JPEG, PNG, WebP, GIF (first frame), BMP, HEIC/HEIF, and AVIF. Check aspect fit
  on black, correct orientation, and safe failure UI for unsupported/corrupt files.
  Check a header-protected image and same-origin/cross-origin redirects: headers
  follow only their original origin. Standard TLS validation must remain active.
- A timed image starts counting after it becomes visible, pauses/resumes via phone
  play/pause/toggle and Siri Remote Select/Play-Pause, and advances once at the end.
  Pause before loading completes, then resume. Slow/failed loads must progress after
  the 15-second fallback plus display duration. Negative durations become zero;
  durations above 24 hours are clamped.
- Untimed/zero-duration images report paused with zero position/duration and remain
  visible until next/previous/jump/stop. Seek/tuning commands do not alter the image;
  tracks and timeline are empty, isLive/isSeekable are false, and speed/scaling/
  subtitle-offset controls are unavailable. Image history has no resume progress or
  captured artwork; `skipHistory` casts create no history entry.
- From the phone, zoom (1–8×), pan/move, scroll, rotate, set/clear an anchor, and reset
  through both JSON and binary pointer input. Anchor zoom/rotation should stay under
  the gesture focus; pan clamps at the viewport. Repeated zoom-out restores center.
  Malformed/short/non-finite packets have no effect. Input on audio/video is ignored.
- Siri Remote Left/Right changes images, Select pauses/resumes, and Menu exits.
  Phone Home exits from timed/untimed/loading/failed images, music, video, buffering,
  paused playback, every overlay, pre-play, and the still-watching prompt; the sender
  receives idle and no stale player status afterward.
- Cast music with artwork, poster-only, backdrop-only, a failing artwork URL, and no
  artwork. Check fallback/placeholder, blurred background, title/artist/album and
  Now Playing metadata/artwork. Confirm actual sound, transport/scrubber, tracks,
  speed, app volume, and switching between MPV and AVPlayer.
- Play video → timed image → music → untimed image → video in one queue; repeat with
  music/image first. Advance, go back, jump, append, move/remove the current item,
  clear, stop, and reconnect mid-playback. Keep one playback ID and player context
  until exit; status and every playlist item carry the current resolved media kind.
  Pre-play appears only for video, honors skipPreplay, and cannot appear over music
  or images when the first item changes kind.

Page-controlled media (`detectedBy` `page_cast` / `linked_page`): video, audio,
subtitles (including late `add_subtitle`) and images play only through the embedded
Rust stream proxy (`Network/PageMediaRoute.swift`, `Network/TVProxyHost.swift`).
The proxy listens on 127.0.0.1, starts with the first page item and stops after 30 s
without registrations. It resolves DNS, follows redirects and fetches HLS children
under the page-cast network policy: public destinations plus the item's exact
`allowedPrivateOrigins`, never loopback. AVPlayer, mpv, the subtitle loader and the
image loader are given the proxy URL; the original URL and credential headers never
reach them. A refused or unavailable proxy (or a build without the Cast Core
library) rejects `play`, `playlist`, `queue_add` and `playlist_jump` with
`page_media_unavailable`, and a late subtitle with `subtitle_unavailable`; nothing
falls back to direct playback. Queued page items are registered when they come up,
and a page item that is not routed shows an error instead of playing. Page items are
not saved to History, because a History replay is a TV-initiated direct play.
Cast-sheet media (any other `detectedBy`) keeps playing directly, including
phone-served LAN media with standard URLSession trust and scoped headers.
Build the proxy with `sh cast/build-apple-tvos.sh` (see `cast/README.md`). Host
checks cover the routing decision; confirm real playback on an Apple TV: an HLS
page cast with a public host, one with an approved LAN origin, an unapproved LAN
origin (must be refused), and a late subtitle.
Diagnostic logs over WSS are deferred and are not advertised.
