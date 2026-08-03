# PlayBridge — iOS Phone App (Apple sender)

The iOS **sender** in the PlayBridge suite — the Apple counterpart to [`mobile/android`](../android/).
A native SwiftUI app (`com.playbridge.PlayBridge-Phone`) that discovers a PlayBridge receiver
(Android TV, tvOS, or desktop) on the LAN, pairs with it, and then casts links and acts as a
remote. It mirrors `tv/apple` (the tvOS receiver) in layout and conventions.

> **Scope:** the **bridge core** plus an **in-app browser**. The app has two tabs — **Browse**
> (web browser with automatic video detection → cast) and **Cast** (discover/pair/connect, remote,
> now-playing). Still **not** ported from Android: library/discovery, debrid, downloads, in-app
> phone player, ad-blocking.

## What it does

### Cast tab — the bridge

- **Discovery** — Bonjour (`_playbridge._tcp`) via `NetService`, plus a manual-IP fallback.
- **Pairing** — sends a `pairing_request`; on the TV's **Allow**, persists the token + TLS pin in
  the Keychain. Reconnects automatically on next launch with `auth`.
- **TLS pinning (TOFU)** — for `wss://` receivers, validates the server's SPKI pin
  (`sha256/<base64>`) on every connection; refuses a changed fingerprint (possible MITM).
- **Cast** — paste a video URL (a single video is sent as a one-item playlist, per the protocol).
- **Remote** — transport (`play`/`pause`/`stop`/seek ±10), D-pad + back, and a touchpad that
  sends the compact 9-byte binary mouse packet.
- **Now-playing sync** — renders the receiver's `status` / `context` / `playlist_status` /
  `tracks` / `player_settings` messages; tap a playlist item to jump.

### Browse tab — web browser + video detection

- **Multi-tab WKWebView** with address/search bar, back/forward, reload, progress, and a tab
  switcher with live snapshots.
- **Automatic stream detection** — injected user scripts (the iOS replacement for the Android
  GeckoView WebExtension) hook `fetch`/`XMLHttpRequest` and observe the DOM to find HLS/DASH/MP4
  streams and subtitle tracks; a badge shows the count on the current page.
- **Rich preview → cast** — `AVPlayer` preview, poster thumbnail (`AVAssetImageGenerator`),
  HLS/DASH **quality selection**, and **subtitle attach**, then cast to the TV with the page's
  `Referer`/User-Agent headers.
- **Ad blocking** — a `WKContentRuleList` (Safari content-blocker, the WKWebView-native stand-in for
  uBlock) blocks a curated set of ad/tracker/analytics hosts and hides common ad containers. Toggle
  with the shield button in the toolbar (on by default); see `Browser/ContentBlocker.swift`.

> **Browser limitations (vs the GeckoView Android browser):** ad blocking is a curated content-rule
> list, not full EasyList/uBlock (no per-site cosmetic filtering, no WebExtensions); "hidden" m3u8
> served with a generic content-type and no media extension may be missed; `AVPlayer`
> preview/thumbnail only work for AVFoundation-supported formats (HLS, mp4/m4v/mov) — mkv/avi/flv
> show "preview unavailable" but can still be cast; header capture is best-effort (URL +
> `Referer`/UA) since WKWebView doesn't expose full request headers/cookies to JS.

## Tech

- **SwiftUI** + **WebKit** + **AVKit/AVFoundation**, **iOS 16+**, no third-party dependencies.
- Wire format is plain JSON over a WebSocket (`URLSessionWebSocketTask`). Message keys match the
  proto `json_name` annotations in [`protocol/proto/messages.proto`](../../protocol/), so the
  receiver decodes them identically to the Android/tvOS clients. Pinning uses `CryptoKit` +
  `Security` (no `swift-protobuf`/`swift-crypto` needed for this scope).
- Project layout mirrors `tv/apple`: `Network/`, `Browser/`, `Data/`, `Models/`, `UI/`.

## Build & run

```bash
cd "mobile/apple/PlayBridge Phone"

# Compile for the simulator
xcodebuild -project "PlayBridge Phone.xcodeproj" -scheme "PlayBridge Phone" \
  -destination 'platform=iOS Simulator,name=iPhone 16' build

# Or just open it
open "PlayBridge Phone.xcodeproj"
```

### Google Cast Core groundwork

The Apple sender has an ABI-v2 adapter in
`PlayBridge Phone/Network/GoogleCastSession.swift`. Build the optional native
XCFramework from the repository root:

```bash
./cast/build-apple.sh
```

Then add `mobile/apple/Native/PlayBridgeCastCore.xcframework` to the phone
target as **Do Not Embed**. The adapter remains a safe unavailable stub when
the framework is not linked, so ordinary source builds continue to work.

Set `PlayBridgeGoogleCastApplicationID` in the target build settings to the
published PlayBridge Custom Web Receiver application ID, `30FDC6BC`.
`_googlecast._tcp` is already declared for Local Network permission; discovery
and UI adoption are the remaining Apple product work.

On a device, Bonjour requires the **Local Network** permission (granted on first scan). The
network keys live in `PlayBridge Phone/Info.plist`: `NSBonjourServices`,
`NSLocalNetworkUsageDescription`, and `NSAppTransportSecurity` (`NSAllowsLocalNetworking` for LAN
`ws://`, `NSAllowsArbitraryLoadsInWebContent` so the browser can load arbitrary http(s) sites).
Standard app keys are still generated (`GENERATE_INFOPLIST_FILE`).

### Testing the bridge

1. Start a receiver on the same Wi-Fi (the `tv/apple` tvOS app or an Android TV receiver).
2. On the **Cast** tab the receiver appears under **Discovered** (or use **Manual connect**).
3. Tap it → **Allow** on the TV → it connects and persists the pairing.
4. Paste a test stream URL and **Cast**; use the remote to drive playback.
5. On the **Browse** tab, open a page with a video (e.g. an HLS demo) → the stream badge appears →
   tap it → preview/pick quality → **Cast to TV**.

## Protocol

This app is a consumer of the shared PlayBridge protocol. The source of truth is
[`protocol/`](../../protocol/) (`messages.proto` + `constants.go`). Outbound commands use the
`{"type":"command","action":…,"payload":…}` envelope; pairing/auth/ping are standalone messages.
See [`protocol/README.md`](../../protocol/README.md) for the TLS-pinning (`cert_fingerprint`)
details.
