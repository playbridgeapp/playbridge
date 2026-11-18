# iOS local playback

The phone uses standard MPVKit 1.0.0 for local video/audio playback by default:
website sessions, browser-detected streams, imported files and completed downloads.
MP4, MKV, HLS, DASH, audio and opaque URLs all start on mpv. Apple's AVPlayer is a
second engine the user can switch to (see [Player engine](#player-engine)); PlayBridge
never picks it by format and never falls back to it on its own. A missing engine fails
explicitly. Retries preserve the selected route, headers, engine and current position.
Original URL and MIME information remain available for diagnostics.

## Player engine

**More (⋯) → Player → Player engine** switches the open player between **mpv** and
**AVPlayer**. When playback fails, the failure screen also offers **Try with AVPlayer**
(or **Try with mpv** on AVPlayer). Switching prepares the selected route again, as
**Try again** does, and reopens the video at the current position; a playing video
keeps playing and a paused one stays paused.

The choice lasts for the open player: a website queue's next episode and a watch-party
replacement keep it, and the next video opened starts on mpv again. It is not saved
as a preference, because AVPlayer suits particular streams (for example an HLS video
that mpv opens as live and can't seek, or a stream mpv shows without a picture) and
would be the weaker choice for most others.

With AVPlayer:

- Streams that need request headers (cookies, referer, user agent) and plain-HTTP
  streams play through the phone's loopback proxy (`StreamRouteService.localPlayback`),
  which sends the headers on every request, including HLS segments. AVPlayer itself
  gets only a `127.0.0.1` address. Streams already routed **Via phone** or website
  media are already on loopback. If the proxy isn't available, playback fails with a
  message suggesting mpv.
- The stream's own audio and subtitle tracks come from AVFoundation's media
  selection, with their languages, so the remembered audio/subtitle languages apply
  as with mpv. Embedded subtitles use the phone's subtitle style where AVFoundation
  allows it (size, generic font family, background, edge; custom fonts don't apply).
- Subtitles ticked in the cast sheet (SRT/WebVTT files) and website subtitles are
  drawn over the video with the same caption view, style and timing offset as on
  mpv. The timing offset applies only to these overlay captions, not to subtitles
  inside the stream.
- Fit/Fill, speed, seeking, the queue, website progress and watch-party controls work
  as on mpv. AVPlayer pauses in the background and on audio interruptions, keeping the
  user's pause choice, and does not offer AirPlay or Picture in Picture from the
  phone player.
- Not supported: MKV files (refused at once, with a message suggesting mpv), styled
  ASS/SSA subtitle files (left out of the subtitle list), bitmap subtitles, DASH,
  and codecs Apple doesn't decode. These fail with a message suggesting mpv.
- AVFoundation is part of iOS; it adds no third-party code or licence notice.

Debug-build `[PhonePlayback]` lines for AVPlayer show the host and file extension,
header names, whether loopback was used, track counts and languages, and error domains
and codes. AVPlayer's error-log comments and URIs are never printed; they are only
classified into the fixed TLS/HTTP/network categories used in diagnostics.

MPVKit is pinned with Swift Package Manager in the phone project. The Apple TV
pins the same version in its own project. Both link the standard product,
not MPVKit-GPL. See `THIRD_PARTY_LICENSES.md` for the pinned source and notices.

## Player behavior

Local **Via phone** playback uses loopback proxy registrations, including rewritten
HLS children, so mpv does not go through Wi-Fi/VPN to reach its own proxy. Receiver
casts still use the advertised LAN address. Upstream/private-origin policies and
registration lifetimes are unchanged; no route silently becomes Direct.

- **Opening (Settings › Phone player › Landscape videos open in):** **Fullscreen**
  (default) holds landscape as soon as the player opens; **Portrait first** follows
  how the phone is held. Once the first frame gives the picture's size
  (`PhonePlaybackState.videoWidth/Height`), a vertical video is held in portrait
  either way. A website's requested orientation wins and audio never turns the
  player. The rule is `PhonePlayerOpening.decide`; it runs once per opening, after
  which the Rotate and Fullscreen buttons take over.
- **Portrait layout:** a landscape video on an upright phone shows the video on
  top (same surface, so mpv only resizes) with the title, seek bar, Previous / Play /
  Next, **Fullscreen**, the speed chip and **Up next** below. In Portrait first,
  Fullscreen holds landscape until the phone is turned to match, then hands rotation
  back to the phone.
- **No black screen:** until the first frame, the cast sheet's thumbnail (or a
  website item's backdrop/poster from its visual metadata) shows blurred, then
  fades out. On iOS 18+ the player zooms out of the cast sheet card's thumbnail.
- **Ambient mode (Settings › Phone player, on by default):** a blurred glow of the
  video's colours fills the space around the picture: behind the portrait layout, and
  the bars in full screen with Fit (the surface then takes exactly the picture's size).
  Once a second while playing, the engine hands over a 48-pixel copy of the frame (mpv
  `screenshot-raw video`, AVPlayer `AVPlayerItemVideoOutput`); the glow steps a third of
  the way toward it so colour changes never dim, and a Core Animation layer crossfades
  it (a SwiftUI image swap blanks for a frame). mpv's grab reads back from the GPU,
  which crashes the simulator's Metal driver, so it is off in the simulator.
- The control scrims run edge to edge (past the safe areas, so Fill shows no hard
  sides) and are off over the blurred poster.
- mpv feeds the website progress, queue and final-close snapshot. Fullscreen website playback includes a live **Queue** and next/previous controls, using that same queue for manual navigation and automatic advancement. An unresolved tail shows **Getting the next episode from the website…** and advances when the website supplies it; only an explicit end-of-list finishes the queue.
- Controls hide after three seconds of playback; a single tap toggles them and
  a double tap on either half seeks ten seconds. Consecutive seeks accumulate.
  Pausing, buffering, scrubbing, opening settings/queue and VoiceOver prevent
  automatic hiding. A double-tap or swipe seek leaves the controls as they are,
  including the brief buffering it causes (for three seconds after the gesture).
  Touch lock hides all controls; tapping reveals only Unlock.
- The layout matches Android's redesign. Top: Back, the title (up to two lines,
  on its own row in portrait) with "3 of 12 · mpv" under it, then **Queue**,
  **Audio & subtitles** and **More**. Centre: Previous / Play-Pause / Next; there
  are no ±10 s buttons because double-tap skips (VoiceOver users get the seek
  bar's adjustable action and named ±10 s actions). Bottom: the seek bar, then
  Lock, Rotate and a speed chip that opens More.
- The seek bar draws with the remote's `RemoteTimelineTrack`: it waves while
  playing, is flat when paused or held, and shimmers while buffering. A tap or drag
  moves to the point under the finger. Tapping the end time switches between the
  duration and the time left, and the choice is remembered.
- **More** has Playback (speed 0.5×–2×), Picture (**Fit/Fill**) and Player (engine).
  Fit preserves the entire image; Fill crops without stretching. The fullscreen
  controls overlay the image and system chrome is hidden.
- **Audio & subtitles** has audio/subtitle tracks, remembered language preferences,
  subtitle delay (±10 seconds) and the subtitle style. A positive delay moves
  captions later. Delay resets on episode changes but survives retry.
- The subtitle style (size, background, edge, font, and whether styled subtitles
  such as ASS are restyled) is the same model and editor as the TV's
  (`Subtitles/SubtitleStyleSettings.swift`, kept byte-identical with tvOS and
  checked by `run-subtitle-style-checks.sh`). It is also in Settings → Audio & subtitles,
  next to the connected TV's own style. Up to 5 custom `.ttf`/`.otf` fonts (30 MB)
  are kept on the phone; only the selected one is linked into mpv's
  `sub-fonts-dir`. Native and website captions share the style; bitmap
  subtitles can't be restyled. The old size and dark-background choices were
  carried over once; the yellow text option is gone, as on Android.
- Configure TV (Play on) and Settings → Audio & subtitles edit a connected TV's style
  (`subtitle_style_v1`) and send it fonts in acknowledged chunks
  (`subtitle_font_v1`, see `protocol/docs/WSS_FLOW.md`). Font bytes are never logged.
- The preferred audio and subtitle languages are also in Settings → Audio & subtitles,
  and choosing a track in the player updates them. Video casts send them to the TV
  (`preferredAudioLanguage` / `preferredSubtitleLanguage`, two-letter codes); the
  Android TV honours them, the Apple TV ignores them for now.
- Subtitles ticked in the cast sheet reach the phone player too: the phone downloads each
  with its own headers (8 MB cap, content checked, HLS subtitle playlists left out), stores
  it as a temporary UTF-8 file removed when the player closes, and mpv adds it with an
  asynchronous `sub-add` once the file loads, again after a retry. The first ticked one is on unless the user
  picks another track; one that can't be prepared is left out.
- Debug builds print a play-on-phone trace (`[PhonePlayback]`, `Network/PhonePlaybackLog.swift`):
  the cast sheet's stream candidates and choice, routing, ticked subtitles, mpv's container,
  timeline, video output and tracks every few seconds, and mpv's info/warning messages.
  Addresses become host and file extension, headers only names, and any message mentioning
  a cookie, token, key or password is dropped. Release builds print nothing. Read it with
  `xcrun devicectl device process launch --console … | grep '^\[PhonePlayback\]'`.
- Detected subtitles are checked as soon as they're detected, from their first 8 KB:
  error pages, JSON and WebVTT thumbnail sprites are hidden, UTF-16 files are decoded,
  and the dialogue's language is identified on the phone. The cast sheet lists the
  preferred language first, then the phone's, and ticks the preferred one (the plain
  version over forced or SDH) until the user changes the ticks. A language named in a
  subtitle's title or file name (`English`, `movie.en.vtt`) is used when the dialogue
  doesn't settle it. Checked with `run-subtitle-language-checks.sh`.
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
bash mobile/apple/tests/run-subtitle-language-checks.sh
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
