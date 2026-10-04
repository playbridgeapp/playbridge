# iOS local playback

The phone uses standard MPVKit 1.0.0 for all local video/audio playback: website
sessions, browser-detected streams, imported files and completed downloads.
MP4, MKV, HLS, DASH, audio and opaque URLs all use the same decoder; there is no
AVPlayer fallback or **Try with mpv** action. A missing mpv engine fails explicitly.
Retries preserve the selected route, headers and current position. Original URL
and MIME information remain available for diagnostics, not engine selection.

MPVKit is pinned with Swift Package Manager in the phone project. The Apple TV's
separate CocoaPods dependency is unchanged. The phone links the standard product,
not MPVKit-GPL. See `THIRD_PARTY_LICENSES.md` for the pinned source and notices.

## Player behavior

Local **Via phone** playback uses loopback proxy registrations, including rewritten
HLS children, so mpv does not go through Wi-Fi/VPN to reach its own proxy. Receiver
casts still use the advertised LAN address. Upstream/private-origin policies and
registration lifetimes are unchanged; no route silently becomes Direct.

- mpv feeds the website progress, queue and final-close snapshot. Fullscreen website playback includes a live **Queue** and next/previous controls, using that same queue for manual navigation and automatic advancement. An unresolved tail shows **Loading next episode…** and advances when the website supplies it; only an explicit end-of-list finishes the queue.
- Controls hide after three seconds of playback; a single tap toggles them and
  a double tap on either half seeks ten seconds. Consecutive seeks accumulate.
  Pausing, buffering, scrubbing, opening settings/queue and VoiceOver prevent
  automatic hiding. Touch lock hides all controls; tapping reveals only Unlock.
- The overlay includes **Fit/Fill**, speed (0.5×–2×), lock and player settings.
  Fit preserves the entire image; Fill crops without stretching. The fullscreen
  toolbar overlays the image and system chrome is hidden.
- Player settings include audio/subtitle tracks, remembered language preferences,
  subtitle delay (±10 seconds), text size, white/yellow text and a dark background.
  Native and website captions share delay/style choices; embedded text styling is
  overridden, while bitmap subtitles may not support it. A positive delay moves
  captions later. Delay resets on episode changes but survives retry.
- Only display choices and normalized language codes are persisted, never URLs,
  track labels or credentials. Selected languages are restored after track discovery
  on retries/episode changes, with website sidecar fallback when available. Missing
  preferred subtitles stay off; missing audio uses the source default.
- mpv uses VideoToolbox decoding when available and its normal software fallback.
- The standard `gpu-next` / MoltenVK renderer has phone touch controls, seek,
  embedded audio/subtitle selection, and the existing SRT/WebVTT sidecar captions.
  Drawable-size changes explicitly rebuild the embedded video output while
  preserving the decoder, clock and pause choice. Resizing the Metal layer alone
  leaves mpv drawing into its old portrait viewport.
- mpv pauses video rendering in the background, preserves the user's pause choice
  on return, and pauses on audio interruptions. Its video surface does not offer
  Picture in Picture or video AirPlay. The dedicated external AirPlay casting
  path retains its system player; it is not a local playback fallback.
- libmpv calls and destruction run on a serial queue. The Metal layer outlives
  asynchronous destruction; stale decoder callbacks cannot mutate a new episode.
- HTTPS verification stays enabled. MPVKit's GnuTLS backend needs the bundled
  Mozilla root CA store because it cannot use the iOS system trust store. The
  pinned, checksum-verified snapshot lives in `Resources/MozillaRootCertificates.pem`.
  Refresh it from curl's official CA extraction service during release dependency
  updates; preserve its bundled license, source notice and verified checksum.
- Diagnostics contain engine, format, source host, position, numeric error and
  fixed TLS/HTTP failure categories, never full stream URLs or header values.
  Decoder warning events are classified in memory and their raw text discarded;
  terminal/file logging is disabled. Loading error `-13` alone does not establish
  expiration or an unsupported codec.

## Verification

From the repository root:

```sh
bash mobile/apple/tests/run-phone-player-features-checks.sh
bash mobile/apple/tests/run-phone-engine-checks.sh
bash mobile/apple/tests/run-website-playback-checks.sh
bash mobile/apple/tests/run-playback-error-checks.sh
bash mobile/apple/tests/run-mpv-phone-probe.sh # booted iOS simulator and ffmpeg required
MPV_FEATURES_PROBE=1 bash mobile/apple/tests/run-mpv-phone-probe.sh # native speed/language and fit/fill/subtitle pixel checks
MPV_ORIENTATION_PROBE=1 bash mobile/apple/tests/run-mpv-phone-probe.sh # fullscreen pixel checks, including paused rotation
MPV_OPENING_ORIENTATION_PROBE=1 MPV_OPENING_ORIENTATION=landscape bash mobile/apple/tests/run-mpv-phone-probe.sh # opening preference, decoded pixels and page-orientation restoration
# Repeat the opening probe with MPV_OPENING_ORIENTATION=portrait for portrait entry.
MPV_PHONE_PROXY_PROBE=1 bash mobile/apple/tests/run-mpv-phone-probe.sh # requires existing optional Cast Core XCFramework
MPV_TLS_REJECTION_PROBE=1 bash mobile/apple/tests/run-mpv-phone-probe.sh
MPV_NETWORK_PROBE=1 MPV_REMOTE_FIXTURE=https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4 bash mobile/apple/tests/run-mpv-phone-probe.sh
```

Build the phone target with Xcode as described in `AGENTS.md`. The native MKV
probe in `mobile/apple/tests/MPVPhonePlaybackProbe.swift` exercises the production
decoder, rendering view and session in an isolated simulator app. Physical
iPhone checks remain necessary for hardware decoding, HDR, audio routes and
background transitions.

For private authenticated test URLs, use `MPV_REMOTE_FIXTURE_FILE` with a file
outside the repository instead of putting the URL into shell history or logs.
