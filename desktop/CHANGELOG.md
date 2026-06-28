# Changelog — PlayBridge Desktop (receiver)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
