# Changelog — PlayBridge TV (tvOS)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
