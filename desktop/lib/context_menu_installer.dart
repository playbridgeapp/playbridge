import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';

/// Registers an OS file-manager "Play on TV" entry that shells the
/// `playbridge_cast` helper with the selected file. Mirrors
/// [NativeHostInstaller]: best-effort + idempotent on launch, and re-runnable
/// from a button.
///
/// - **Windows:** HKCU `SystemFileAssociations\{video,audio}\shell\PlayBridge`
///   (no admin needed).
/// - **macOS:** a Quick Action (`~/Library/Services/Play on TV.workflow`) that
///   appears under Finder → right-click → Quick Actions for media files.
/// - **Linux:** a Nautilus script (`~/.local/share/nautilus/scripts/Play on TV`).
///   Nemo/Thunar/Dolphin use different mechanisms — documented, not auto-installed.
class ContextMenuInstaller {
  static const _menuLabel = 'Play on TV';
  static const _castExe = 'playbridge_cast';

  /// Locate the compiled `playbridge_cast` binary: env override, beside the
  /// running executable (packaged), the macOS `.app/Contents/Resources`, or
  /// `build/` (dev).
  static String? findCastBinary() {
    final exe = Platform.isWindows ? '$_castExe.exe' : _castExe;
    final exeFile = File(Platform.resolvedExecutable);
    final exeDir = exeFile.parent.path;
    final cwd = Directory.current.path;
    final candidates = <String>[
      if (Platform.environment['PLAYBRIDGE_CAST_BIN'] != null)
        Platform.environment['PLAYBRIDGE_CAST_BIN']!,
      '$exeDir/$exe',
      '${exeFile.parent.parent.path}/Resources/$exe',
      '$cwd/build/$exe',
      '$cwd/desktop/build/$exe',
    ];
    for (final c in candidates) {
      if (File(c).existsSync()) return File(c).absolute.path;
    }
    return null;
  }

  /// Returns a usable cast-binary path, extracting the bundled asset
  /// (`assets/host/playbridge_cast`) into app-support if not found on disk.
  static Future<String?> ensureCastBinary() async {
    final onDisk = findCastBinary();
    if (onDisk != null) return onDisk;
    try {
      final exe = Platform.isWindows ? '$_castExe.exe' : _castExe;
      final data = await rootBundle.load('assets/host/$exe');
      final dir =
          Directory('${(await getApplicationSupportDirectory()).path}/host');
      if (!dir.existsSync()) dir.createSync(recursive: true);
      final out = File('${dir.path}/$exe');
      await out.writeAsBytes(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
      );
      if (!Platform.isWindows) {
        await Process.run('chmod', ['+x', out.path]);
        if (Platform.isMacOS) {
          await Process.run('xattr', ['-c', out.path]);
          await Process.run('codesign', ['-s', '-', '--force', out.path]);
        }
      }
      return out.absolute.path;
    } catch (_) {
      return null;
    }
  }

  static Future<Map<String, String>> install() async {
    final castBin = await ensureCastBinary();
    if (castBin == null) {
      return {
        'cast helper': 'not found — build it: cd desktop && '
            'dart compile exe bin/playbridge_cast.dart -o build/playbridge_cast',
      };
    }
    if (Platform.isWindows) return _installWindows(castBin);
    if (Platform.isMacOS) return _installMacOS(castBin);
    if (Platform.isLinux) return _installLinux(castBin);
    return {'unsupported': Platform.operatingSystem};
  }

  static Future<void> installSilently() async {
    try {
      final r = await install();
      debugPrint('[ctx-menu] $r');
    } catch (e) {
      debugPrint('[ctx-menu] failed: $e');
    }
  }

  // ─── Windows ────────────────────────────────────────────────────────────────
  // SystemFileAssociations\<perceived-type> applies to every file Windows tags
  // with that PerceivedType, so one entry covers all video (and one all audio)
  // without enumerating extensions. The command's "%1" is the clicked file.
  static Future<Map<String, String>> _installWindows(String castBin) async {
    final result = <String, String>{};
    for (final type in ['video', 'audio']) {
      final base =
          'HKCU\\Software\\Classes\\SystemFileAssociations\\$type\\shell\\PlayBridge';
      try {
        final label = await Process.run('reg',
            ['add', base, '/ve', '/t', 'REG_SZ', '/d', _menuLabel, '/f']);
        // Icon: reuse the app's icon from the running exe (index 0).
        await Process.run('reg', [
          'add',
          base,
          '/v',
          'Icon',
          '/t',
          'REG_SZ',
          '/d',
          Platform.resolvedExecutable,
          '/f',
        ]);
        final cmd = await Process.run('reg', [
          'add',
          '$base\\command',
          '/ve',
          '/t',
          'REG_SZ',
          '/d',
          '"$castBin" "%1"',
          '/f',
        ]);
        result[type] = (label.exitCode == 0 && cmd.exitCode == 0)
            ? 'ready'
            : 'error: ${(cmd.stderr as String).trim()}';
      } catch (e) {
        result[type] = 'error: $e';
      }
    }
    return result;
  }

  // ─── macOS ──────────────────────────────────────────────────────────────────
  // A Quick Action is a `.workflow` bundle in ~/Library/Services. Info.plist's
  // NSServices entry makes Finder show it for media files; document.wflow holds a
  // single "Run Shell Script" action that calls the cast helper per selected file.
  static Future<Map<String, String>> _installMacOS(String castBin) async {
    final home = Platform.environment['HOME'] ?? '';
    if (home.isEmpty) return {'macOS': 'error: no HOME'};
    final bundle =
        Directory('$home/Library/Services/$_menuLabel.workflow/Contents');
    try {
      if (!bundle.existsSync()) bundle.createSync(recursive: true);
      await File('${bundle.path}/Info.plist')
          .writeAsString(_macInfoPlist(), flush: true);
      await File('${bundle.path}/document.wflow')
          .writeAsString(_macWflow(castBin), flush: true);
      return {
        'macOS': 'ready (Finder → right-click → Quick Actions → $_menuLabel)'
      };
    } catch (e) {
      return {'macOS': 'error: $e'};
    }
  }

  static String _macInfoPlist() => '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key>
      <dict><key>default</key><string>$_menuLabel</string></dict>
      <key>NSMessage</key><string>runWorkflowAsService</string>
      <key>NSSendFileTypes</key>
      <array>
        <string>public.movie</string>
        <string>public.audiovisual-content</string>
        <string>public.audio</string>
      </array>
    </dict>
  </array>
</dict>
</plist>
''';

  // Minimal Automator "Run Shell Script" Quick Action that receives files as
  // arguments ("$@") and casts each. Each invocation runs in a detached
  // subshell ( … & ) so the Quick Action returns immediately instead of spinning
  // at 99% while it waits on the helper (which itself blocks up to 8s for a cast
  // result). If a future macOS rejects this document, re-create the action once
  // in Automator and replace document.wflow.
  static String _macWflow(String castBin) {
    final script =
        'for f in "\$@"; do ("$castBin" "\$f" >/dev/null 2>&1 &); done';
    final escaped = const HtmlEscape().convert(script);
    return '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>AMApplicationBuild</key><string>521</string>
  <key>AMApplicationVersion</key><string>2.10</string>
  <key>actions</key>
  <array>
    <dict>
      <key>action</key>
      <dict>
        <key>AMActionVersion</key><string>2.0.3</string>
        <key>AMProvides</key>
        <dict>
          <key>Container</key><string>List</string>
          <key>Types</key><array><string>com.apple.applescript.object</string></array>
        </dict>
        <key>ActionBundlePath</key>
        <string>/System/Library/Automator/Run Shell Script.action</string>
        <key>ActionName</key><string>Run Shell Script</string>
        <key>ActionParameters</key>
        <dict>
          <key>COMMAND_STRING</key><string>$escaped</string>
          <key>inputMethod</key><integer>1</integer>
          <key>shell</key><string>/bin/bash</string>
        </dict>
        <key>BundleIdentifier</key>
        <string>com.apple.RunShellScript</string>
      </dict>
    </dict>
  </array>
  <key>connectors</key><dict/>
  <key>workflowMetaData</key>
  <dict>
    <key>serviceInputTypeIdentifier</key>
    <string>com.apple.Automator.fileSystemObject</string>
    <key>serviceOutputTypeIdentifier</key>
    <string>com.apple.Automator.nothing</string>
    <key>workflowTypeIdentifier</key>
    <string>com.apple.Automator.servicesMenu</string>
  </dict>
</dict>
</plist>
''';
  }

  // ─── Linux ──────────────────────────────────────────────────────────────────
  // Nautilus runs executable scripts in ~/.local/share/nautilus/scripts and
  // passes selected paths in $NAUTILUS_SCRIPT_SELECTED_FILE_PATHS (newline-sep).
  static Future<Map<String, String>> _installLinux(String castBin) async {
    final home = Platform.environment['HOME'] ?? '';
    if (home.isEmpty) return {'Linux': 'error: no HOME'};
    final dir = Directory('$home/.local/share/nautilus/scripts');
    try {
      if (!dir.existsSync()) dir.createSync(recursive: true);
      final script = File('${dir.path}/$_menuLabel');
      await script.writeAsString('''#!/usr/bin/env bash
# PlayBridge "Play on TV" — installed by the desktop app.
IFS=\$'\\n'
for f in \$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS; do
  [ -n "\$f" ] && "$castBin" "\$f" &
done
''', flush: true);
      await Process.run('chmod', ['+x', script.path]);
      return {
        'Linux (Nautilus)': 'ready (right-click → Scripts → $_menuLabel)',
        'Nemo/Thunar/Dolphin': 'not auto-installed — add a custom action '
            'pointing at "$castBin %F"',
      };
    } catch (e) {
      return {'Linux': 'error: $e'};
    }
  }
}
