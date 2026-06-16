import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';

/// Writes the browser native-messaging host manifests so the PlayBridge
/// extension can reach this app — without the user editing files by hand.
///
/// This is the standard way native-messaging apps register themselves: drop a
/// `com.playbridge.host.json` manifest into each browser's `NativeMessagingHosts`
/// directory, pointing at the host binary and allowing our extension id. We run
/// it on launch (best-effort, idempotent) and from a "Set up browser casting"
/// button.
class NativeHostInstaller {
  static const hostName = 'com.playbridge.host';

  /// Fixed extension ids. Firefox = gecko id from the extension manifest.
  static const firefoxExtId = 'video-detector@playbridge';

  /// Chrome-family extension ids allowed to reach the host. The first is the
  /// stable *unpacked-dev* id derived from the `key` baked into
  /// `manifests/chrome.json`. When the extension is published, the Chrome Web
  /// Store / Edge Add-ons assign their own permanent ids — add them here so
  /// store-installed copies can reach the host too. (Each becomes a
  /// `chrome-extension://<id>/` entry in the host manifest's `allowed_origins`.)
  static const chromeExtIds = <String>[
    'lhkbcaaoomlmoleggodlbafalokdgokn', // unpacked dev (from manifest `key`)
    // TODO: add the Chrome Web Store id after publishing, e.g.
    // 'abcdefghijklmnopabcdefghijklmnop',
    // TODO: add the Edge Add-ons id if shipping there.
  ];

  /// Locate the compiled `playbridge_host` binary on disk. Checks an env
  /// override, next to the running executable (packaged builds), the macOS
  /// `.app/Contents/Resources`, and `build/` relative to the working dir (dev).
  static String? findHostBinary() {
    final exe = Platform.isWindows ? 'playbridge_host.exe' : 'playbridge_host';
    final exeFile = File(Platform.resolvedExecutable);
    final exeDir = exeFile.parent.path;
    final cwd = Directory.current.path;
    final candidates = <String>[
      if (Platform.environment['PLAYBRIDGE_HOST_BIN'] != null)
        Platform.environment['PLAYBRIDGE_HOST_BIN']!,
      '$exeDir/$exe',
      // macOS .app: Contents/MacOS/<exe> sits beside Contents/Resources/<exe>.
      '${exeFile.parent.parent.path}/Resources/$exe',
      '$cwd/build/$exe',
      '$cwd/desktop/build/$exe',
    ];
    for (final c in candidates) {
      final f = File(c);
      if (f.existsSync()) return f.absolute.path;
    }
    return null;
  }

  /// Returns a usable host-binary path: an on-disk one if present, otherwise
  /// extracts the binary bundled as a Flutter asset (`assets/host/...`) into the
  /// app-support dir and makes it executable. The asset path makes a packaged
  /// `flutter build` carry the host automatically.
  static Future<String?> ensureHostBinary() async {
    final onDisk = findHostBinary();
    if (onDisk != null) return onDisk;
    try {
      final exe = Platform.isWindows ? 'playbridge_host.exe' : 'playbridge_host';
      final data = await rootBundle.load('assets/host/$exe');
      final dir = Directory(
          '${(await getApplicationSupportDirectory()).path}/host');
      if (!dir.existsSync()) dir.createSync(recursive: true);
      final out = File('${dir.path}/$exe');
      await out.writeAsBytes(
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
      );
      if (!Platform.isWindows) {
        await Process.run('chmod', ['+x', out.path]);
      }
      return out.absolute.path;
    } catch (_) {
      return null;
    }
  }

  /// label → (NativeMessagingHosts dir, is-firefox-family) for the current OS.
  static Map<String, ({String dir, bool firefox})> _browserDirs() {
    final home = Platform.environment['HOME'] ?? '';
    final out = <String, ({String dir, bool firefox})>{};
    if (Platform.isMacOS) {
      final s = '$home/Library/Application Support';
      out['Firefox'] = (dir: '$s/Mozilla/NativeMessagingHosts', firefox: true);
      out['Chrome'] = (dir: '$s/Google/Chrome/NativeMessagingHosts', firefox: false);
      out['Chromium'] = (dir: '$s/Chromium/NativeMessagingHosts', firefox: false);
      out['Brave'] = (
        dir: '$s/BraveSoftware/Brave-Browser/NativeMessagingHosts',
        firefox: false,
      );
      out['Edge'] = (dir: '$s/Microsoft Edge/NativeMessagingHosts', firefox: false);
    } else if (Platform.isLinux) {
      out['Firefox'] = (dir: '$home/.mozilla/native-messaging-hosts', firefox: true);
      out['Chrome'] = (dir: '$home/.config/google-chrome/NativeMessagingHosts', firefox: false);
      out['Chromium'] = (dir: '$home/.config/chromium/NativeMessagingHosts', firefox: false);
      out['Brave'] = (
        dir: '$home/.config/BraveSoftware/Brave-Browser/NativeMessagingHosts',
        firefox: false,
      );
    }
    return out;
  }

  static String _manifest(String hostPath, bool firefox) {
    final map = <String, dynamic>{
      'name': hostName,
      'description': 'PlayBridge native-messaging host',
      'path': hostPath,
      'type': 'stdio',
      if (firefox)
        'allowed_extensions': [firefoxExtId]
      else
        'allowed_origins': [
          for (final id in chromeExtIds) 'chrome-extension://$id/',
        ],
    };
    return const JsonEncoder.withIndent('  ').convert(map);
  }

  /// Installs the manifest into every detected browser. Returns label → status
  /// (`ready` / `not installed (skipped)` / `error: …`). A browser is treated as
  /// present when the parent of its `NativeMessagingHosts` dir exists.
  static Future<Map<String, String>> install() async {
    final result = <String, String>{};

    final host = await ensureHostBinary();
    if (host == null) {
      result['host binary'] =
          'not found — build it: cd desktop && dart compile exe bin/playbridge_host.dart -o build/playbridge_host';
      return result;
    }

    if (Platform.isWindows) return _installWindows(host);

    for (final entry in _browserDirs().entries) {
      final dir = Directory(entry.value.dir);
      if (!dir.parent.existsSync()) {
        result[entry.key] = 'not installed (skipped)';
        continue;
      }
      try {
        if (!dir.existsSync()) dir.createSync(recursive: true);
        final file = File('${dir.path}/$hostName.json');
        await file.writeAsString(_manifest(host, entry.value.firefox), flush: true);
        result[entry.key] = 'ready';
      } catch (e) {
        result[entry.key] = 'error: $e';
      }
    }
    return result;
  }

  /// Windows registers native-messaging hosts via the registry, not a
  /// `NativeMessagingHosts` directory: a per-browser key whose default value is
  /// the absolute path to the manifest JSON. We write the manifest (two
  /// variants — Chromium-family `allowed_origins` vs Firefox `allowed_extensions`)
  /// to the app-support dir, then point each browser's HKCU key at it. HKCU
  /// needs no admin. Unconditionally writes the keys (we can't cheaply detect
  /// which browsers are installed); a key for an absent browser is harmless.
  static Future<Map<String, String>> _installWindows(String host) async {
    final result = <String, String>{};
    final Directory dir;
    try {
      dir = Directory('${(await getApplicationSupportDirectory()).path}\\native');
      if (!dir.existsSync()) dir.createSync(recursive: true);
    } catch (e) {
      result['manifests'] = 'error: $e';
      return result;
    }

    final chromeManifest = File('${dir.path}\\$hostName.json');
    final firefoxManifest = File('${dir.path}\\$hostName.firefox.json');
    try {
      await chromeManifest.writeAsString(_manifest(host, false), flush: true);
      await firefoxManifest.writeAsString(_manifest(host, true), flush: true);
    } catch (e) {
      result['manifests'] = 'error: $e';
      return result;
    }

    // label → (registry vendor subkey, manifest file).
    final targets = <String, ({String subkey, String manifest})>{
      'Chrome': (subkey: r'Google\Chrome', manifest: chromeManifest.path),
      'Chromium': (subkey: 'Chromium', manifest: chromeManifest.path),
      'Edge': (subkey: r'Microsoft\Edge', manifest: chromeManifest.path),
      'Brave': (
        subkey: r'BraveSoftware\Brave-Browser',
        manifest: chromeManifest.path,
      ),
      'Firefox': (subkey: 'Mozilla', manifest: firefoxManifest.path),
    };

    for (final entry in targets.entries) {
      final key =
          'HKCU\\Software\\${entry.value.subkey}\\NativeMessagingHosts\\$hostName';
      try {
        final res = await Process.run('reg', [
          'add', key, '/ve', '/t', 'REG_SZ', '/d', entry.value.manifest, '/f',
        ]);
        result[entry.key] = res.exitCode == 0
            ? 'ready'
            : 'error: ${(res.stderr as String).trim()}';
      } catch (e) {
        result[entry.key] = 'error: $e';
      }
    }
    return result;
  }

  /// Best-effort install on app launch; logs the outcome, never throws.
  static Future<void> installSilently() async {
    try {
      final r = await install();
      debugPrint('[host-install] $r');
    } catch (e) {
      debugPrint('[host-install] failed: $e');
    }
  }
}
