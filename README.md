# PlayBridge

<p align="center">
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="Status: Alpha">
  <img src="https://img.shields.io/badge/license-GPLv3-blue" alt="License: GPLv3">
  <a href="https://github.com/playbridgeapp/playbridge/releases"><img src="https://img.shields.io/github/v/release/playbridgeapp/playbridge?label=latest%20release" alt="Latest release"></a>
</p>

<p align="center">
  <img src="docs/screenshots/cast-flow.png" alt="Find content on your phone, play it on your TV" width="720">
</p>

PlayBridge is an open-source casting suite: browse on your phone, play on the big screen. Find your content on your phone — in the built-in browser or your media library — and bridge it to **Android TV / Fire TV**, **Apple TV**, a **desktop receiver** (macOS, Windows, Linux), or any **DLNA-capable TV** with nothing installed on it. Local-network only, no account, no cloud.

> [!WARNING]
> **PlayBridge is in alpha.** It's under active early development — expect bugs, incomplete features, and breaking changes between releases. Feedback and issue reports are very welcome.

## Screenshots

**Browse & cast from your phone**

| Console hub | Library & discovery | Built-in browser |
|:---:|:---:|:---:|
| <img src="docs/screenshots/phone-hub.jpg" alt="Console hub — connected to the TV" width="230"> | <img src="docs/screenshots/phone-library.jpg" alt="Library and discovery" width="230"> | <img src="docs/screenshots/phone-browser.jpg" alt="Built-in browser with cast" width="230"> |

**Your phone is the remote**

| Now playing | Touchpad · D-Pad · Keyboard |
|:---:|:---:|
| <img src="docs/screenshots/phone-now-playing.jpg" alt="Now playing — scrub and transport controls" width="230"> | <img src="docs/screenshots/phone-remote.jpg" alt="Touchpad, D-Pad and keyboard remote" width="230"> |

## Features

- **Browse & detect**: a full phone browser (GeckoView) that detects videos on the page — direct files, HLS, DASH, and MSE/blob players — and casts them with one tap.
- **Library & discovery**: a Stremio-style library with catalogs, add-ons, and a watchlist; pick an episode and send it straight to the TV.
- **Cast anywhere**: native receivers for Android TV / Fire TV, Apple TV, and desktop — or cast to any DLNA/UPnP renderer without installing anything on it.
- **Binge-ready**: episode queue with lazy auto-advance, watch-progress tracking, and resume ("Resume · 23:14") that actually seeks the TV to where you left off.
- **Phone as remote**: touchpad, D-pad, keyboard, and transport controls, context-aware for the browser and player.
- **Local files**: cast videos from your phone's storage to any receiver.
- **Dual player engines**: ExoPlayer and MPV on the TV, with automatic fallback when a stream misbehaves.
- **Private by design**: everything stays on your local network — no account, no cloud, GPLv3.

## Installation

- **Android TV / Fire TV (receiver)**
  - Open the **Downloader** app on your TV and enter code `9557748` to install the TV Player directly, or
  - download the latest `tv-player` APK from [Releases](https://github.com/playbridgeapp/playbridge/releases) and sideload it.
  - *Note:* on first launch the TV app asks for "Display over other apps" — required for the receiver to come to the foreground when a cast arrives.
  - Optional: the ad-blocked **TV Browser** APK (GeckoView + uBlock Origin) extends the player with web browsing.
- **Apple TV (receiver)**: no prebuilt binary yet — build and deploy from Xcode; see the [TV README](tv/).
- **Desktop (receiver)**: download the build for your OS from [Releases](https://github.com/playbridgeapp/playbridge/releases) (`playbridge-desktop-windows-*.zip`, `-linux-*.tar.gz`, `-macos-*.zip`). Linux needs `libmpv2`; the macOS build is unsigned (right-click → Open on first launch).
- **DLNA TVs**: nothing to install — the phone discovers renderers on your network automatically.
- **Android Phone (sender)**: download the latest `phone` APK from [Releases](https://github.com/playbridgeapp/playbridge/releases) and install it.

## How to connect & cast

1. Connect your phone and receiver to the **same Wi-Fi network**, and open the PlayBridge app on both.
2. On the phone, tap the **device chip** (top of the Library and browser cast screens) — it lists discovered receivers. Tap yours to connect.
   - *Not discovered?* Tap **"All devices & manual connect"** and enter the receiver's IP address (shown on its screen).
3. On first connect, the receiver asks **"Allow device to connect?"** — select **Allow** with your TV remote.
4. Browse any video site in the phone browser, play a video, and tap cast when PlayBridge detects the stream — or send a movie/episode directly from the Library.

## Components

PlayBridge is a monorepo; each component has its own README:

1.  **[Phone App](mobile/)** (`mobile/`) — the sender: browser, library, video detection, remote. [Changelog](mobile/android/CHANGELOG.md)
2.  **[TV Apps](tv/)** (`tv/`) — receivers for Android TV (player + browser APKs) and Apple TV. [Changelog](tv/android/CHANGELOG.md) · [tvOS changelog](tv/apple/CHANGELOG.md)
3.  **[Desktop App](desktop/)** (`desktop/`) — a Flutter desktop receiver that plays casts via libmpv. [Changelog](desktop/CHANGELOG.md)
4.  **[Browser Extension](extension/)** (`extension/`) — a Firefox extension that casts media from desktop browser tabs.
5.  **[Shared Module](shared/)** (`shared/`) — Kotlin Multiplatform logic, player engines, and protocol bindings.
6.  **[Protocol](protocol/)** (`protocol/`) — protobuf wire-format definitions (git submodule).

## Documentation

Comprehensive project documentation is available:
- **[Design System](DESIGN.md)**: Visual language, color tokens, typography, and component specifications.
- **[Contributing](CONTRIBUTING.md)**: Setup instructions and contribution guidelines.
- **[Security Policy](SECURITY.md)**: Security considerations and vulnerability reporting.

### Cast demo page

Sites can cast directly into PlayBridge via the injected `window.playbridge.cast()` page bridge.
A live demo that exercises all payload shapes (single video, HLS, playlist with `startIndex`,
bare array) plus the browser's video detection paths is hosted at
**[playbridge.app/cast-demo](https://playbridge.app/cast-demo/)** — open it in the PlayBridge
phone browser. Source: [`web/site/static/cast-demo/`](web/site/static/cast-demo/index.html).

## Build Instructions

> [!IMPORTANT]
> `protocol/` is a git submodule — clone with `git clone --recursive`, or run
> `git submodule update --init` in an existing checkout, or the builds will fail.

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

For security-related issues, please refer to our [Security Policy](SECURITY.md).
