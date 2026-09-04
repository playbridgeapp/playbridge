# PlayBridge Feature Gap Matrix

Status: living engineering backlog

Audit date: 2026-09-03

This is the code-backed parity matrix for **Android phone vs iOS phone senders** and **all receivers**. It is not the product marketing tables in [`README.md`](../README.md) and not the protocol surface in [`protocol/docs/WSS_FLOW.md`](../protocol/docs/WSS_FLOW.md). When those disagree with this file, trust the code paths listed under Evidence.

Android phone rows describe the **FOSS** flavor unless noted. Play Store builds hide debrid, Nuvio scrapers, and APK self-update.

## Legend

| Mark | Meaning |
|---|---|
| Y | Shipped end-to-end |
| ~ | Partial: real code with a named gap |
| ○ | Stub: source or plist exists, not product-wired |
| — | Absent |
| n/a | Not applicable to that role |

## How to use this file

- **iOS porting:** close sender holes against Android FOSS.
- **Receiver parity:** close Apple TV / CLI against Android TV + Desktop.
- **Do not** duplicate rows into the README matrices unless the product claim should change.
- When a cell changes, update the mark, the note, and the evidence path in the same edit.

## Sender matrix — Android phone vs iOS phone

| Feature | Android | iOS | Notes |
|---|---|---|---|
| PlayBridge mDNS + manual IP | Y | Y | iOS is IPv4-only; no custom WSS-port UI (connects `ws://` on default 8765). |
| SAS pairing + token auth + SPKI TOFU | Y | Y | iOS never sends legacy `pairing_request`. |
| Saved devices + auto-reconnect | Y | Y | |
| DLNA / UPnP renderer targets | Y | — | Android: Rust SSDP + SOAP. |
| Google Cast targets | Y | ○ | iOS: `GoogleCastSession.swift` + App ID; XCFramework unlinked, no discovery UI. |
| Roku ECP targets | Y | — | |
| Phone-hosted browser receiver | Y | — | |
| GeckoView / WKWebView + HLS/DASH detect | Y | Y | Both drop `blob:` / `data:`; no pure MSE reconstruct. |
| Quality picker | Y | ~ | iOS DASH tiers are labels; cast still sends the full MPD URL. |
| Subtitle attach on cast | Y | Y | iOS has no OpenSubtitles / addon search. |
| Receiver track / speed / boost UI | Y | ~ | iOS parses `tracks` / `player_settings`, never shows or sends them. |
| Local files | Y | ~ | iOS serves one file at a time. |
| IPTV (M3U) | Y | Y | No EPG / Xtream on either phone in this audit. |
| Collections | ~ | ~ | Both cast one item; no collection-as-playlist. |
| Library / TMDB / Stremio addons | Y | — | iOS menu “Extensions” is Coming Soon. |
| Debrid | Y | — | Android Play flavor: hidden. |
| Downloads | Y | — | Android HLS remux is VOD-only; DASH download is a stub kind. |
| Ad blocking | Y | Y | Android: uBlock AMO. iOS: EasyList/EasyPrivacy WK rules + picker. Phone README still says “curated list only”. |
| Remote: transport + D-pad + touchpad | Y | Y | iOS seek bar is display-only; no mouse scroll. |
| Remote: volume | Y | — | Native Android volume is up/down keys, not absolute. |
| Remote: keyboard | Y | — | Android: native browser context. |
| Queue / jump / auto-advance | Y | ~ | iOS queue tab only when already in player context; no reorder/remove. |
| Play on sender | Y | ~ | Android: full ExoPlayer + PiP. iOS: CastSheet fullscreen AVPlayer. |
| Screen mirroring | Y | — | Android: WebRTC to PB receivers; MPEG-TS to Cast/DLNA. |
| Mixed media (video/audio/image) | Y | — | Android gates on receiver `mediaKinds`. |
| Stream proxy / via-phone routing | Y | — | iOS CHANGELOG claims Off/Auto; no UI or wire field. |
| In-app updates | Y | — | Android Play: opens store, no sideload. |
| Backup / import-export | Y | — | |

### Android-only sender targets (not iOS)

| Capability | Native PB | DLNA | Google Cast | Roku | Phone browser host |
|---|---|---|---|---|---|
| Load / play-pause / stop / now-playing | Y | Y | Y | Y | Y |
| Seek | Y | Y | Y | — | Y |
| Volume | ~ keys | ~ if RenderingControl | Y | Y | Y |
| D-pad / touchpad / keyboard | Y | — | — | keypress | — |
| Queue / jump | Y | phone EOS | phone EOS | phone EOS | phone EOS |
| Subtitles | Y | — | — | — | — |
| Browser / page cast | Y | — | — | — | n/a |
| Screen mirror | WebRTC | MPEG-TS | MPEG-TS/HLS | — | — |

## Receiver matrix

| Feature | Android TV | Apple TV | Desktop | DLNA | CLI |
|---|---|---|---|---|---|
| `_playbridge._tcp` + SAS/WSS/TLS | Y | Y | Y | — SSDP, no pairing | Y |
| Engines | exo, mpv | avplayer, vlc, mpv | internal_mpv | TV firmware | system mpv |
| HLS | Y | Y (VLC proxy) | Y | ~ phone rewrite | ~ mpv |
| DASH | Y | — | Y (proxy) | TV-dependent | mpv-dependent |
| Playlist + EOS auto-advance | Y | Y | Y | phone-side only | ~ queue, no EOF |
| D-pad | Y | ~ center/left/right | ○ ignored | — | — |
| Phone keyboard | ~ browser only | — | — | — | — |
| Mouse / touchpad | ~ browser + image | — | ~ image only | — | — |
| Volume remote | Y | — | Y | ~ SOAP | TUI only |
| Tracks / sidecar subs | Y | ~ no boost/offset | Y | — | — |
| Phone speed / boost / sub offset | Y | ignored | ignored | — | ignored |
| History / favorites / resume | Y | Y | Y | phone only | ~ startPositionMs |
| Web playback | Y | — | — | — | — |
| Mixed media | Y | — | Y | — | — |
| Screen mirror | WebRTC | — | WebRTC | MPEG-TS | — |
| Still watching | Y | Y | Y | — | — |
| In-app updates | Y FOSS / ○ Play | — | Y | n/a | CLI binary |
| Caps: `players` | exo, mpv | avplayer, vlc, mpv | internal_mpv | n/a | internal_mpv |
| Caps: `browsers` | webview [, gecko] | not sent | not sent | n/a | not sent |
| Caps: `mediaKinds` | video, audio, image | not sent | video, audio, image | n/a | not sent |
| Caps: `screenMirrorWebRtc` | true | not sent | true | n/a | false |

Desktop is also a sender (`tv_sender_controller.dart`); that role is out of scope here. The browser extension is not a WSS endpoint.

## Highest-leverage gaps

1. **iOS sender vs Android:** library/addons, debrid, downloads, DLNA + Google Cast + Roku, screen mirror, volume/keyboard/seek/tracks remote, in-app player, mixed media, stream routing.
2. **Apple TV vs Android TV:** no browser, no WebRTC mirror, no mixed media, no volume remote, no mouse, D-pad incomplete, auth caps advertise `players` only, phone player-settings prefixes ignored.
3. **Desktop vs Android TV:** no receiver browser (so phone page-cast must stay gated off); phone speed/boost/sub-offset ignored.
4. **CLI vs Desktop:** same Rust WSS runtime, but no EOF advance, no tracks/subs, no mixed-media or mirror caps, no still watching.
5. **Docs drift:** [`mobile/apple/README.md`](../mobile/apple/README.md) still says ad blocking is a curated list and that the app is two tabs; code has EasyList WK rules and a dashboard/router shell.

## Protocol vs UI splits

- Apple TV omission of `mediaKinds` / `browsers` / `screenMirrorWebRtc` is advertisement-by-omission; senders treat it as video-only, no browser, no mirror.
- DLNA never speaks PlayBridge JSON. Capabilities are a phone-side `CastTarget` set; auto-advance is `ExternalQueueCoordinator` while the phone stays alive.
- Phone-driven `speed:` / `audio_boost:` / `sub_offset:` are fully applied on **Android TV only**.

## Evidence

| Area | Paths |
|---|---|
| Android sender nav / cast / remote | `mobile/android/app/src/main/java/com/playbridge/sender/` (`browser/Screen.kt`, `connection/`, `cast/`, `ui/`) |
| Android discovery | `mobile/android/.../connection/RustReceiverDiscovery.kt` |
| iOS sender | `mobile/apple/PlayBridge Phone/PlayBridge Phone/` (`Network/`, `Browser/`, `UI/`) |
| iOS Cast stub | `.../Network/GoogleCastSession.swift`, `Info.plist` `_googlecast._tcp` |
| Android TV | `tv/android/player/app/src/main/java/com/playbridge/player/` |
| Apple TV | `tv/apple/PlayBridge TV/PlayBridge TV/` |
| Desktop receiver | `desktop/lib/receiver_server.dart`, `desktop/lib/discovery.dart` |
| DLNA | `mobile/android/.../cast/dlna/`, `connection/ExternalQueueCoordinator.kt` |
| CLI receiver | `cli/src/receive.rs` |
| Protocol surface | `protocol/docs/WSS_FLOW.md` |
| Mixed-media design | `docs/design-mixed-media-receivers.md` |
