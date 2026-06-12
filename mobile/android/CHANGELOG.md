# Changelog — PlayBridge Sender (Android phone)

All notable changes to the phone app (`com.playbridge.sender`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
