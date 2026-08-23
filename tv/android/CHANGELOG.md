# Changelog — PlayBridge TV (Android TV)

Covers both APKs in this tree: the **player** (`com.playbridge.player`) and the **GeckoView plugin** (`com.playbridge.geckoview.plugin`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Player [0.13.0] — 2026-08-11 (versionCode 228)

> **Works best together**: Screen mirroring, mixed-media playlists, and the skip pre-play preference are cross-device features introduced in this coordinated release. Use PlayBridge Phone 0.13.0+ and Desktop 0.13.0+ on the other side for full support.

### Added
- **WebRTC screen mirroring**: Receive H.264 phone screen mirrors with lifecycle-safe rendering, orientation-aware video, capability advertisement, and validated signaling.
- **Mixed-media playlists**: Play combined audio/video/image playlists from phone senders with a dedicated presentation surface for image slides.
- **Page casting playback policy**: Enforce sender page playback and subtitle resource policies when casting linked browser pages.
- **Skip pre-play preference**: Honor the skip pre-play cast preference when loading casts from senders.

### Changed
- **Release optimization**: Enable R8 code and resource optimization for smaller, optimized FOSS and Play release builds.
- **Compact progress visibility**: Keep the playback progress bar visible in the compact player controls overlay.

### Fixed
- **Remote pointer input**: Smooth high-frequency pointer input and anchor image transforms to the touch midpoint during remote-control sessions.

### Security
- **Persisted pairing tokens**: Store paired-device credentials as SHA-256 digests, migrate compatible legacy records, and refresh active authentication state after pairing changes.

## Player [0.11.0] — 2026-07-30 (versionCode 226)

### Added
- **Live/Network Exo Recovery**: Classifies Media3 errors in `ExoRendererService` and recovers in-engine (live edge / re-prepare) before escalating; host engine auto-switch is limited to startup hard failures, with failover allowed only after in-engine recovery is exhausted.
- **Embedded Subtitle Offset**: Wires ExoPlayer embedded subtitle delay through `OffsetTextRenderer` so phone/TV offset controls apply to in-band tracks (fixes #171).
- **Debug Network Diagnostics**: Optional debug-only network logging for stream fetches, ContentSniffer, skip/subtitle downloads, and browser traffic (not retained in release diagnostics).

### Fixed
- **Subtitle Sync Reset**: Forces absolute zero offset on Sync Reset instead of applying a captured delta that could leave engine/UI state uncleared.
- **Update Dialog Snooze**: Persists "Later" per version so Activity recreation does not re-show the update prompt until a newer release; manual Check for updates still prompts.

### Security
- **WebView Mixed Content**: Switched `mixedContentMode` from `ALWAYS_ALLOW` to `COMPATIBILITY_MODE` to reduce MITM/XSS exposure from active HTTP content on HTTPS pages.
- **WebView Content Access**: Disabled `allowContentAccess` to block local content-provider access from the TV browser WebView.
- **Auth Response Disclosure**: Stopped echoing authentication tokens in `auth_response` payloads (shared protocol hardening, SEC-005).

## Player [0.10.1] — 2026-07-22 (versionCode 225)

### Fixed
- **Android 15 Boot Compatibility**: Kept the boot-started receiver server limited to the `connectedDevice` foreground-service type, preventing restricted `mediaPlayback` startup from `BOOT_COMPLETED` while preserving automatic discovery and background casting.

## Player [0.10.0] — 2026-07-19 (versionCode 224)

### Added
- **Integrated TV Library**: Replaced separate history/favorites screens with a unified, TV-friendly Library featuring "Continue watching" (resumable items), "Recent" (new or completed items), and "Favorites" sections.
- **Library Session Playback**: Saves the full playlist and current index to allow resuming playlist sessions directly from the Library.
- **Private Frame Thumbnails**: Added lightweight, private frame captures (320x180 JPEGs) stored locally under `noBackupFilesDir` for items lacking artwork, with support for automatic refresh and cache invalidation.
- **Adaptive Video Quality Settings**: Extended the `tracks` event and `player_settings` command flow to support adaptive video quality selection (auto/max height) from phone remotes.
- **Looping Support**: Added persistence and restoration of playback looping state.
- **Process Isolation**: Moved MPV, ExoPlayer, and WebView player engines into their own dedicated, isolated background processes to prevent renderer instability or crashes from affecting the main application.
- **Private-Process Provider Bridge**: Added a non-exported broadcast bridge for WebView status, queue draining, and replacement launches.

### Changed
- **UX & Focus Styling**: Consolidated sidebar destinations, improved dropdown initial focus, added consistent TV focus scaling and overlay transitions, and replaced the generic spinner with an animated PlayBridge loading blob.
- **WebView Separation**: Moved System WebView browser into `:web` with a unique data-directory suffix to isolate renderer processes.
- **Security & Redaction**: Redacted credentials/tokens and hashed media/history identifiers in diagnostic logs, with URL redaction helpers for authenticated streams and external subtitles.

### Fixed
- **Playback Context Restoration**: Restores audio, subtitle, and loop preferences reliably by deferring restoration while placeholder tracks exist, persisting pending status until verified, and prioritizing normalized metadata matching over fragile positional track IDs.
- **Stale Start Positions**: Restart completed Recent/Favorite items from zero even if the cast payload contains a stale position.
- **Activity Recreation**: Preserves the active main-screen destination across Activity recreation and trips to settings.
- **Dialog & UI Races**: Resolved races where long-press dialogs accidentally triggered resume, and delayed the pre-play "Play now" action until counting down.
- **Browser Media Control**: Pauses backgrounded browser media, and prevents WebView processes from improperly owning or starting `ServerService`.

## Player [0.9.3] — 2026-07-14 (versionCode 223)

### Fixed
- **TLS Keystore Security**: Hardened pairing certificate keystore credentials and protected private key storage from unauthorized local system reads.

## Player [0.9.2] — 2026-07-11 (versionCode 222)

### Changed
- **FFmpeg decoder AAR**: Moved to shared `prebuilt/media3/lib-decoder-ffmpeg-release.aar` (single copy with phone). Gradle path updated; runtime behavior unchanged. (#103)

## Player [0.9.1] — 2026-07-08 (versionCode 221)

### Changed
- **Gradle Wrapper**: Upgraded gradle wrapper version to 9.5.1 in player.

## GeckoView Plugin [0.3.4] — 2026-07-08 (versionCode 212)

### Changed
- **Gradle Wrapper**: Upgraded gradle wrapper version to 9.5.1 in geckoview plugin.

## Player [0.9.0] — 2026-07-07 (versionCode 220)

### Added
- **TheIntroDB Skip Segments** (https://theintrodb.org): second skip-segments provider alongside IntroDB. Keyless, works with TMDB or IMDb ids, and covers **movies** (credits) as well as episodes — including multiple ranges per type and open-ended credits that run to the end of the file. New "Skip Segments Provider" setting (IntroDB / TheIntroDB / Both — default Both, where IntroDB wins per segment type and TheIntroDB fills the gaps) plus a configurable TheIntroDB API URL in Settings → Integrations. (#90)
- **FOSS/Play Distribution Flavors**: Play builds strip `REQUEST_INSTALL_PACKAGES` via a manifest overlay, stub out the APK installer, and hide sideload links (GeckoView plugin prompt). (#90)
- **Exit-App Action**: New exit-app action in Settings. (#87)

### Fixed
- **Local TLS Trust**: Replaced the `ContentSniffer` trust-all SSL with `LocalSelfSignedTrustManager` — system trust first, then a single valid self-signed cert for LAN hosts; the hostname verifier re-validates public hosts against system trust. (#90)
- **MPV Skip Clamping**: Clamped open-ended skip seeks to media duration for MPV. (#90)
- **Injected Input Events**: Set `SOURCE_TOUCHSCREEN` on injected events so remote touchpad input behaves like real touches. (#86)
- **Theme & UI Styling**: Resolved theme and UI styling inconsistencies, removed `StaticAuroraBackground`, and fixed playback/connection races from a code audit. (#85, #87)

## GeckoView Plugin [0.3.3] — 2026-07-07 (versionCode 211)

### Fixed
- **Injected Input Events**: Set `SOURCE_TOUCHSCREEN` on injected events and improved touchpad click tracking in the browser engine. (#86)

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
