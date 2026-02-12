# PlayBridge

PlayBridge is an open-source Android application suite that bridges the gap between your phone and Android TV. Use PlayBridge to play what you want without the troubles of a clunky TV browser. Find your content on your phone, and seamlessly bridge it to your big screen.

## Features

- **Send to TV**: Push URLs and content from your phone to your TV.
- **TV Controls**: Use your phone as a remote control for the TV browser and player.
- **Browser**: A fully functional web browser on your phone that syncs with your TV.
- **Open Source**: Built with privacy and transparency in mind.

## Components

The project consists of two applications:
1.  **Phone App (`phone`)**: The sender application. Browses web, detects videos, and controls the TV.
2.  **TV App (`tv`)**: The receiver application. Runs on Android TV, plays content, and displays a web browser.

## Build Instructions

### Prerequisites
- Android Studio Ladybug or later
- JDK 17+
- Android SDK 26+

### Building

To build the APKs:

```bash
# Build Phone App
./gradlew :phone:app:assembleDebug

# Build TV App
./gradlew :tv:app:assembleDebug
```

## Contributing

Contributions are welcome! Please follow these steps:
1.  Fork the repository.
2.  Create a feature branch.
3.  Commit your changes.
4.  Open a Pull Request.

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.
Some components (GeckoView) are licensed under MPL 2.0, which is compatible with GPLv3.
