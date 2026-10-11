# Changelog — PlayBridge Video Detector (browser extension)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.15.1] — 2026-10-11

### Fixed
- **Firefox for Android**: The add-on is no longer offered for Firefox for Android, which cannot run the desktop app's native messaging host.

## [0.15.0] — 2026-10-11

> **Works best together**: Device selection and the website playback API need PlayBridge Desktop 0.15.1 or later. With older desktop apps the extension keeps its read-only device list and websites see playback as unavailable.

### Added
- **Device selection**: Choose the playback device (a paired TV or This computer) from the popup or from a device chip beside the in-page cast button.
- **Website playback API**: Websites in desktop browsers can use `window.playbridge` in top-level frames (`getPlaybackDestination`, `choosePlaybackDestination`, `play`, `linkCast`, `cast`, and linked sessions with `replace`, `append`, `jump`, `provideItems`, `unlink`, and events `needitems`, `statechange`, `ended`).
- **Website access and consent**: An extension-owned window asks before a website can cast and lets you pick its device. Destination ids are private per website, choosing a device requires a user gesture, sessions unlink on navigation, and the popup lists website access you can revoke.
- **Open PlayBridge**: When the desktop app is not running, casting offers to launch it.
- **Richer media detection**: Discover media URLs in response bodies and track media changes on single-page sites without losing earlier playable streams.

### Changed
- **Reconnection**: Hovering the cast button, opening the popup or casting reconnects to the desktop app immediately instead of waiting for the reconnect backoff.
- **Cast results**: The cast button reports "Playing on this computer" or "Casting to <TV>". An offline TV shows an error with a one-off "Play on this computer" action instead of playing locally without asking.
- **Device filter chips**: Protocol filter chips (PlayBridge, Google Cast, DLNA, Roku, Web Browser) above the device list in the popup and in-page picker replace per-row protocol badges, remember your choice and hide offline devices.
- **Chrome store packages**: Remove the development-only manifest key from production uploads while retaining stable IDs for unpacked builds; refresh store listing guidance and screenshots.
- **Stream details**: Improve candidate ranking, captured request headers and media lifecycle tracking.

### Fixed
- **Low-latency HLS**: Cast separate audio/video renditions through synthetic master playlists and improve playlist attribute and whitespace parsing.
- **Detection ownership**: Associate media events with the committed tab and document so navigation does not mix detections from different pages.

### Security
- **Page integration**: Isolate the embedded browser's page API, bind linked casting to the owning document and keep sensitive receiver identities out of website responses.
- **Native bridge**: Keep credentials and protected stream details out of persisted diagnostics.

## [0.6.0] — 2026-07-15

### Added
- **Native Bridge Connection Delay**: Wait up to 3 seconds for the native messaging connection to establish when casting from the extension before giving up.

### Changed
- **Native Bridge Reconnection**: Implement exponential backoff reconnection for the extension native messaging host bridge.

## [0.5.0] — 2026-07-14

### Added
- **On-Video Cast Overlay**: Adds an intuitive overlay button on top of detected video players to allow casting directly from the page layout.
- **Improved Casting UX**: Enhances candidate sorting and discovery status feedback on the popup panel.

### Fixed
- **DASH Segment Candidate Filtering**: Excluded DASH initialization and chunk segments (`init-*.mp4` / `seg-*.mp4`) from ranking to prevent listing duplicate segment file fragments as playable candidates.

## [0.4.2] — 2026-07-08

### Fixed
- **Native Bridge Reconnection**: Throttled the native messaging port's reconnection routine to at most 3 failed retries upon disconnecting without establishing a valid session. Resets on successful message events.

## [0.4.1] — 2026-07-07

### Fixed
- **XSS in Popup**: Replaced unsafe `innerHTML` assignment with safe DOM methods (`createElement`/`textContent`) in `renderSavedConnections` to prevent Cross-Site Scripting when rendering user-supplied connection data (IP and PIN). (#89)

## [0.4.0] — 2026-06-21

### Added
- **Chrome Extra Headers Support**: Conditionally request `extraHeaders` on Chrome MV3 (and added `"webRequestExtraHeaders"` permission) in the background script to ensure custom headers (Origin, Referer, Cookie) are correctly captured. (#51)

### Fixed
- **Badge Update Errors**: Catch potential badge update rejection promises to avoid background script crashes. (#51)
- Fixed IPv6 connection wrapping/parsing issues in the background script. (#44)

## [0.3.0] — 2026-06-16

### Added
- **Toolbar badge** showing the number of detected streams on the active tab,
  themed to the popup accent.
- **Privacy policy** (`PRIVACY.md`) for Chrome Web Store / Firefox AMO submission.
- Detection of DASH manifests by URL (`.mpd`), alongside HLS (`m3u8`).

### Changed
- Routed casting through the PlayBridge desktop app via a native-messaging
  bridge instead of connecting to the TV directly, so Debrid tokens and auth
  headers never travel over plaintext `ws://`.
- Cross-browser build: a single source now emits `dist/firefox` (MV2) and
  `dist/chrome` (MV3) via a `webextension-polyfill` shim.
- **MV3 survival:** per-tab detections are persisted to `storage.session` and
  rehydrated when Chrome's service worker wakes, so the popup no longer loses
  detections after the worker is suspended. No-op on Firefox's persistent
  background page.

### Fixed
- **Headerless streams missed:** `m3u8`/`mpd` responses served without a
  `Content-Type` header are now detected (the URL-pattern and `#EXTM3U`
  body-sniff checks no longer bail when the header is absent).
- **Detection blocked on parse:** a stream is now reported the instant it's
  seen; HLS quality enrichment happens afterward with a timeout, so an origin
  that stalls the playlist fetch can't suppress the detection entirely.
- **Streams in iframes/workers not shown:** requests that surface with
  `tabId == -1` are now mapped back to the real tab (via a URL→tab correlation,
  falling back to the active tab) instead of being filed where the popup can't
  see them.

## [0.2.0]

Baseline release prior to the desktop-bridge rewrite (direct `ws://` connection
to the TV).
