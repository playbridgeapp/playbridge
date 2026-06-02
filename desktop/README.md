# PlayBridge — Desktop

A Flutter **desktop receiver**: it accepts cast commands from the phone and plays them via libmpv. Runs on macOS, Windows, and Linux.

## Build & run

```bash
cd desktop
flutter pub get
flutter run -d macos        # or: windows, linux
```

Release build:

```bash
flutter build macos         # or: windows, linux
```

## Requirements

- Flutter SDK (Dart `^3.6`)
- libmpv available on the host (used for playback)
