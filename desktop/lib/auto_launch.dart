import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// "Launch at login" that doesn't depend on any third-party plugin.
///
/// macOS  — writes a LaunchAgent plist into `~/Library/LaunchAgents/`
/// Linux  — writes an XDG autostart `.desktop` file into `~/.config/autostart/`
/// Windows — TODO (would need win32_registry to write HKCU\…\Run)
class AutoLaunch {
  AutoLaunch({
    required this.executablePath,
    this.bundleId = appId,
    this.legacyIds = legacyAppIds,
    String? home,
  }) : _home = home;

  /// The macOS bundle id, also the LaunchAgent label / autostart file name.
  static const appId = 'app.playbridge.desktop';

  /// Entry names written by older builds: the previous bundle id (tray menu)
  /// and `com.playbridge.desktop` (Settings). Replaced by [appId] on enable.
  static const legacyAppIds = [
    'com.playbridge.playbridgeDesktop',
    'com.playbridge.desktop',
  ];

  final String bundleId;
  final List<String> legacyIds;
  final String executablePath;
  final String? _home;

  Future<bool> isEnabled() async {
    for (final f in _entryFiles([bundleId, ...legacyIds])) {
      if (await f.exists()) return true;
    }
    return false;
  }

  Future<void> enable() async {
    final f = _entryFile(bundleId);
    if (f == null) return;
    await f.parent.create(recursive: true);
    await f.writeAsString(_renderEntry());
    await _deleteAll(legacyIds);
  }

  Future<void> disable() => _deleteAll([bundleId, ...legacyIds]);

  /// Rewrites a legacy entry under [bundleId] (pointing at this executable), so
  /// launch-at-login survives the bundle id change without starting twice.
  Future<void> migrateLegacy() async {
    var legacy = false;
    for (final f in _entryFiles(legacyIds)) {
      if (await f.exists()) legacy = true;
    }
    if (legacy) await enable();
  }

  Future<void> _deleteAll(List<String> ids) async {
    for (final f in _entryFiles(ids)) {
      if (await f.exists()) await f.delete();
    }
  }

  Iterable<File> _entryFiles(List<String> ids) =>
      ids.map(_entryFile).whereType<File>();

  File? _entryFile(String id) {
    final home = _home ?? Platform.environment['HOME'];
    if (home == null) return null;
    if (Platform.isMacOS) {
      return File('$home/Library/LaunchAgents/$id.plist');
    }
    if (Platform.isLinux) {
      // XDG autostart spec: any .desktop file dropped here runs at login.
      return File('$home/.config/autostart/$id.desktop');
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
