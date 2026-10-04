# MPV audio: phone vs TV

## Verified dependency and implementation differences

| | iPhone local playback | Apple TV MPV |
|---|---|---|
| Package | `mpvkit/MPVKit` **1.0.0**, SPM | `mpv-ios/MPVKit` **0.41.0-av**, CocoaPods |
| Embedded libmpv version string | `mpv v0.41.0-dirty` | `mpv v0.41.0-dirty` |
| Requested audio output | `avfoundation,audiounit` | `audiounit` |
| Compiled audio outputs relevant here | AVFoundation and AudioUnit | AudioUnit only |
| Video output | `gpu-next`/MoltenVK | Native `avfoundation` |
| App audio session | Playback/movie mode, default local routing policy | Playback/movie mode; now default routing policy instead of long-form audio |

The package version numbers do **not** mean libmpv 1.0 vs libmpv 0.41. Both
archives report the same base mpv version; build patches/backends differ.
The requested phone audio list prefers AVFoundation but falls back to
AudioUnit; device `current-ao` identifies what actually initialized.

Evidence is from the phone
`PlayBridge Phone.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`,
the TV Podfile/installed manifest, both playback implementations, and `nm`/
version strings from the installed standard-phone iOS archive and pinned TV
arm64 archive. The phone package includes
`Sources/BuildScripts/patch/libmpv/0003-enable-avfoundation-ao-tvos.patch`.

TV `vo=avfoundation` is a **video** backend, not an audio backend. Setting
`ao=avfoundation` cannot enable code missing from the installed library.
The standard 1.0.0 tvOS archive has AVFoundation audio but lacks the fork's
native AVFoundation **video** output; blindly swapping dependencies would
change the rendering/HDR path and is not an audio-only fix. No dependency
migration has been made.

## Confirmed initialization failure and targeted patch

Device logs identify `kAudioUnitErr_InvalidProperty` (-10879) when the AudioUnit
driver queries the HDMI channel layout, followed by audio initialization failure.
The subsequent `selectedAudio=no` is a consequence of that failure, not evidence
that the user selected mute. The session route reports 32 channels, but that
count alone does not identify a valid speaker map or prove the cable is faulty.

The pinned driver now has a [source-built fallback](../native/mpv-audiounit/README.md):
for this unsupported property on PCM, request stereo instead of aborting. Valid
multichannel queries remain unchanged. The fix is installed by `pod install`;
there is no library-version or video-output change. Host tests reproduce the
original failure and exercise the patched code with API doubles under sanitizers.
The user confirmed restored audible playback on the previously failing Apple TV
HDMI route. Broader route/multichannel coverage remains a separate device check.

## Further device coverage

The earlier per-item audio/mute reset and default routing-policy change did
not alone resolve the silence. The AudioUnit patch above did; do not confuse
session configuration with the confirmed initialization fix.

Test a known-audio MP4/HLS in both engines, preplay mute/unmute, audio-track
changes, queue transitions and representative HDMI/multichannel routes.
For further failures, share only Debug `[MPV audio-session]` lines and
AudioUnit initialization/start warnings. Do not share stream URLs,
authentication headers or cookies. Generic port types omit device names/IDs;
system volume may not reflect an external HDMI receiver.

The CityHall AV1 sample has no audio track. Unsigned builds and host tests
cannot verify audible playback on the Apple TV.
