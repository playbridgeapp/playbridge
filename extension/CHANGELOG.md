# Changelog — PlayBridge Video Detector (browser extension)

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
