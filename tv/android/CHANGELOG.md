# Changelog — PlayBridge TV (Android TV)

Covers both APKs in this tree: the **player** (`com.playbridge.player`) and the **GeckoView plugin** (`com.playbridge.geckoview.plugin`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Player [0.8.0] — 2026-07-04 (versionCode 219)

### Added
- **In-App Updates**: Implemented updates check on cold start, updates downloader, Settings integration, and update prompt dialog logic.

## GeckoView Plugin [0.3.2] — 2026-07-01 (versionCode 210)

### Fixed
- **Google Play TV Compatibility**: Set `android.software.leanback` to required in the manifest to ensure correct store filtering on Android TV.

## Player [0.7.2] — 2026-07-01 (versionCode 218)

### Fixed
- **Google Play TV Compatibility**: Set `android.software.leanback` to required in the manifest to ensure correct store filtering on Android TV.

## GeckoView Plugin [0.3.1] — 2026-07-01 (versionCode 209)

### Fixed
- **Google Play TV Compatibility**: Declared `android.hardware.bluetooth` as optional in the plugin manifest to ensure wide device compatibility. (#70)

## Player [0.7.1] — 2026-07-01 (versionCode 217)

### Fixed
- **Google Play TV Compatibility**: Declared `android.hardware.bluetooth` as optional to prevent Google Play from listing the player as incompatible on uncertified/generic TV boxes that do not support Bluetooth. (#70)

## GeckoView Plugin [0.3.0] — 2026-06-30 (versionCode 208)

### Added
- **Remote User Agent Support**: Added support to parse and apply custom user agent headers dynamically inside the `GeckoViewEngine` instance.

## Player [0.7.0] — 2026-06-30 (versionCode 216)

### Added
- **Remote User Agent Support**: Integrated message handling and dynamic user agent overriding in `SystemWebViewEngine` based on phone-provided requests.

## Player [0.6.2] — 2026-06-30 (versionCode 215)

### Added
- **Overlay Permission Rationale**: Created a custom `OverlayPermissionDialog` explaining the requirement for "Display over other apps" permission prior to launching system settings.

## Player [0.6.1] — 2026-06-29 (versionCode 214)

### Fixed
- **SAS Authentication & Protocol**: Resolved handshake state issues, socket connection drops, and keystore-related keyPassword signing configurations.

## Player [0.6.0] — 2026-06-28 (versionCode 213)

### Added
- **SAS Pairing Handshake**: Implemented the Secure Association Service (SAS) pairing handshake using cryptographically verified short authentication strings (SAS) for secure, out-of-band receiver authentication. (#66)
- **User Script Management**: Added a custom user script manager supporting dynamic JavaScript injection into the system WebView. (#61)
- **YouTube Keep-Alive Script**: Integrated a YouTube keep-alive script to prevent media playback freezes in background WebView instances. (#61)

## Player [0.5.0] — 2026-06-22 (versionCode 212)

### Added
- **WebView Video Controls**: Injects a custom video control script (`pb-video-control.js`) into the System WebView to enable remote control commands over web playback. (#59)
- **Browser Integration**: Integrated navigation controls and remote key event translation into `BrowserActivity`. (#59)

## Player [0.4.0] — 2026-06-22 (versionCode 211)

### Added
- **Diagnostics Logger**: Support for in-app diagnostics logs and `FileLogger` writing to disk. (#54)
- **Diagnostics Setting**: Added toggle to enable/disable file logging in Settings. (#54)

### Changed
- Improved player activity and playback coordinator state management. (#56)

## GeckoView Plugin [0.2.7] — 2026-06-22 (versionCode 207)

### Fixed
- Fixed keystore path signing failure by converting `storeFile` to an absolute path. (#53)

## Player [0.3.0] — 2026-06-21 (versionCode 210)

### Added
- **Intro Skipping (IntroDB)**: Support to skip intro, recap, and outro segments using IntroDB, featuring interactive skip buttons and automated skips. (#48)
- **Gated History**: Option to disable casting history under Settings, preventing saving new entries and hiding the history panel. (#49)
- **Custom Add-on Subtitles**: Support for custom external and add-on subtitles in the player. (#44)
- **Security-First Cast Connections**: Enforced `wss://` secure websocket connections exclusively and removed plaintext HTTP/WS fallbacks. (#41)

### Fixed
- Resolved MPV skip segment delays and skip button persistence issues. (#48)
- Fixed IPv6 connection wrapping/parsing issues. (#44)

## Player [0.2.9] — 2026-06-17 (versionCode 209)

### Changed
- Settings: Updated "Allow insecure connections (ws)" toggle description to warn users about security implications of unencrypted websocket connections. (#36)

### Removed
- ContentSniffer: Removed unused legacy `getUnsafeOkHttpClient` helper. (#36)

## Player [0.2.8] — 2026-06-15 (versionCode 208)

### Added
- Dedicated subtitle selection overlay with live dialogue line previews, fine/coarse sync control, and language grouping. (#32)
- Save and persist playback progress every ~5 seconds to survive force-kill events. (#31)

### Changed
- TV history now stores raw playlist payloads, ensuring subtitles, audio language, and headers are fully restored on replay. (#31)
- Replaced on-pause screenshot capture with poster/backdrop art. (#31)

### Removed
- Video filters feature end-to-end from TV engine, UI, and settings. (#31)

## Player [0.2.7] — 2026-06-13 (versionCode 207)

### Added
- Device Guard warning: Added alert dialog to notify user if the TV app is running on a non-TV (phone) device. (#25)

### Fixed
- Fixed startup crash on some Android TV devices (where `ACTION_MANAGE_OVERLAY_PERMISSION` is missing) by wrapping overlay permission settings page launch in a try-catch block and restricting check to Android 14+. (#28)

## GeckoView Plugin [0.2.6] — 2026-06-13 (versionCode 206)

### Changed
- Refactored code structure: renamed the project module to `geckoview-plugin` and moved code to the package `com.playbridge.geckoview.plugin`. (#25)
- uBlock Origin is now bundled as a shared asset in the KMP module instead of a project-local asset. (#26)

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
