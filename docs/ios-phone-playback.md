# iOS local playback

The phone uses AVPlayer for compatible streams and standard MPVKit 1.0.0 for MKV
(original URL extension or Matroska MIME type). Detection uses the original URL
even when the selected stream route returns an opaque proxy URL. An AVPlayer
failure offers **Try with mpv** for local playback; retry keeps the selected route
and the current position. No conversion server is required.

MPVKit is pinned with Swift Package Manager in the phone project. The Apple TV's
separate CocoaPods dependency is unchanged. The phone links the standard product,
not MPVKit-GPL. See `THIRD_PARTY_LICENSES.md` for the pinned source and notices.

## Player behavior

- Both engines feed the same website progress, queue and final-close snapshot.
- mpv uses VideoToolbox decoding when available and its normal software fallback.
- The standard `gpu-next` / MoltenVK renderer has phone touch controls, seek,
  embedded audio/subtitle selection, and the existing SRT/WebVTT sidecar captions.
- mpv pauses video rendering in the background, preserves the user's pause choice
  on return, and pauses on audio interruptions. Its video surface does not offer
  Picture in Picture or video AirPlay. AVPlayer retains those native capabilities.
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
bash mobile/apple/tests/run-phone-engine-checks.sh
bash mobile/apple/tests/run-website-playback-checks.sh
bash mobile/apple/tests/run-playback-error-checks.sh
bash mobile/apple/tests/run-mpv-phone-probe.sh # booted iOS simulator and ffmpeg required
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
