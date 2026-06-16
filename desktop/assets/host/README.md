# Bundled native-messaging host

`flutter build` bundles whatever is in this directory as an app asset. Put the
compiled `playbridge_host` binary here so the packaged app can extract and
register it with browsers (`lib/native_host_installer.dart`).

Build it (also done by `scripts/build_host.sh`):

```bash
cd desktop
dart compile exe bin/playbridge_host.dart -o assets/host/playbridge_host
# Windows: -o assets/host/playbridge_host.exe
```

The binary is platform + architecture specific, so compile it on (or for) each
target you ship. It is generated output — keep it out of version control.

Notes:
- In dev (`flutter run`) the app finds the host at `build/playbridge_host`, so the
  asset isn't required.
- The runtime-extracted binary lands in the app-support dir and is `chmod +x`'d.
  For a **notarized** macOS release, prefer placing the host inside the signed
  `.app` bundle (`Contents/MacOS/`) instead — `findHostBinary()` already checks
  there — so Gatekeeper doesn't block an extracted executable.
