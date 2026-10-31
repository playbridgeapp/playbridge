# Changelog — PlayBridge Sender (Android phone)

All notable changes to the phone app (`com.playbridge.sender`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.16.0] — 2026-10-31 (versionCode 229)

> **Upgrading removes the Library.** The Library, Stremio add-ons, debrid providers and Nuvio scrapers are gone, and their data is deleted on upgrade: installed add-ons, watchlist, resume positions, scrapers, debrid and TMDB/OMDB/TVDB keys, and their caches. Collections, IPTV, bridged apps, browser casting, Phone Files, downloads and the remote stay. Old backups still import; the removed sections are ignored.
>
> **Works best together**: Player Home, the Loop switch, browser fullscreen state, chapters and skip segments on the seek bar, the playback failure reason and live remote updates need PlayBridge TV Player 0.16.0, PlayBridge Desktop 0.16.0 or PlayBridge for Apple TV 0.16.0. Older receivers keep working with the controls they report. Full Android TV control works with any Android TV or Google TV, with or without PlayBridge installed.

### Removed
- **Library, add-ons, debrid and Nuvio scrapers**: Removed from the app, including their screens, dashboard tiles and settings, `stremio://` add-on installs, magnet handling, add-on episode resolution and Library watch-progress tracking.

### Added
- **Android TV full control**: Pair with any Android TV or Google TV using the on-screen code and control the D-pad, Home, Back, volume, the keyboard and Open app from the remote. Paired TVs are listed and removable in Settings → TV.
- **TV remote without PlayBridge running**: With a Full control pairing, the Remote opens even when PlayBridge isn't running on the TV, shows "PlayBridge isn't running on this TV." with **Open PlayBridge**, and switches to the full remote in place once PlayBridge connects. The cast sheet offers **Open PlayBridge on <TV>**.
- **Remote that matches the receiver**: The remote shows only the keys, player settings, browser controls and volume control the connected receiver reports, follows the engine active on the TV, and updates live when that changes (for example when the TV's GeckoView plugin is installed or removed).
- **Apple TV app control**: The D-pad, OK, Back and Home drive the whole PlayBridge Apple TV app while it's idle, not just the player.
- **Seek bar with chapters and skip segments**: A wavy seek bar shows chapter gaps, intro/recap/credits bands and the buffered range; scrubbing shows the chapter or segment and snaps to chapter starts and segment ends.
- **Volume level**: One line shows the receiver's volume and mute state ("TV volume 40/100 · Unmuted"). The volume pill opens a sheet with an exact-level slider or large step buttons, plus mute. The media notification's volume follows the real level.
- **Subtitles sheet**: One sheet with Tracks, Adjust and Add pages: filter by origin, language chips and search for long lists, readable track names ("English" instead of "eng"), timing in ±0.1/±0.5 s steps with Reset, and adding from the page, a phone file or a link.
- **Up next**: Upcoming items with watched and progress state, and an All sheet with season tabs to jump ahead or manage the queue.
- **Playback failure reason**: When a receiver gives up on a video, the phone shows why ("The media couldn't be loaded (HTTP 403)") in a banner that stays until dismissed, and in the cast notification.
- **Play on sheet**: One Play on sheet replaces the Devices screen and the Cast to dialog: the current receiver, This phone and saved devices, nearby devices, pairing, adding a device by address, device details and Configure TV.
- **This phone**: Picking This phone, or switching to another device, disconnects the previous one but leaves it playing. A warning appears first when it's playing something the phone serves or something a website controls. A TV that drops stays chosen, and the Dashboard shows "Lost <TV> · Use this phone".
- **Phone Files and phone lists**: Redesigned Phone Files. Lists of any length play on the TV as a moving queue, with a music-style Up next sheet (reorder, remove, repeat, save) and colours taken from the current item's artwork.
- **Dashboard redesign**: A top bar with Remote and Settings, a chip naming where videos play, one grid of tiles with live status, and a Customize home screen to reorder and hide tiles.
- **Bridged app tiles**: Each bridged app gets its own tile colour (Automatic, a chosen colour or the site's `theme_color`) and a real icon, falling back to the site's favicon and then a monogram.
- **Media controls in the cast notification**: Media title, Play/Pause, Stop and a seek bar while casting, on the lock screen too; the phone's volume keys control the receiver.
- **Downloads library**: Thumbnails and video length, date and source site for every download, search and sort, multi-select delete, tap to play on the selected device (cast to a receiver or play in the app), and the now-playing bar for quick access to the remote. Each download's menu adds Cast to…, Open with…, Share, Rename, Open source page, Show in Downloads folder and Remove from list with undo. In-progress downloads show speed and time left.
- **Download notifications**: Follow each download's progress, pause/resume/cancel from the shade, retry failures, and open completed files. Multiple downloads are grouped together.
- **Direct cast controls in the background**: Keep receiver controls available while external media is loaded or paused, including Direct and Via proxy routes, without holding CPU or Wi-Fi locks for those routes.
- **Notification recovery**: When notifications are off, show a one-time playback or casting reminder with a shortcut to enable them or open notification settings.
- **Website media seeking**: Show playback position, duration and speed in media controls, with seeking for supported videos and background in-app playback.
- **Blob subtitles**: Subtitles that a site builds in the page (`blob:` URLs) appear in the cast sheet and play on the TV, served from the phone.
- **Remote haptics, button pad and keyboard**: Adjustable vibration on remote presses, a toggle between the swipe pad and arrow buttons, and a keyboard sheet for typing into TV text fields.

### Changed
- **Remote redesign**: The remote adapts to portrait and landscape, uses the seek bar for play/pause, seeking and volume, hides the pad while media plays, and shows the TV's activity (Watching, Listening, Browsing, Viewing) in its header. Stop moves to the bottom bar.
- **Seeking**: A quick swipe always seeks; scrubbing starts after a 0.4 s hold. The pad steps by distance with stationary hold-to-repeat.
- **Player controls**: The Loop control is a switch when the TV reports its state, Home from the player returns the TV to PlayBridge's idle screen, and More is hidden when it has nothing to show. The title line no longer repeats Paused, Buffering or Live.
- **Browser fullscreen and typing**: The remote follows the TV browser's fullscreen state, and typed text reaches fields inside embedded players and in fullscreen.
- **Full control connection**: Connects to the selected TV straight away without scanning, stays up while PlayBridge is open and closes 60 seconds after it goes to the background.
- **Pop-up blocked toast**: Shows where the pop-up was going and which site opened it, with **Open**, **Always allow** and close, and dismisses itself after about 6 seconds.
- **Address bar**: Copied plain text shows as "Text you copied" and searches. localhost, local IP addresses, `.local` names and `name:port` open over http; `javascript:` input is searched, never run.
- **Desktop site**: A per-tab setting; new tabs start in mobile mode.
- **Website media notifications**: Use the page title with informative media details or the site host, route controls to the displayed media owner, and open its playing tab when tapped.
- **Linked website casts**: The phone no longer asks the receiver to resend everything every 2 seconds during a website cast.
- **Streams**: No longer added on first launch or treated specially; an existing Streams tile stays as an ordinary bridged app.
- **Licence**: PlayBridge's own code is now under MPL-2.0.

### Fixed
- **Video detection in popup tabs**: Videos on pages a site opens in a new tab or popup are detected again.
- **Saved receivers after a Wi-Fi change**: Saved PlayBridge receivers no longer show as Offline when discovery finds them on the new network, and their addresses are refreshed.
- **Duplicate DLNA devices**: A UPnP renderer (such as Kodi) appears once instead of several times; saved devices keep matching.
- **Downloads that wouldn't play on Apple TV**: MP4s made from HLS downloads now write their AAC audio correctly, so AVPlayer plays them.
- **Browser on a TV Stop**: Stopping the TV browser host no longer stops another receiver that's playing.
- **Pairing a second phone**: Pairing another phone with the same Android TV no longer disconnects the first.
- **Android TV pairing storage**: Pairings are saved atomically, with "Couldn't save the pairing" or "Couldn't remove the TV" and a retry when that fails.
- **Keyboard in web pages**: Focused fields in web pages and bridged apps stay above the soft keyboard.
- **Player-to-browser transitions**: Switching from the player to the TV browser clears the old title, playlist and seek bar from the remote.
- **Roku playback**: A stale stop no longer interrupts a newer load.
- **Deleting downloads**: Delete now removes the saved video from the phone instead of only the list entry, asking for permission when Android requires it.
- **Open source page**: New downloads remember the page they were started from, and the page opens in a new tab even when no tab is open.
- **Concurrent downloads**: Give each download its own progress and result notifications, including a resumable paused notification when the foreground time budget is reached.
- **Browser media cleanup**: Release media observers and their background work when tabs close or their browser sessions are replaced.
- **Cast notification startup**: Avoid ghost Connected notifications after a killed session and stop cleanly when Android denies foreground promotion.
- **Notification Disconnect**: Disconnect both external receivers and native TV links, and keep the cast notification until the manager finishes the session. Stop still preserves an idle native connection.
- **Media notification cleanup**: Stop delayed service starts whose last owner has already left, and expire idle media notifications even when no playback state arrives.
- **Media notification titles**: Restore each owner's title, artist and artwork when playback resumes after dismissing or stopping its notification.
- **"Casting" after stopping on the receiver**: When a receiver stops playback on its own without telling the phone (such as stopping a video in PlayBridge Desktop), the phone now notices after a few seconds and the notification switches back to Connected.

### Security
- **Browser engine upgrade**: GeckoView 157 and Android Components 157.0.1, with upstream security fixes.
- **Pairing credential encryption**: Android TV credentials are encrypted at rest with the Android Keystore and excluded from backups and device transfer.
- **Log privacy**: Release builds no longer log media titles, stream URLs, add-on and API URLs that can contain keys, raw API responses, pop-up and magnet links, pairing codes or typed text.
- **Website playback on this phone and other devices**: Videos and subtitles a website plays on This phone, or on DLNA, Chromecast, Roku or a TV browser, now go through the phone's proxy. It reaches only public addresses and the local-network addresses you approved, and checks every redirect and segment.

## [0.15.0] — 2026-10-09 (versionCode 228)

### Added
- **Bridged Apps**: Save websites as isolated app shortcuts, edit their names and home URLs, and reorder dashboard tiles. Approved sites can choose playback destinations and manage linked episode queues.
- **Browser setup and detection**: Fullscreen onboarding with optional uBlock setup, configurable media detection, and refreshed browser menus, cast picker and edge controls.
- **FOSS plugins**: Use opt-in Nuvio plugins from both the Library and approved Bridged Apps.

### Changed
- **Dashboard**: The close (X) button returns to where the Dashboard was opened from, including the bridged app you were in; on a fresh launch it opens Browser. Exit now sits next to X.
- **Legacy Library**: Marked the built-in Library as legacy ("Library (legacy)"), preinstalled the Streams bridged app as "Library", and added an in-app notice pointing users to the new app.
- **Casting controls**: Support receiver-owned queues, attaching subtitles during playback, private casts without receiver history, and playback progress callbacks for native senders.

### Fixed
- **Website playback consent**: Ask for website-casting approval when choosing a playback destination, so fresh installs can obtain a usable device id before playing.
- **Browser continuity**: Restore tabs lazily, preserve Bridged App sessions and input focus, and return reliably between Browser, Dashboard and Remote.
- **Playback and discovery**: Reduce probe storms and memory pressure; improve stream thumbnails, HLS parsing, DLNA discovery/playback and Google Cast reconnects.
- **Resume and file serving**: Preserve page-cast resume positions and correctly serve suffix ranges, invalid-range responses and HEAD requests for local files.

### Security
- **Website and extension approval**: Isolate page integrations, bind linked sessions to their owning page, and require native approval for extension installation and permissions with overlay-touch protection.
- **Proxy credentials**: Scope credentials to the original media origin, restrict private-network access to approved media, reject unsafe remote registration URLs and redirects, and bound registration responses.
- **Diagnostics**: Fetch TV logs only through the paired encrypted connection and redact sensitive destination and media details.

## [0.13.4] — 2026-08-31 (versionCode 227)

### Fixed
- **New-tab connection modal**: Keep the in-app browser UI mounted when a new tab is selected before its Gecko session exists, so the connections / Cast to picker no longer reopens on New Tab or Open in new tab.

## [0.13.3] — 2026-08-29 (versionCode 226)

### Fixed
- **In-app browser WebRTC**: Keep GeckoView's `org.webrtc.videoengine` JNI classes when stripping duplicate WebRTC types, so sites that use getUserMedia no longer crash the release app.

## [0.13.2] — 2026-08-29 (versionCode 225)

### Fixed
- **Release connection stability**: Disable R8 code and resource shrinking after release-only reflection failures broke Wire protocol serialization during receiver connections and pairing.

## [0.13.1] — 2026-08-28 (versionCode 224)

### Fixed
- **Android 16 startup**: Preserve SnakeYAML package names during R8 optimization so GeckoView no longer crashes while initializing its debug configuration on Android 16 devices, including 16 KB page-size lab devices.
- **Foreground-service timeouts**: Classify the long-lived phone-hosted browser receiver as a connected-device session and stop app-owned foreground services immediately when Android reports a timeout.
- **Long downloads**: Pause Android 15+ downloads before the `dataSync` foreground-service limit and keep timeout-paused work from being automatically re-enqueued.

## [0.13.0] — 2026-08-11 (versionCode 223)

> **Works best together**: Screen mirroring, mixed-media playlists, and the skip pre-play preference are cross-device features introduced in this coordinated release. Use PlayBridge TV Player 0.13.0+ and Desktop 0.13.0+ on the other side for full support.

### Added
- **Screen mirroring**: Mirror the phone to compatible PlayBridge TV and Desktop receivers over WebRTC with H.264 video, optional playback audio, orientation-aware sizing, and selectable quality limits. Google Cast and DLNA receivers can receive the same capture as a video-only H.264/MPEG-TS live stream.
- **Consent-gated page casting**: Cast the page you are browsing to linked TV/Desktop receivers with per-site consent prompts, a dedicated consent settings screen, and richer page media/subtitle metadata shared over the protocol.
- **Browser fullscreen mode**: Toggle fullscreen chrome in the in-app browser from the menu.
- **Skip pre-play cast preference**: New casting preference that lets supported receivers skip pre-play screens when loading casts.

### Changed
- **Release optimization**: Enable R8 code and resource optimization for smaller, optimized FOSS and Play release builds.
- **External screen mirroring**: Keep Google Cast playback active across phone orientation changes by using a stable encoded canvas and resizing only the captured display source.
- **Menu sheet layout**: Fill the browser menu grid left-to-right and add paging only once the 2x5 grid is full, hiding dots and swipe on single-page menus.

### Fixed
- **Remote control pointer input**: Smooth high-frequency pointer input and anchor image transforms to the touch midpoint so remote-control sessions track gestures accurately.

## [0.12.0] — 2026-08-03 (versionCode 222)

### Added
- **Google Cast**: Chromecast/Google Cast sender support with persistent sessions, standard media controls, and the PlayBridge custom receiver.
- **Phone-hosted casting**: Browser receiver target hosted by the phone, with selectable Direct, Via phone, and Via proxy stream routes.
- **Receiver discovery**: Unified PlayBridge, DLNA, and Roku discovery with destination-specific casting.
- **HLS Cast segment hints**: When packaging Via phone/proxy, probe playlists and attach segment-format hints so Google Cast loads accept MPEG-TS bytes behind misleading suffixes (e.g. `.jpg` on some CDNs).

### Changed
- **Casting experience**: Redesigned Devices and cast destination flows, including richer media previews, route selection, and active-session state.
- **Playback and routing**: Improved external cast loading, reconnects, timeline handling, stream proxying, and playback-engine failover for difficult streams.
- **Cast sheet ranking (SPA)**: Same-document navigations keep earlier detections castable while advancing a media lifecycle so the view you just opened is preferred in ranking.
- **Diagnostics**: Debug network diagnostics and expanded casting/connection telemetry; detector media URLs and sensitive headers stay debug-build-only, with redaction in persisted logs.

### Fixed
- **Origin-protected CDNs**: Forward page `Origin` on Via-phone/DLNA upstream fetches so same-site media hosts no longer return 403 (aligned with Desktop).
- **Edge long-press vs gestures**: Suppress Gecko link context menus when the press began inside system back/home gesture insets.

## [0.11.2] — 2026-07-14 (versionCode 221)

### Fixed
- **Pairing Security**: Hardened local pairing certificate generation and securely stored private keys from unauthorized local system reads.

## [0.11.1] — 2026-07-11 (versionCode 220)

### Fixed
- **In-app player (no video / silent video)**: TV-aligned ExoPlayer factory with Media3 **FFmpeg audio PREFER** (`prebuilt/media3/lib-decoder-ffmpeg-release.aar`), hardware-first video, decoder fallback, and memory-aware load control. TextureView + deferred `prepare()` until the surface is attached (Compose SurfaceView left permanent buffering). Stuck recovery only after first frame and does not mute audio when FFmpeg is loaded. (#103)
- **Library Watch**: No longer auto-reconnects to the TV from the detail screen; pairing stays on the Connection sheet. (#103)

### Changed
- **FFmpeg packaging**: Phone depends on the shared repo prebuilt FFmpeg decoder AAR (same artifact as TV). (#103)

## [0.11.0] — 2026-07-08 (versionCode 219)

### Added
- **Nuvio DOM & Crypto Support**: Integrated JSoup `1.18.1` and implemented DOM parsing (`DomBridge`) and cryptographic helpers (`CryptoBridge`/`PluginCrypto`) inside sandboxed QuickJS scraper plugin runner.

### Changed
- **Concurrent Stream Resolution**: Refactored stream resolution to fetch from multiple Stremio/Nuvio sources concurrently using Kotlin coroutines and `channelFlow` for immediate and progressive UI updates.

### Fixed
- **Addon Loading & Navigation**: Gated addon navigation by keying on `addonBaseUrl` instead of name, mapped request types to Stremio-supported types, and made `seasonPosters` nullable to prevent deserialization crashes.

## [0.10.0] — 2026-07-07 (versionCode 218)

### Added
- **FOSS/Play Distribution Flavors**: Play builds strip `REQUEST_INSTALL_PACKAGES` via a manifest overlay, stub out the APK installer, disable Debrid + Nuvio scraper plugins, and hide sideload links. (#90)
- **Clear Data Sheet**: Firefox-style Clear Data sheet (Gecko StorageController + Room), paged menu sheet, and a 200 MB Gecko disk-cache cap. (#90)
- **Battery Exemption Guidance**: Settings-page guidance replaces the restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission. (#90)

### Fixed
- **Watch-Progress Tracking**: Gated skip-ahead catch-up to deliberately-started episodes only, forward-only advance detection, DLNA final resume flush, resume cleanup before rewatch dedup, and dropped the fabricated season-1 fallback. (#90)
- **Media Notification & Background Playback**: Reworked the media notification and in-app player background playback; page/tab playback now stops when the cast sheet opens. (#87)
- **Connection UX**: Single connection popup, no reconnect after a deliberate disconnect, removed the Keep-trying button, and restored navigation on return to shell. (#87, #90)
- **Reactive Theme Switching**: Theme changes apply without `Activity.recreate`, plus theme/UI styling consistency fixes. (#85, #87)
- **Touchpad Click Tracking**: Improved touchpad click tracking in the remote control and cast sheet. (#86)

## [0.9.0] — 2026-07-04 (versionCode 217)

### Added
- **In-App Updates**: Implemented dynamic updates check on cold start, local APK downloader (`ApkInstaller`), and a manual "Check for updates" option in Settings.

### Fixed
- **Touchpad Area Gestures**: Locked the touchpad two-finger gestures to a single mode (zoom or scroll) for its lifetime using accumulator logic, and fixed a panY calculation typo.

## [0.8.0] — 2026-07-03 (versionCode 216)

### Added
- **Nuvio Scrapers**: Integrated a sandboxed JavaScript runtime powered by QuickJS, with Cheerio and CryptoJS support for scraper scripts.
- **Library Add-ons UI**: Created addon models, database provider migrations, addon settings UI, and scraper runner logs.

## [0.7.1] — 2026-07-01 (versionCode 215)

### Fixed
- **Google Play Compatibility**: Declared `android.hardware.touchscreen` as required in the manifest to ensure the phone app is filtered out on non-touch TV devices.

## [0.7.0] — 2026-06-30 (versionCode 214)

### Added
- **User Agent Presets**: Added custom User Agent presets sheet supporting Android/Desktop/TV browser spoofing and settings repository persistence.
- **User Agent Selection UI**: Built the `UserAgentSheet` component and menu integration to allow hot-swapping web browser layouts.

### Removed
- **Referer Fallback**: Removed automatic fallback that populated `Referer` headers with the page's origin URL when requesting video streams.


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
