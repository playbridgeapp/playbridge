# iOS background casting

The phone publishes external receiver playback through `MPNowPlayingInfoCenter`
and routes system play, pause, stop, ±15-second skip and seek commands through the
existing receiver adapters. Timing comes from receiver status, never from the
local audio player's one-second loop. Live or non-seekable media has no seek controls.

`CastPlaybackSession` owns the remote playback lifetime. `CastSystemPlayback` owns
the shared iOS audio session, system controls, and a looping 8 kHz, mono, 16-bit PCM
buffer containing only zeros. This is an explicit silent-audio implementation for
maintaining an active external-media session; it is not evidence of how Web Video
Caster implements background support. App Review acceptance remains unverified.

## Lifetime

- Applies to audio/video casting through Direct, Via phone and Via proxy, including
  on-phone files. Images do not start silent audio.
- A temporary control connection loss freezes the system timeline, retains phone
  stream registrations, and attempts to reconnect for up to two minutes. Pairing,
  authentication and certificate failures require user action. A confirmed receiver
  application exit ends the session instead of relaunching it in the background.
- Stop, actual media completion, explicit disconnect and changing receivers end the
  audio loop and release phone route/subtitle resources.
- After five minutes continuously paused, the audio loop is stopped. Metadata and
  phone URL registrations remain available for resume. iOS may suspend the app;
  reopening it reconnects and obtains fresh receiver state. Lock Screen resume
  after prolonged suspension needs device validation.
- Two minutes without media status while actively playing expires the session.
- On-phone playback takes audio-session ownership while its full-screen player is
  open or its local-file preview is playing. Returning from that playback restores
  an ongoing remote cast's controls/audio session. Calls and
  other audio interruptions are respected; remote status polling does not reclaim
  audio from another app. An explicit Play/cast action can reclaim it.
- Media metadata opts out of system suggestions on iOS 18 and newer. No stream URLs,
  headers, pairing credentials or subtitle URLs are put in system playback metadata.

## Verification

Run the standalone lifetime/controller checks documented in
`mobile/apple/tests/README.md`, then build the phone target for iOS Simulator and
physical iOS. Unit tests and a simulator cannot validate real iOS suspension.

For the physical-device test, launch without the Xcode debugger and start a known
audio/video stream. Test each route independently, including Via phone HLS and a
local file. Check after locking at 30 seconds, five minutes, and one hour:

1. TV playback continues and the iPhone speaker stays silent.
2. The Lock Screen shows the TV title, duration and progressing position. Pause,
   play, ±15-second skip and scrubbing affect the TV, not the silent player.
3. Short Wi-Fi interruptions reconnect without revoking the phone stream URL.
4. Stop and completion remove controls and stop silent audio. Starting a second
   video after Stop is not suppressed by late status from the previous video.
5. Pause longer than five minutes, then check resume/reconnect and resource retention.
6. Open/close an on-phone player, start audio in another app, and interrupt with a
   call. Check session ownership, recovery, and unintended phone sound.
7. Check Low Power Mode, Bluetooth/headphone route changes, and receiver shutdown.

In Debug, the Remote header has **Copy background casting diagnostics**. The report
contains transport/playback flags, retained-route count, silent-player state,
interruption state, local-player ownership and an audio error domain/code. It has
no media URLs or credentials and uses a local clipboard item expiring after 15 minutes.

To compare Now Playing plus an active audio session against the silent renderer,
launch Debug with `-CastSilentAudioEnabled NO`; normal Debug/Release casting enables
the silent renderer. This comparison must be performed without a debugger attached.
Never treat a moving system seek bar alone as proof of background execution: iOS
extrapolates it from the last reported position and playback rate.

## Validation record — 2026-09-25

- iOS Simulator Debug build: passed.
- Signed physical-iPhone Debug build: passed.
- Casting lifetime, Google Cast controller, remote-control and stream-route fixture
  checks: passed.
- Locked-screen playback, interruption behavior and long-duration battery/network
  behavior: not measured; the user chose to perform casting tests on their devices.

## References

- [Apple: elapsed Now Playing time](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfopropertyelapsedplaybacktime)
- [Apple: background playback controls](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/MediaPlaybackGuide/Contents/Resources/en.lproj/RefiningTheUserExperience/RefiningTheUserExperience.html)
- [Apple DTS: indirect audio hardware control and background execution](https://developer.apple.com/forums/thread/840384)
