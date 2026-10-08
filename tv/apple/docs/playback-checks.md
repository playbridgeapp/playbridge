# Apple TV playback checks

The receiver has two playback engines: AVPlayer (default) and MPV. VLC and
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
