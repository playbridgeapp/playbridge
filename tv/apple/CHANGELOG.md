# Changelog — PlayBridge TV (tvOS)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- **MPV uses standard LGPL MPVKit 1.0.0** (same as the phone) instead of the GPL `mpv-ios/MPVKit` 0.41.0-av fork: `gpu-next` video, AVFoundation audio. The local AudioUnit patch is removed.
- **No more CocoaPods**: SwiftProtobuf comes from Swift Package Manager; open `PlayBridge TV.xcodeproj`.
- Settings has an **About** section with the privacy policy address and app version.
- Minimum tvOS lowered from 26.4 to 17.0.
- Added @2x Top Shelf images for Apple TV 4K.

### Fixed
- MPV now switches the display to HDR10/HLG for HDR files; previously the mode was chosen before mpv knew the colorimetry, so HDR played in SDR mode.
- Embedded frameworks' `MinimumOSVersion` is clamped to the deployment target for App Store validation.

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
