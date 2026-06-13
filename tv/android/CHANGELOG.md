# Changelog — PlayBridge TV (Android TV)

Covers both APKs in this tree: the **player** (`com.playbridge.player`) and the **GeckoView plugin** (`com.playbridge.geckoview.plugin`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Player [0.2.6] — 2026-06-12 (versionCode 206)

### Added
- Honor `start_position_ms` for actual resume playback in both engines (ExoPlayer and MPV), mapped through `EXTRA_START_POSITION`. (#11)
- `playlist_status` echo enriched with season/episode/imdbId/bingeGroup from visual metadata, enabling phone-side queue re-attach and watch-progress tracking. (#11)

### Changed
- **Player instances reused across episodes** (ExoPlayer): faster episode transitions in binge sessions. (#14)
- Engine switches carry the live queue via playlist snapshot, so mid-playlist Exo↔MPV switches keep position in the queue. (#12)

### Removed
- Unused Bluetooth server and its permissions.

## GeckoView Plugin [0.2.5] — 2026-06-12 (versionCode 205)

### Changed
- Maintenance release: version alignment with the player; Android Lint now blocking in CI via baselines. No functional changes.
