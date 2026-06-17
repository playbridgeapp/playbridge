# Changelog — PlayBridge Sender (Android phone)

All notable changes to the phone app (`com.playbridge.sender`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
