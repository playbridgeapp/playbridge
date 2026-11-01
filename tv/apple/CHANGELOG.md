# Changelog — PlayBridge TV (tvOS)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.16.0] — 2026-10-31 (build 11)

> **mpv is now the default player.** A saved choice is kept; if you never picked one, playback now uses mpv. AVPlayer stays available in Settings, and mpv takes over when AVPlayer fails.

### Added
- **Redesigned mpv controls**: An Apple-style transport with a white seek bar, chapter gaps, skip-segment bands and the buffered range; Loop, Speed, Switch, Subtitles, Audio and Playlist buttons; an Info panel on Down with Info, Chapters (as thumbnail cards) and Settings; and touch scrubbing on the Siri Remote while paused.
- **Chapters**: Chapter navigation on the TV in both players, and chapter and buffered-range data sent to phones for their seek bar.
- **Skip intro and credits**: Skip segments from IntroDB and TheIntroDB with a Skip button in both players. Choose the provider in Settings.
- **Player settings on the TV and from the phone**: Speed, Fit/Zoom/Fill scaling and subtitle sync, from the Settings tab and AVPlayer's menus or from the phone remote.
- **Images and music**: Mixed playlists with images (including slideshows with a display duration, and pan, zoom and rotate from the phone) and music with artwork, title, artist and album.
- **Error screen**: When mpv can't play an item it shows the reason with Try Again, Next and Close instead of failing silently, and reports the error to the phone.
- **Phone controls the whole app**: The phone remote's D-pad, OK, Back and Home move around the app while it's idle. Allowing or denying a pairing still needs the Siri Remote.
- **Website casts through a built-in proxy**: Video, subtitles and images a website sends (page casts and linked pages) play through a proxy inside the app that only reaches public addresses and the local-network addresses the website was allowed. Videos you pick in the phone's cast sheet still play directly.
- **Receiver capabilities**: The app tells phones which remote keys and player settings it supports, so their remotes only show working controls. It keeps the queue item ids phones send.

### Changed
- **Playlist**: A bottom sheet of artwork cards replaces the side list, and Playlist is hidden for a one-item queue.
- **Preplay artwork**: Sharp 4K backdrops and posters, without the blur.
- **Subtitles from senders**: Subtitles sent with their own headers and labels (`subtitleResources`) now load, show their label, and can be picked from the phone.

### Fixed
- **AVPlayer failures**: When AVPlayer fails after loading, or before an item was ever ready, mpv takes over at the same position instead of hanging on a spinner.
- **Stalled playback**: Playback that keeps buffering without progress moves to mpv or shows the error screen.
- **Volume between casts**: The app volume keeps its level from one cast to the next.
- **Remote and controls**: Phone ±15 s works while paused, Back steps out one level at a time, Play/Pause always toggles, audio picks carry to the next item, and the Skip Segments setting is focusable.
- **Subtitles you add from the phone**: A subtitle added during a website cast, such as a file served by the phone, loads instead of being refused.
- **Pairing requests on any screen**: A pairing code now appears above whatever the TV is showing, including during playback, instead of only on Pair Device. Back cancels it.
- **Pairing and sidebar**: The pairing screen shows the port without a separator, sidebar titles stay on one line, and Remove All asks for confirmation.

## [0.15.0] — 2026-10-09 (build 6)

### Added
- **Playback controls**: Receiver-owned queue controls, late subtitle attachment, an explicit player-switching menu and native playback progress callbacks.
- **Still watching**: Configurable reminders during long playback sessions.
- **Top Shelf and About**: Add Apple TV 4K @2x Top Shelf images and an About section with the privacy policy address and app version.

### Changed
- **MPV playback**: Use standard LGPL MPVKit 1.0.0, aligned with the phone, with gpu-next video and AVFoundation audio; remove the old AudioUnit patch and VLC engine.
- **Platform support**: Lower the minimum tvOS version from 26.4 to 17.0; dependencies, including SwiftProtobuf, now use Swift Package Manager without CocoaPods.
- **Private casts**: Honor the receiver-history preference and preserve cast progress across episode transitions.

### Fixed
- **HDR output**: Switch the display to HDR10/HLG after MPV identifies the media's color information instead of playing HDR in SDR mode.
- **Playback continuity**: Improve subtitle selection, track preferences and sender status during episode changes and player switching.
- **Store validation**: Clamp embedded frameworks' minimum OS version to the deployment target.

### Security
- **Pairing credentials**: Protect persisted pairing tokens and avoid echoing authentication secrets in receiver responses.

## [0.3.0] — 2026-06-28 (build 5)

### Added
- **SAS Pairing Handshake**: Implemented the Secure Association Service (SAS) pairing handshake, generating and displaying short authentication strings for secure out-of-band authentication. (#66)
- **Pairing View**: Updated the pairing UI with verification screens and controls to validate connecting senders. (#66)

## [0.2.0] — 2026-06-21 (build 4)

### Added
- **Gated History**: Option to disable casting history under Settings. (#49)
- **Security-First Cast Connections**: Enforced `wss://` secure websocket connections exclusively and removed plaintext WS fallbacks. (#41)

### Fixed
- Fixed IPv6 connection wrapping/parsing issues. (#44)

### 

## [1.0.1] — 2026-06-12 (build 3)

### Added
- Honor `start_position_ms` (resume) in all three engines — AVPlayer, VLC, and MPV — via initial seek time. (#12)
- Emit `playlist_status` (Android-compatible format) on queue changes and client connect, enabling phone-side queue re-attach and watch-progress tracking. (#12)
- Session-scoped track preferences: audio/subtitle picks carry across episodes in all engines despite per-item player recreation. (#12)

### Changed
- **MPV player instances reused across episodes**: faster episode transitions in binge sessions. (#14)

## [1.0] — 2026-06 (build 2)

Initial tvOS receiver: WebSocket server with pairing, AVPlayer/VLC/MPV engines with in-player switching, now-playing context broadcast, MPVKit/VLCKit 4.0 (AV1) support.
