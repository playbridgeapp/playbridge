# Changelog — PlayBridge Sender (iOS phone)

All notable changes to the iOS phone app (`com.playbridge.PlayBridge-Phone`).
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
