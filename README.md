# PlayBridge

PlayBridge is an open-source Android application suite that bridges the gap between your phone and Android TV. Use PlayBridge to play what you want without the troubles of a clunky TV browser. Find your content on your phone, and seamlessly bridge it to your big screen.

## Features

- **Send to TV**: Push URLs and content from your phone to your TV.
- **TV Controls**: Use your phone as a remote control for the TV browser and player.
- **Browser**: A fully functional web browser on your phone that syncs with your TV.
- **Open Source**: Built with privacy and transparency in mind.

## Components

The project is organized into several components:
1.  **Phone App (`mobile/`)** — the sender: browses the web, detects videos, and controls the TV.
2.  **TV App (`tv/`)** — the receiver for Android TV (with a tvOS variant): plays content and hosts a web browser.
3.  **Desktop App (`desktop/`)** — a Flutter desktop receiver that plays casts via libmpv.
4.  **Browser Extension (`extension/`)** — a Firefox extension that casts media from desktop browser tabs.
5.  **Shared Module (`shared/`)** — Kotlin Multiplatform logic, player engines, and protocol bindings.
6.  **Protocol (`protocol/`)** — protobuf wire-format definitions (git submodule).

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

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.
Some components (GeckoView) are licensed under MPL 2.0, which is compatible with GPLv3.

## Contact

For questions, feedback, or support, please reach out to us at [playbridgeapp@gmail.com](mailto:playbridgeapp@gmail.com).

For security-related issues, please refer to our [Security Policy](SECURITY.md).
