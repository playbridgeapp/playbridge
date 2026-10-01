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

## Investigating memory growth

Enable **Settings → Diagnostics → Enable logging** before reproducing playback.
The `memory` log entries record process memory every 30 seconds and on playback
changes. On macOS, physical footprint and its lifetime peak include compressed
memory. Debug/profile builds also report the main Dart isolate's heap and external
memory when the VM service is available; release builds retain process counters.
Entries include decoder, dimensions, buffer counters/limits, image-cache counters,
and playback state without media URLs, headers, or titles. Samples above 2 GiB or
growing by 512 MiB between samples are marked as warnings.

Use **Diagnostics → View logs → Copy all** after the issue, including after an app
restart. Logs use the existing bounded rotation (5 MiB per file, two older files).
Turning logging off stops sampling and clears the saved logs, so copy them first.
