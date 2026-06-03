# PlayBridge

<p align="center">
  <img src="docs/screenshots/cast-flow.png" alt="Find content on your phone, play it on your TV" width="720">
</p>

PlayBridge is an open-source Android application suite that bridges the gap between your phone and Android TV. Use PlayBridge to play what you want without the troubles of a clunky TV browser. Find your content on your phone, and seamlessly bridge it to your big screen.

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

- **Send to TV**: Push URLs and content from your phone to your TV.
- **TV Controls**: Use your phone as a remote control for the TV browser and player.
- **Browser**: A fully functional web browser on your phone that syncs with your TV.
- **Open Source**: Built with privacy and transparency in mind.

## Components

PlayBridge is a monorepo; each component has its own README:

1.  **[Phone App](mobile/)** (`mobile/`) — the sender: browses the web, detects videos, and controls the TV.
2.  **[TV App](tv/)** (`tv/`) — the receiver for Android TV (plus a tvOS variant): plays content and hosts a web browser.
3.  **[Desktop App](desktop/)** (`desktop/`) — a Flutter desktop receiver that plays casts via libmpv.
4.  **[Browser Extension](extension/)** (`extension/`) — a Firefox extension that casts media from desktop browser tabs.
5.  **[Shared Module](shared/)** (`shared/`) — Kotlin Multiplatform logic, player engines, and protocol bindings.
6.  **[Protocol](protocol/)** (`protocol/`) — protobuf wire-format definitions (git submodule).

## Documentation

Comprehensive project documentation is available:
- **[Design System](DESIGN.md)**: Visual language, color tokens, typography, and component specifications.
- **[Contributing](CONTRIBUTING.md)**: Setup instructions and contribution guidelines.
- **[Security Policy](SECURITY.md)**: Security considerations and vulnerability reporting.

## Build Instructions

### Prerequisites
- Android Studio Ladybug or later
- JDK 17+
- Android SDK 26+

### Building

To build the APKs:

```bash
# Build Phone App
cd mobile/android && ./gradlew :app:assembleDebug

# Build TV App
cd tv/android && ./gradlew :player:app:assembleDebug
```

## Contributing

Contributions are welcome! Please follow these steps:
1.  Fork the repository.
2.  Create a feature branch.
3.  Commit your changes.
4.  Open a Pull Request.

For detailed instructions, see [CONTRIBUTING.md](CONTRIBUTING.md).

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
Some components (GeckoView) are licensed under MPL 2.0, which is compatible with GPLv3.

## Contact

For questions, feedback, or support, please reach out to us at [playbridgeapp@gmail.com](mailto:playbridgeapp@gmail.com).

For security-related issues, please refer to our [Security Policy](SECURITY.md).
