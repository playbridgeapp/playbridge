# MPV audio and video output on Apple TV

## Current stack

The Apple TV and the iPhone link the same dependency and the same output
configuration:

| | iPhone local playback | Apple TV MPV |
|---|---|---|
| Package | `mpvkit/MPVKit` **1.0.0**, SPM, standard (LGPL) product | Same |
| Requested audio output | `avfoundation,audiounit` | Same |
| Video output | `gpu-next` / Vulkan via MoltenVK | Same, plus `target-colorspace-hint=yes` |
| App audio session | Playback/movie mode, default routing policy | Same |

The TV previously used the `mpv-ios/MPVKit` **0.41.0-av** fork through
CocoaPods: GPL with nonfree FFmpeg components, native `vo=avfoundation`
video, and AudioUnit-only audio. That fork needed a locally rebuilt AudioUnit
driver, because the HDMI channel-layout query failed with
`kAudioUnitErr_InvalidProperty` (-10879) and audio never started. AVFoundation
audio does not make that query, so the patch, its tests and its build tooling
have been removed along with the fork.

## Device verification (October 2026)

On an Apple TV 4K (3rd generation, A15) over HDMI, a 2160p HEVC Dolby Vision /
HDR10 remux with DTS-HD 5.1 audio:

- `[MPV] audio output=avfoundation`, audible on the HDMI route.
- `[MPV] hwdec-current: videotoolbox`.
- `[MPV] video output=gpu-next source=bt.2020/pq target=bt.2020/pq display=hdr10`;
  the TV switched to HDR and colours were correct.
- Smooth playback, and video resumed after returning from the Home Screen.

mpv plays the HDR10 base layer of Dolby Vision files; the display switches to
HDR10, not Dolby Vision.

## Further device coverage

Still to test: AAC stereo MP4/HLS in both engines, HLG content, SDR after HDR
(the display should return to SDR), multichannel PCM routes, preplay
mute/unmute, audio-track changes and queue transitions.

For failures, share only Debug `[MPV audio-session]`, `[MPV] audio output`,
`[MPV] video output` and `hwdec-current` lines. Do not share stream URLs,
authentication headers or cookies: the Debug `[DebugNetwork]` lines print the
full request URL. Generic port types omit device names/IDs; system volume may
not reflect an external HDMI receiver.
