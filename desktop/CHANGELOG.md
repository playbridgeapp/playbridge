# Changelog — PlayBridge Desktop (receiver)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
