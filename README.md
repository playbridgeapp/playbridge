# PlayBridge

<p align="center">
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="Status: Alpha">
  <img src="https://img.shields.io/badge/license-GPLv3-blue" alt="License: GPLv3">
  <a href="https://github.com/playbridgeapp/playbridge/releases"><img src="https://img.shields.io/github/v/release/playbridgeapp/playbridge?label=latest%20release" alt="Latest release"></a>
  <a href="https://ko-fi.com/playbridgeapp"><img src="https://img.shields.io/badge/Ko--fi-Support%20us-F16061?logo=ko-fi&logoColor=white" alt="Support us on Ko-fi"></a>
</p>

<p align="center">
  <img src="docs/screenshots/cast-flow.png" alt="Find content on your phone, play it on your TV" width="720">
</p>

PlayBridge is an open-source casting suite: browse on your phone, play on the big screen. Find your content on your phone — in the built-in browser or your media library — and bridge it to **Android TV / Fire TV**, **Apple TV**, a **desktop receiver** (macOS, Windows, Linux), or any **DLNA-capable TV** with nothing installed on it. Local-network only, no account, no cloud.

> [!WARNING]
> **PlayBridge is in alpha.** It's under active early development — expect bugs, incomplete features, and breaking changes between releases. Feedback and issue reports are very welcome.

## Table of Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Sender feature matrix](#sender-feature-matrix)
- [Receiver feature matrix](#receiver-feature-matrix)
- [Installation](#installation)
  - [Google Play](#google-play)
- [How to connect & cast](#how-to-connect--cast)
- [Components](#components)
- [Documentation](#documentation)
- [Build Instructions](#build-instructions)
- [Contributing](#contributing)
- [AI Policy](#ai-policy)
- [Acknowledgments](#acknowledgments)
- [License](#license)
- [Contact](#contact)

## Screenshots

**Browse & cast from your phone**

| Console hub | Library & discovery | Library detail screen | Built-in browser |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/phone-hub.jpg" alt="Console hub — connected to the TV" width="170"> | <img src="docs/screenshots/phone-library.jpg" alt="Library and discovery" width="170"> | <img src="docs/screenshots/phone-library-details.jpg" alt="Library detail screen" width="170"> | <img src="docs/screenshots/phone-browser.jpg" alt="Built-in browser with cast" width="170"> |

**Your phone is the remote**

| Context-aware remote | Touchpad remote | D-Pad remote | Keyboard remote |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/phone-remote-context.jpg" alt="Context-aware remote" width="170"> | <img src="docs/screenshots/phone-remote-touchpad.jpg" alt="Touchpad mode" width="170"> | <img src="docs/screenshots/phone-remote-dpad.jpg" alt="D-Pad mode" width="170"> | <img src="docs/screenshots/phone-remote-keyboard.jpg" alt="Keyboard input mode" width="170"> |

**Android TV receiver**

| Pairing screen | Player & details | Settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/tv-pairing.png" alt="Android TV pairing screen" width="230"> | <img src="docs/screenshots/tv-preplay.jpg" alt="Android TV player and details" width="230"> | <img src="docs/screenshots/tv-settings.png" alt="Android TV settings screen" width="230"> |

| Favorites | History |
|:---:|:---:|
| <img src="docs/screenshots/tv-favourite.png" alt="Android TV favorites" width="230"> | <img src="docs/screenshots/tv-history.png" alt="Android TV history" width="230"> |

**Apple TV receiver**

| Pairing screen | Player & details | Settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/apple-tv-pairing.jpg" alt="Apple TV pairing screen" width="230"> | <img src="docs/screenshots/apple-tv-preplay.jpg" alt="Apple TV player and details" width="230"> | <img src="docs/screenshots/apple-tv-settings.jpg" alt="Apple TV settings screen" width="230"> |

| Favorites | History |
|:---:|:---:|
| <img src="docs/screenshots/apple-tv-favourite.jpg" alt="Apple TV favorites" width="230"> | <img src="docs/screenshots/apple-tv-history.jpg" alt="Apple TV history" width="230"> |

**Desktop receiver**

| Pairing screen | Player & details | Settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/desktop-pairing.jpg" alt="Desktop pairing screen" width="230"> | <img src="docs/screenshots/desktop-preplay.jpg" alt="Desktop player and details" width="230"> | <img src="docs/screenshots/desktop-settings.jpg" alt="Desktop settings screen" width="230"> |

| Favorites | History |
|:---:|:---:|
| <img src="docs/screenshots/desktop-favourite.jpg" alt="Desktop favorites" width="230"> | <img src="docs/screenshots/desktop-history.jpg" alt="Desktop history" width="230"> |

**Browser extension**

| Connected | Streams detected | Subtitles detected | Open URL options |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/ext-connected.jpg" alt="Extension connected" width="170"> | <img src="docs/screenshots/ext-video-streams.jpg" alt="Video streams detected" width="170"> | <img src="docs/screenshots/ext-subtitles.jpg" alt="Subtitles detected" width="170"> | <img src="docs/screenshots/ext-open-url.jpg" alt="Open URL options" width="170"> |

## Features

- **Browse & detect**: a full phone browser (GeckoView, with built-in ad-blocking) that detects videos on the page — direct files, HLS, DASH, and MSE/blob players — and casts them with one tap.
- **Library & discovery**: a Stremio-style library with catalogs, add-ons (including sandboxed JavaScript scrapers), a watchlist, and Collections; pick an episode and send it straight to the TV.
- **Cast anywhere**: native receivers for Android TV / Fire TV, Apple TV, and desktop — or cast to any DLNA/UPnP renderer without installing anything on it.
- **Binge-ready**: episode queue with lazy auto-advance, watch-progress tracking, and resume ("Resume · 23:14") that actually seeks the TV to where you left off.
- **Phone as remote**: touchpad, D-pad, keyboard, and transport controls, context-aware for the browser and player.
- **Local files & IPTV**: cast videos from your phone's storage to any receiver, and load M3U/IPTV playlists.
- **Downloads**: save streams to your phone with a WorkManager-backed queue, on-the-fly HLS→MP4 transmuxing, and publishing to your gallery.
- **Dual player engines**: ExoPlayer and MPV on the TV, with automatic fallback when a stream misbehaves.
- **Secure pairing**: connections are verified with a short 6-digit code and encrypted over `wss://` with certificate pinning.
- **Private by design**: everything stays on your local network — no account, no cloud, GPLv3.

## Sender feature matrix

| Feature | Android phone | iPhone / iPad | Desktop app | Browser extension |
|---|---|---|---|---|
| Supported platforms | Android | iOS, iPadOS | macOS, Windows, Linux | Firefox and Chromium browsers |
| Receiver targets | PlayBridge receivers and DLNA / UPnP renderers | PlayBridge receivers | PlayBridge receivers | Receiver selected in Desktop, or Desktop itself |
| Secure receiver pairing | ✓ | ✓ | ✓ | Handled by Desktop |
| Built-in browser and stream detection | GeckoView; direct files, HLS, DASH, and MSE / blob players | WKWebView; direct files, HLS, and DASH | Via the browser extension | Detects direct files, HLS, DASH, and MSE-backed players in browser tabs |
| Stream quality selection | ✓ | ✓ | Via the browser extension | HLS quality selection |
| Subtitle detection and attachment | ✓ | ✓ | — | Detection only; attachment is not relayed to Desktop yet |
| Local file casting | ✓ | ✓ | ✓, including drag-and-drop and multi-file playlists | — |
| Library, discovery, and add-ons | ✓ | — | — | — |
| Debrid integrations | ✓ in supported builds | — | — | — |
| IPTV and custom collections | ✓ | ✓ | — | — |
| Downloads | ✓ | — | — | — |
| Ad blocking | uBlock Origin | WebKit content blocker with EasyList and custom rules | — | — |
| Queue support | ✓, including episode auto-advance | ✓ | Multi-file playlists | Single detected item |
| Remote controls | Full remote: transport, seek, volume, D-pad, touchpad, keyboard, and track selection | Transport, seek, D-pad, touchpad, and track selection | Transport, seek, and playlist navigation | Use Desktop or a phone remote |
| Play on the sender device | ✓ | Browser preview only | ✓ | Plays through Desktop when no receiver is selected |
| Companion app required | No | No | No | PlayBridge Desktop |

Browser detection depends on what each browser exposes. Protected streams may require captured request headers, and some MSE / blob players cannot be replayed outside their original page.

## Receiver feature matrix

| Feature | Android TV / Fire TV | Apple TV | Desktop | DLNA / UPnP TV |
|---|---|---|---|---|
| Supported platforms | Android TV, Fire TV | tvOS | macOS, Windows, Linux | Compatible smart TVs and renderers |
| Receiver installation | PlayBridge TV app | PlayBridge TV app (build from source) | PlayBridge Desktop app | None |
| Playback engines | Media3 / ExoPlayer + MPV | AVPlayer + VLC + MPV | MPV | TV's built-in player |
| Direct streams, HLS, DASH, and local files | ✓ | ✓ | ✓ | Renderer-dependent; the phone proxies local files and header-protected HLS |
| Verified, encrypted pairing | ✓ | ✓ | ✓ | N/A — standard local-network DLNA |
| Phone playback controls | Full controls, including seek, volume, and track selection | Transport, seek, and track selection | Full controls, including seek, system volume, and track selection | Play, pause, stop, and seek; no volume or track selection |
| Episode queue and auto-advance | ✓ | ✓ | ✓ | Phone-managed; the phone must remain active |
| Resume and watch-progress tracking | ✓ | ✓ | ✓ | ✓, when the renderer reports usable playback status |
| External subtitles and audio/subtitle selection | ✓ | ✓ | ✓ | Not exposed through PlayBridge |
| Receiver history and favorites | ✓ | ✓ | ✓ | No receiver UI; progress remains on the phone |
| Web playback on the receiver | Optional GeckoView plugin or system WebView | — | Browser-extension bridge | — |
| In-app updates | ✓ | — | ✓ | N/A |

Media and codec support ultimately depends on the selected playback engine, operating system, and device hardware. DLNA behavior varies the most between TV manufacturers and models.

## Installation

- **Android TV / Fire TV (receiver)**
  - Open the **Downloader** app on your TV and enter code `9557748` to install the TV Player directly, or
  - download the latest `tv-player` APK from [Releases](https://github.com/playbridgeapp/playbridge/releases) and sideload it.
  - *Note:* on first launch the TV app asks for "Display over other apps" — required for the receiver to come to the foreground when a cast arrives.
  - Optional: the ad-blocked **TV Browser** APK (GeckoView + uBlock Origin) extends the player with web browsing.
- **Apple TV (receiver)**: no prebuilt binary yet — build and deploy from Xcode; see the [TV README](tv/).
- **Desktop (receiver)**: download the build for your OS from [Releases](https://github.com/playbridgeapp/playbridge/releases) (`playbridge-desktop-windows-*.zip`, `-linux-*.tar.gz`, `-macos-*.zip`). Linux needs `libmpv2`; the macOS build is unsigned (right-click → Open on first launch).
- **DLNA TVs**: nothing to install — the phone discovers renderers on your network automatically.
- **Android Phone (sender)**:
  - <a href="https://play.google.com/store/apps/details?id=com.playbridge.sender"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get PlayBridge on Google Play" height="80"></a>
  - or download the latest `phone` APK from [GitHub Releases](https://github.com/playbridgeapp/playbridge/releases) and install it.
- **Staying up to date**: the phone and TV apps can check for new releases and install updates from within the app, so sideloaded builds don't go stale.

### Google Play

The Android phone sender is publicly available on Google Play:

* **Android Phone (Sender)**:
  * <a href="https://play.google.com/store/apps/details?id=com.playbridge.sender"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get PlayBridge on Google Play" height="80"></a>
  * Or download the latest `phone` APK from [GitHub Releases](https://github.com/playbridgeapp/playbridge/releases).
* **Android TV (Player) — closed testing**
  * **Group invite**: [pbtvclosedtesters Google Group](https://groups.google.com/g/pbtvclosedtesters)
  * **Apply to be a tester**: [Google Play Opt-in](https://play.google.com/apps/testing/com.playbridge.player)
  * **Download app**: [Google Play Store](https://play.google.com/store/apps/details?id=com.playbridge.player)

## How to connect & cast

1. Connect your phone and receiver to the **same Wi-Fi network**, and open the PlayBridge app on both.
2. On the phone, tap the **device chip** (top of the Library and browser cast screens) — it lists discovered receivers. Tap yours to connect.
   - *Not discovered?* Tap **"All devices & manual connect"** and enter the receiver's IP address (shown on its screen).
3. On first connect, the receiver displays a **6-digit pairing code**. Enter it on the phone to verify and secure the connection — devices you've already paired reconnect automatically.
4. Browse any video site in the phone browser, play a video, and tap cast when PlayBridge detects the stream — or send a movie/episode directly from the Library.

## Components

PlayBridge is a monorepo; each component has its own README:

1.  **[Phone App](mobile/)** (`mobile/`) — the sender: browser, library, video detection, remote. [Changelog](mobile/android/CHANGELOG.md)
2.  **[TV Apps](tv/)** (`tv/`) — receivers for Android TV (player + browser APKs) and Apple TV. [Changelog](tv/android/CHANGELOG.md) · [tvOS changelog](tv/apple/CHANGELOG.md)
3.  **[Desktop App](desktop/)** (`desktop/`) — a Flutter desktop receiver that plays casts via libmpv. [Changelog](desktop/CHANGELOG.md)
4.  **[Browser Extension](extension/)** (`extension/`) — a Firefox extension that casts media from desktop browser tabs.
5.  **[Shared Module](shared/)** (`shared/`) — Kotlin Multiplatform logic, player engines, and protocol bindings.
6.  **[Protocol](protocol/)** (`protocol/`) — AsyncAPI WSS contract, detailed connection flow, and retained protobuf bindings.

## Documentation

Comprehensive project documentation is available:
- **[Design System](DESIGN.md)**: Visual language, color tokens, typography, and component specifications.
- **[Contributing](CONTRIBUTING.md)**: Setup instructions and contribution guidelines.
- **[Security Policy](SECURITY.md)**: Security considerations and vulnerability reporting.
- **[WSS Protocol Flow](protocol/docs/WSS_FLOW.md)**: Discovery, TLS pinning, SAS pairing, authentication, commands, status events, and compatibility rules. See also the machine-readable [AsyncAPI contract](protocol/asyncapi.yaml).

### Cast demo page

Sites can cast directly into PlayBridge via the injected `window.playbridge.cast()` page bridge.
A live demo that exercises all payload shapes (single video, HLS, playlist with `startIndex`,
bare array) plus the browser's video detection paths is hosted at
**[playbridge.app/cast-demo](https://playbridge.app/cast-demo/)** — open it in the PlayBridge
phone browser. Source: [`web/site/static/cast-demo/`](web/site/static/cast-demo/index.html).

## Build Instructions

### Prerequisites

- **Android apps**: Android Studio Ladybug or later, JDK 17+, Android SDK 26+
- **Desktop**: Flutter SDK (Dart `^3.6`) and libmpv
- **Apple TV**: Xcode with CocoaPods (see [tv/](tv/))

### Building

```bash
# Phone app
cd mobile/android && ./gradlew :app:assembleDebug

# TV player
cd tv/android && ./gradlew :player:app:assembleDebug

# TV browser
cd tv/android && ./gradlew :browser:app:assembleDebug

# Desktop
cd desktop && flutter pub get && flutter run    # -d macos | windows | linux
```

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for setup and guidelines.

## AI Policy

This project is built with the help of AI-assisted tools. We welcome similar contributions, provided you take full ownership of the code you submit. Every pull request—regardless of the tools used—is subject to the same standard of review and testing.

- **Responsibility**: You must fully understand, test, and be able to explain all parts of your changes.
- **Reviewability**: Keep PRs focused and readable. Avoid large, undocumented dumps of generated code.

## Acknowledgments

PlayBridge was inspired by — and learned from — these excellent open-source projects. Huge thanks to their authors and communities:

- **[Stremio](https://github.com/Stremio/stremio-web)**
- **[Swiftfin](https://github.com/jellyfin/Swiftfin)** and **[Streamyfin](https://github.com/streamyfin/streamyfin)**
- **[NuvioTV](https://github.com/NuvioMedia/NuvioTV)**
- **[AIOStreams](https://github.com/Viren070/AIOStreams)**
- **[VLC for iOS/tvOS](https://github.com/videolan/vlc-ios)**
- **[mpvEx](https://github.com/marlboro-advance/mpvEx)**, **[mpvNova](https://github.com/Laskco/mpvNova)**, and **[Player](https://github.com/moneytoo/Player)**

All trademarks and copyrights belong to their respective owners.

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.

It also bundles third-party software under their own (GPLv3-compatible) licenses — including GeckoView (MPL-2.0), FFmpeg and mpv/libmpv (LGPL/GPL), and uBlock Origin (GPL-3.0). See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the full list.

## Contact

For questions, feedback, or support, please reach out to us at [playbridgeapp@gmail.com](mailto:playbridgeapp@gmail.com).

If you'd like to support development, you can [buy us a coffee on Ko-fi (kofi)](https://ko-fi.com/playbridgeapp).

For security-related issues, please refer to our [Security Policy](SECURITY.md).
