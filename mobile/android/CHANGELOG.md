# Changelog — PlayBridge Sender (Android phone)

All notable changes to the phone app (`com.playbridge.sender`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.7.0] — 2026-06-30 (versionCode 214)

### Added
- **User Agent Presets**: Added custom User Agent presets sheet supporting Android/Desktop/TV browser spoofing and settings repository persistence.
- **User Agent Selection UI**: Built the `UserAgentSheet` component and menu integration to allow hot-swapping web browser layouts.

## [0.6.1] — 2026-06-29 (versionCode 213)

### Fixed
- **SAS Auth Connection**: Resolved client reconnection timing and verification state flags on socket reconnection.

## [0.6.0] — 2026-06-28 (versionCode 212)

### Added
- **SAS Pairing Handshake**: Integrated Secure Association Service (SAS) pairing handshake using cryptographically verified short authentication strings (SAS) for secure receiver pairing. (#66)
- **Premium Glassmorphic Remote**: Redesigned the remote control UI with a modern glassmorphic aesthetic, interactive touch targets, and a segmented controller for switching control modes. (#65)
- **WorkManager Downloads Rewrite**: Completely overhauled the download engine with a `WorkManager`-backed queue, concurrent segment downloading, on-the-fly HLS to MP4 transmuxing, and proper `MediaStore` publishing. (#63)
- **Cast Connection Routing**: Implemented a robust reconnect supervisor, connection routing framework, and optimized Foreground Service (FGS) behaviors for background casting durability. (#62)
- **Reactive Watch Route**: Made the watch navigation route reactive and added exclusion filters to the logs viewer. (#64)

## [0.5.0] — 2026-06-22 (versionCode 211)

### Added
- **Remote Control Redesign**: New interface supporting toggleable TV control modes: Media Player mode (gestures / playback chips) and Web Navigation mode (D-pad / web controls). (#59)
- **Binary Protocol Updates**: Updated shared binary protocols for remote interactions. (#59)
- **Cast Sheet Updates**: Improved remote status and cast session handling in `CastSheet`. (#59)

## [0.4.0] — 2026-06-22 (versionCode 210)

### Added
- **Diagnostics Logs Viewer**: Added diagnostics logs screen with real-time logcat reader and email/share attachments. (#54)
- **Crash Logger**: Automatically captures and stores uncaught exceptions locally for diagnostic analysis. (#54)

### Changed
- **Tabs Screen UI**: Refactored the web browser tabs screen interface and navigation logic for improved tab lifecycle. (#56)
- **Player Control Overlays**: Enhanced player control overlay states and TV connection logic. (#56)

### Fixed
- Fixed keystore path signing failure by converting `storeFile` to an absolute path. (#53)

## [0.3.0] — 2026-06-21 (versionCode 209)

### Added
- **Subtitle Selection Dialog**: Added a new subtitle grouping and selection bottom sheet on the remote screen, categorizing subtitles into tabs by language (Embedded, Phone Remote, Add-on languages, etc.). (#44)
- **Security-First Cast Connections**: Enforced `wss://` secure websocket connections exclusively and removed plaintext WS fallbacks. (#41)

### Changed
- **Lint Cleanup**: Resolved numerous security-sensitive, thread, and general lint issues, and cleaned up baseline XML. (#45)

### Fixed
- Fixed IPv6 connection wrapping/parsing issues. (#44)

## [0.2.8] — 2026-06-17 (versionCode 208)

### Added
- **IPTV Integration**: Dashboard tile, playlist manager (URL/file upload), category-grouped channel explorer with search, active-first URL probing, and in-app playback or casting. (#39)
- **Collections**: Dashboard tile, custom collections (create, rename, delete, reorder, deduplicate), and quick "Add to Collection" actions from IPTV, Phone Files, Debrid, Cast History, and the browser cast sheet. (#39)
- **Remote Control Redesign**: Combined seek and volume gesture bar (horizontal swipe to seek, vertical swipe for volume). (#39)
- Upgraded local Room Database (v17 -> v19) to store playlists, channels, and collection schemas. (#39)

### Changed
- Improved UI bottom padding to prevent the Now Playing bar from overlapping the bottom list items. (#39)

### Fixed
- Fixed back navigation from the Remote screen returning to a stale/outdated screen. (#39)
- Resolved relative URL resolution failures in the shared M3U parser. (#39)

## [0.2.7] — 2026-06-15 (versionCode 207)

### Added
- **Phone Files revamp**: Added SAF file picker, search, sort sheet (name/size/modified), and a per-tab folder row. Play locally via 'This Device' or cast to the active target. Hoist screen state so it survives navigation. (#34)
- **Cast bar**: Permanent three-state (playing/idle) cast bar with quick navigation to Remote or Device Picker. (#34)
- Hard 'Exit PlayBridge' button on the Dashboard. (#34)

### Changed
- Overhauled back button navigation rules to return to Dashboard from primary screens, and double-tap Dashboard back button to soft exit. (#34)
- Multi-language subtitle delivery: sends all available subtitle tracks, prioritizing preferred languages and matching release names to ensure better auto-sync. (#32)

### Removed
- Video filter remote controls and settings. (#31)

## [0.2.6] — 2026-06-13 (versionCode 206)

### Added
- Seed default Cinemeta addon if library is empty to improve initial onboarding. (#27)
- uBlock Origin is now bundled as a shared asset in the KMP module. (#26)

## [0.2.5] — 2026-06-12 (versionCode 205)

### Added
- **DLNA/UPnP casting**: discover renderers via SSDP and cast web videos, HLS streams, and local files through an on-device header-injecting proxy (with HLS playlist rewriting). Unified remote drives both native and DLNA targets. (#7)
- **Cast sessions**: `CastSessionManager` + foreground service keep casts (native or DLNA) alive through screen-off and activity death, with a live notification and Stop action. (#11)
- **Automatic watch-progress tracking** on both transports: marks watched at ≥90% or auto-advance, forward-only episode pointer, skip-ahead catch-up, movie/series completion, and a content-keyed resume store ("Resume · 23:14" labels, episode progress bars, actual TV resume via `start_position_ms`). (#11)
- **DLNA episode auto-advance**: phone-driven queue advances binge sessions on renderers via the shared episode stream resolver. (#11)
- **Now-playing mini bar** across main screens (poster-accent on library detail), replacing the remote FABs. (#11)
- **Unified device picker**: one shared DeviceChip + connection sheet across library, detail, cast sheet, and Phone Files; time-boxed discovery with manual rescan and sticky auto-connect; HLS VOD duration derived from `#EXTINF` sums. (#9)

### Changed
- Browser migrated to Mozilla Android Components' store-owned session architecture (`EngineMiddleware`): on-demand engine session creation/restore, tab hibernation, crash auto-recovery (capped), sessions survive activity recreation, reflection hacks replaced with public AC APIs. (#17)
- WebSocket auto-retries removed — failures surface immediately; reconnects are on-demand from send paths and startup auto-connect (no more UI flapping). (#11)

### Fixed
- White-blank-page / tab-not-loading bugs and stale background-tab URLs. (#17)
- Video detection delivered via native messaging instead of racy URL-hash signaling; detection state resets on navigation; live-stream and MSE/blob detection gaps closed (missing Content-Type, `.mpd` by URL, service-worker requests, JS player config probing). (#15)
