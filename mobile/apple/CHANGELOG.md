# Changelog — PlayBridge Sender (iOS phone)

All notable changes to the iOS phone app (`app.playbridge.phone`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.16.0] — 2026-10-31 (build 11)

> **Works best together**: Chapters and skip segments on the seek bar, the playback failure reason and Apple TV app control need PlayBridge receivers 0.16.0. Older receivers keep working with the controls they report.

### Added
- **Play on sheet**: One Play on sheet replaces the Devices screen: the current receiver, This phone, AirPlay and saved devices, nearby devices, pairing, adding a device by address, device details and Configure TV.
- **Browser on a TV**: Play on → Add a device can host a page for a TV's web browser, pair it with a code and cast to it. Keep PlayBridge open while the TV browser is connected.
- **This phone**: Picking This phone, or switching to another device, disconnects the previous one but leaves it playing. A warning appears first when it's playing something the phone serves or something a website controls.
- **Dashboard redesign**: A top bar with Remote and Settings, a chip naming where videos play, one grid of tiles with live status, and a Customize home screen to reorder and hide tiles.
- **Phone Files and phone lists**: Redesigned Phone Files. Lists of any length play on the TV as a moving queue, with a music-style Up next sheet and colours taken from the current item's artwork. Song art comes through from the iPhone.
- **Seek bar with chapters and skip segments**: A wavy seek bar shows chapter gaps, intro/recap/credits bands and the buffered range; scrubbing shows the chapter or segment and snaps to chapter starts and segment ends.
- **Volume and subtitles sheets**: The volume pill opens a sheet with an exact-level slider or step buttons, plus mute. The Subtitles sheet has Tracks, Adjust and Add pages, readable track names and timing in ±0.1/±0.5 s steps.
- **Apple TV app control**: The remote's D-pad, OK, Back and Home drive the whole PlayBridge Apple TV app while it's idle.
- **Bridged app tiles**: Each bridged app gets its own tile colour (Automatic, a chosen colour or the site's `theme_color`) and a real icon, falling back to the site's favicon and then a monogram.
- **Blob subtitles**: Subtitles that a site builds in the page (`blob:` URLs) appear in the cast sheet and play on the TV, served from the iPhone.

### Changed
- **Remote**: Matches the Android remote: a bottom bar with Back, Home, Keyboard, the volume pill and Stop; Loop and AirPlay queue controls move into More; player settings are grouped as Playback, Picture and Audio.
- **Streaming services**: Casting is turned off on YouTube and paid streaming and music services; pages still play on the phone, and casting shows "This site doesn't allow casting."
- **Playlists**: The IPTV screen is now called Playlists.
- **Licence**: PlayBridge's own code is now under MPL-2.0.

### Fixed
- **Nothing playing after connecting**: Connecting to a TV that's already playing shows the title, tracks and seek bar straight away.
- **Saved receivers after a network change**: Saved receivers no longer show as Offline when Bonjour finds them on the new network.
- **Bridged apps waiting for ad blocking**: Installed bridged apps no longer wait for the full ad-block rules to compile before loading.
- **This phone crash**: Tapping This phone no longer crashes.

## [0.15.0] — 2026-10-09 (build 7)

### Added
- **Bridged Apps**: Isolated website shortcuts with editable home URLs, dashboard reordering, per-site casting permissions and linked episode queues.
- **Phone playback**: MPV playback including MKV, live episode queues, speed and track preferences, subtitle timing/style, Fit/Fill, touch lock, swipe scrubbing and double-tap seeks.
- **Casting**: Persistent AirPlay sessions and queues, local-phone playback destinations, late subtitle attachment and receiver-owned queue controls.
- **Browser controls**: Fullscreen browsing, refreshed menus, richer media previews and a persistent remote/cast control experience.

### Changed
- **Playback history**: Record cast progress, preserve resume positions and honor the receiver-history preference for private casts.
- **Store readiness**: Use a numeric marketing version, add privacy disclosures and About information, and require confirmation for website-triggered downloads.

### Fixed
- **Website playback consent**: Ask for website-casting approval when choosing a playback destination, so fresh installs can obtain a usable device id before playing.
- **Playback continuity**: Preserve route, headers, pause intent and episode identity through retries, rotation and background transitions; improve subtitle selection and stream detection.
- **Browser continuity**: Restore app shortcuts lazily at their home URL and preserve browser/remote navigation and website controls.
- **External casting**: Improve DLNA renderer compatibility, Google Cast session recovery and stalled-load timeout handling.

### Security
- **Website authority**: Isolate the native page API, require user gestures for destination selection and scope queues and permissions to the owning document.
- **Media credentials**: Preserve HTTPS certificate verification, restrict private media access to approved origins, scope headers across origins and reject unsafe remote registration URLs and redirects.

## [0.3.3-alpha] — 2026-07-03 (build 6)

### Changed
- **Adblocker & Cosmetic Filtering**: Refined WKWebView element picker logic, custom CSS selectors, and thumbnail resolution patterns.

## [0.3.2-alpha] — 2026-07-01 (build 5)

### Removed
- **Referer Fallback**: Removed automatic fallback that populated `Referer` headers with the page's origin URL when requesting video streams.

## [0.3.1-alpha] — 2026-06-30 (build 4)

### Added
- **Visual Element Picker (Cosmetic Blocker)**: Added browser tool enabling users to tap on web elements, preview hiding them, and permanently block ad layouts or block their source domains.
- **Custom Adblock Rules**: Custom filters and blocked hosts are now persistent.

## [0.3.0-alpha] — 2026-06-30 (build 3)

### Added
- **Phone Files**: Added local file browser to share and cast local video and audio files from the phone, backed by a local HTTP file server (`LocalFileServer.swift`).
- **IPTV Playlists & Channels**: Support for adding and managing IPTV playlists, channel lists, and groups, with search and direct video stream casting.
- **Collections**: Support for custom user media collections to organize video streams.
- **Persistent Stores**: Implemented localized Swift-based data stores (`BrowserDataStore`, `CollectionsStore`, `IptvStore`) for settings and user content persistence.

## [0.2.0-alpha] — 2026-06-29 (build 2)

### Added
- **SAS Pairing Handshake**: Implemented full Secure Association Service (SAS) pairing flow with short authentication string rendering, verification popups, and secure socket connection state machine.
- **SPKI SSL Pinning**: Enforced TOFU (Trust On First Use) security on WSS websocket endpoints using SHA-256 SPKI public key pinning.

### Fixed
- **Adblock compilation stability**: Resolved EasyList CSS rule compilation failures by correctly mapping negated domain filters and custom CSS pseudo-class/scriptlet exclusions.

## [0.1.0-alpha] — 2026-06-18

### Added
- **Unified Cast Sheet**: Overhauled video stream casting interface into a single, cohesive bottom sheet view (`CastSheet.swift`). Features:
  - Play / Queue / Browse tabbed segments.
  - Capability configs: player engines (TV Default, ExoPlayer, AVPlayer, VLC) and Proxy options (Off, Auto, etc.) mirroring Android.
  - Inline live `AVPlayer` preview rendering streams with custom headers directly.
  - Interactive cards with stream media type badges, host domain names, variants (quality/bitrate menu), and subtitle attach selection.
- **Robust Ad-blocking Engine**: Fully resolved WebKit's native content-blocker constraints:
  - Multi-identifier compilation system to support 100% of EasyList & EasyPrivacy rules by dividing them into individual system-registered `WKContentRuleList` objects.
  - CSS selector validation filtering out custom adblock pseudo-classes and scriptlets (like `+js(...)`, `:has(...)`, `:contains(...)`) to prevent `WKErrorDomain` error 6 compilation failures.
  - Separation of domain lists into WebKit-compatible `if-domain` (positive matches) and `unless-domain` (negated matches starting with `~`).
  - Leading wildcard (`*`) and dot (`.`) stripping on domains to clean EasyList entries (e.g. converting `*.example.com` to `example.com`).
  - Empty rule list safeguard appending a dummy block rule if list evaluation returns 0 rules, preventing WebKit "Empty extension" errors.
  - Detailed diagnostic compilation logging mapping failed lists and WebKit's underlying `userInfo` dictionary to the settings alert pop-up.
  - Real-time in-memory URL sniffer intercepting and filtering ad/tracker scripts and redirect flows prior to populating the detected video streams list.

### Changed
- **Branding Simplification**: Removed all custom logo asset (`Image("Logo")`) graphic references, replacing the address bar logo with a clean system symbol (`Image(systemName: "house.fill")`) and simplifying all dashboard/browser text branding to "PlayBridge".
- **Browser State Persistence**: Hoisted `BrowserStore` to the root application view level (`PlayBridgePhoneApp.swift`) as a shared environment object. This prevents the browser webview from resetting/reloading when navigating between the Dashboard and the Browser.
- **Smooth Navigation Transitions**: Applied `.transition(.opacity)` crossfades to navigation screen changes.

### Fixed
- Fixed browser reset bug on navigation.
- Fixed `WKErrorDomain` compile error 6 due to empty arrays, Negated domains (`~`), wildcards (`*`), and scriptlets in custom filters.
