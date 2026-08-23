# Changelog — PlayBridge Desktop (receiver)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.13.0+31] — 2026-08-11

> **Works best together**: Screen mirroring, mixed-media playlists, and the skip pre-play preference are cross-device features introduced in this coordinated release. Use PlayBridge Phone 0.13.0+ and TV Player 0.13.0+ on the other side for full support.

### Added
- **Android screen mirroring**: Receive H.264 WebRTC screen mirrors from Android phones with lifecycle-safe signaling and correctly fitted portrait or landscape video.
- **Native PlayBridge receiver runtime**: Use the secure Rust-backed TLS/WSS receiver with SAS pairing, persisted credentials, typed commands, and integrated pairing UI.
- **Mixed-media playlists**: Play combined audio/video/image playlists with a dedicated media presentation surface, plus history and favorites support for playlist items.
- **Skip pre-play preference**: Honor the sender's skip pre-play cast preference when loading playback requests.

### Changed
- **Google Cast sessions**: Use persistent Cast sessions and the shared CAF receiver, with resilient receiver refresh, replacement loads, and serialized media operations.
- **Page media request policy**: Enforce page-casting network policy through the playback request preparer, receiver server, and stream proxy upstream fetchers.
- **Compact progress visibility**: Keep the playback progress bar visible in the compact player view.

### Fixed
- **Low-latency HLS casting**: Cast demuxed LL-HLS streams through synthetic master playlists so receivers load the selected audio and video renditions reliably.
- **Remote pointer input**: Smooth high-frequency pointer input and anchor image transforms to the touch midpoint during remote-control sessions.

## [0.8.1+29] — 2026-07-19

### Added
- **Linux Playback Stabilization**: Added media-kit initialization and playback lifecycle handling to improve Linux rendering, tray behavior, pairing persistence, and player recovery.
- **Chrome Store Native Host Support**: Updated the bundled native-messaging host registration and documentation for Chrome Web Store extension installs.

### Fixed
- **Proxy Authentication**: Require explicit non-default proxy passwords and keep local proxy authentication behavior consistent across Desktop and standalone proxy deployments.

## [0.8.0+28] — 2026-07-15

### Added
- **Cast Proxy Routing Control**: Adds a checkbox to Send to TV and a playback proxy route toggle to force routing remote casts through the local proxy.
- **Protocol Vendoring**: Integrates the protocol definitions and generated Kotlin/Dart/Swift bindings directly in the monorepo, removing the submodule dependency.

### Changed
- **Dynamic HLS Playlist Rewriting**: Rewrite HLS playlists using the requested host IP instead of hardcoding `127.0.0.1` so that TV receivers can access the proxy.

## [0.7.0+27] — 2026-07-14

### Added
- **In-process Stream Proxy Server**: Integrated the `playbridge_stream_proxy` server directly in-process. Runs seamlessly inside the app, handles CORS, and intercepts HLS and DASH manifests to propagate session tokens and prevent `403` errors.
- **Proxy Mode Configuration**: Adds a Proxy Mode selector in Settings (Off, Auto, Always) to route traffic through loopback for CDNs that block direct player requests.
- **"Open in External Player" Dialog**: Adds a dialog to easily copy proxied stream URLs, view command lines for `mpv` and `vlc`, or launch them automatically.
- **HLS Stream Preselection**: Adds an opt-in setting to pre-select HLS media playlists.
- **Safer extension bridge logging**: Removes Debrid tokens and auth payloads from the logs.

## [0.6.8+26] — 2026-07-11

### Fixed
- **Windows MSVC compatibility**: Allow the `flutter_media_session` plugin's legacy experimental coroutine header to compile with Visual Studio 18 / MSVC 14.51 while the dependency migrates to standard C++ coroutines.

## [0.6.7+25] — 2026-07-11

### Fixed
- **Cast / local open stability**: Delay mpv audio-route arming until playback is stable (~2s) so open-time device flaps no longer pause casts mid-buffer; only re-enable video when the track is stuck on `"no"` (forcing `VideoTrack.auto()` mid-open could disrupt demux on token CDNs). (#103)
- **False end-of-file**: Ignore early `completed` while still near position 0 so progressive HTTP streams that emit demuxer EOF during buffer do not tear down the session. (#103)

## [0.6.6+24] — 2026-07-08

### Fixed
- **macOS Installer Security**: Clear quarantine flag (`xattr -c`) and apply ad-hoc signatures (`codesign -s - --force`) when preparing the context menu and native host installation scripts to bypass macOS security blocks.

## [0.6.4+22] — 2026-07-07

### Added
- **Self-Update**: In-place self-update, mirroring the Android apps' update flow. Checks `playbridge.app/download/<os>` silently on launch (and manually from Settings), downloads the release archive, swaps the install via a detached helper script, and relaunches. Falls back to opening the downloads page when the install location isn't writable.

### Fixed
- **Theme Consistency**: Bundled the Poppins font and aligned the desktop theme with the refreshed cross-app styling. (#85)
- **About Version**: The Settings About tile showed a hardcoded `v1.0.0`; it now shows the real app version.

## [0.6.3+21] — 2026-07-03

### Added
- **HLS Master Resolver**: Dynamic HLS master playlist parser for choosing optimal audio/video sub-streams in the MPV player engine.

### Fixed
- **MPV Seek Clamping**: Clamped forward and backward seek operations to prevent seeking out of bounds or to negative position offsets.


## [0.6.2+20] — 2026-06-30

### Fixed
- **Multiple Senders Pairing**: Corrected the pairing state machine precedence check so the PIN entry screen is visible when a second phone attempts pairing while another is already active.

## [0.6.1+19] — 2026-06-29

### Fixed
- **SAS Pairing Handshake**: Resolved socket thread initialization, pairing screen controller reset states, and authentication string validation.

## [0.6.0+18] — 2026-06-28

### Added
- **SAS Pairing Handshake**: Implemented the Secure Association Service (SAS) pairing flow, prompting the user with a short authentication string matching the phone for secure out-of-band receiver authentication. (#66)
- **Pairing UI**: Integrated verification screens and validation flows into the pairing screen setup. (#66)

## [0.5.0+17] — 2026-06-22

### Added
- **Logs Screen**: Added an in-app Logs viewer screen and log store to track app operations and assist in debugging. (#55)

### Changed
- Overhauled the Remote Control UI, volume handlers, and Now Playing card. (#55)

## [0.4.0+16] — 2026-06-21

### Added
- **Gated History**: Option to disable saving new items to history under Settings. (#50)
- **Forget Paired TVs**: Button in the pairing screen to forget paired TV receivers. (#51)
- **Security-First Cast Connections**: Enforce `wss://` secure websocket connections exclusively and remove plaintext WS fallbacks. (#41)

### Fixed
- Fixed IPv6 connection wrapping/parsing issues. (#44)

## [0.3.1+15] — 2026-06-17

### Added
- **Host OS Volume Control**: Remote volume commands from the phone now drive the host system's OS volume (via `osascript` on macOS, `wpctl`/`pactl`/`amixer` on Linux, and `SendKeys` on Windows), falling back to in-app player volume. (#39)
- Support for handling remote seek and volume control websocket commands. (#39)

### Changed
- Hide the **Now Playing** sidebar tab when no media is casting. (#38)

## [0.3.0+14] — 2026-06-16

### Added
- **Send to TV (sender role):** discover and pair PlayBridge TVs on the LAN over a
  pinned `wss://` link; cast local files served over tokenized LAN HTTP.
- **Drag-and-drop + multi-file casting:** drop one or more media files onto the
  Send to TV screen (or pick several) to cast them as a playlist.
- **Now Playing tab:** the now-casting card (title, interactive seek, transport)
  plus the TV playlist with tap-to-jump; auto-opens when a cast starts.
- **Browser bridge:** the extension casts through this app via a token-gated
  loopback bridge + native-messaging host (host auto-registered on launch;
  Windows via HKCU registry, macOS/Linux via manifest files).
- **"Play on TV" OS context menu:** right-click a media file → cast it (Windows
  `SystemFileAssociations`, macOS Quick Action, Linux Nautilus script).
- **Setting:** "Full screen on play" (Settings → Playback), enabled by default.
- Sidebar grouped into **Receive** and **Send** sections.

### Fixed
- Seeking jumped to the end of the file: browser-only request headers
  (`Range`, `Sec-*`, etc.) are now stripped before handing the URL to mpv.
- First play after launch now reliably enters full screen (retry once after the
  transition settles).
- The now-casting card hides immediately on Stop and on TV-side end/idle.

## [0.2.1+13] — 2026-06-12

### Added
- Honor `start_position_ms` (resume), consumed per queue item. (#12)
- Full control surface: play/pause toggle, real stop, seek back/forward/to, and `audio_track:` / `sub_track:` selection. (#12)
- Broadcast track lists (change-detected) and metadata-rich `playlist_status` (season/episode/imdbId/bingeGroup) so phone queue re-attach and watch-progress tracking work. (#12)
- Pre-play screen (backdrop, poster, chips, overview, countdown) and fullscreen title scrim. (#12)

### Changed
- Audio/subtitle track picks persist across episodes (language-based re-apply in MpvEngine). (#12)
- Performance: shell no longer rebuilds on 5 Hz position ticks; mpv tuned (decoder+vo framedrop, 30 s readahead, 1 s audio buffer). (#12)
- Code style: `dart format` across lib/, lint fixes (`avoid_print`, curly braces). No behavior change.

### Fixed
- `queue_add` now appends to the live queue (was silently dropped unless idle). (#12)
