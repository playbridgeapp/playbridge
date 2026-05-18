import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// "Launch at login" that doesn't depend on any third-party plugin.
///
/// macOS  — writes a LaunchAgent plist into `~/Library/LaunchAgents/`
/// Linux  — writes an XDG autostart `.desktop` file into `~/.config/autostart/`
/// Windows — TODO (would need win32_registry to write HKCU\…\Run)
class AutoLaunch {
  AutoLaunch({required this.bundleId, required this.executablePath});

  final String bundleId;
  final String executablePath;

  Future<bool> isEnabled() async {
    final f = await _entryFile();
    return f != null && await f.exists();
  }

  Future<void> enable() async {
    final f = await _entryFile();
    if (f == null) return;
    await f.parent.create(recursive: true);
    await f.writeAsString(_renderEntry());
  }

  Future<void> disable() async {
    final f = await _entryFile();
    if (f != null && await f.exists()) {
      await f.delete();
    }
  }

  Future<File?> _entryFile() async {
    final home = Platform.environment['HOME'];
    if (home == null) return null;
    if (Platform.isMacOS) {
      return File('$home/Library/LaunchAgents/$bundleId.plist');
    }
    if (Platform.isLinux) {
      // XDG autostart spec: any .desktop file dropped here runs at login.
      return File('$home/.config/autostart/$bundleId.desktop');
    }
    if (Platform.isWindows) {
      debugPrint('[auto_launch] Windows is not implemented yet');
    }
    return null;
  }

  String _renderEntry() {
    if (Platform.isMacOS) {
      return '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$bundleId</string>
  <key>ProgramArguments</key>
  <array>
    <string>$executablePath</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
</dict>
</plist>
''';
    }
    if (Platform.isLinux) {
      return '''[Desktop Entry]
Type=Application
Name=PlayBridge
Exec="$executablePath"
X-GNOME-Autostart-enabled=true
''';
    }
    return '';
  }

  /// Best-effort discovery of the location to point the autostart entry at.
  /// During `flutter run` this returns the dev `Runner.app` executable, which
  /// is unstable across builds. For real "service" use, run a release build
  /// and put the app in `/Applications` first.
  static Future<String> resolveExecutablePath() async {
    if (Platform.isMacOS) {
      // Platform.resolvedExecutable is `<App>.app/Contents/MacOS/<binary>`
      // — that's exactly what LaunchAgent wants.
      return Platform.resolvedExecutable;
    }
    return Platform.resolvedExecutable;
  }

  /// On macOS, `app-sandbox = true` means LaunchAgent writes are *redirected*
  /// to the app's container at `~/Library/Containers/<bundle-id>/Data/...`
  /// rather than reaching the real `~/Library/LaunchAgents/`. The system
  /// won't load anything from the container, so launch-at-login silently
  /// fails. Detect that case so we can show a useful message.
  static Future<bool> isLikelySandboxed() async {
    if (!Platform.isMacOS) return false;
    final container = await getApplicationSupportDirectory();
    return container.path.contains('/Library/Containers/');
  }
}
