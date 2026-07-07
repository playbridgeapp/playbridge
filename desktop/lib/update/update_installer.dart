import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// Downloads a release archive and performs the in-place self-update.
///
/// The desktop builds ship as bare archives (macOS `.app` zip, Windows zip,
/// Linux tar.gz) with no installer, so the update flow is:
///
///  1. download the archive to a staging dir under the system temp dir
///  2. extract it with the platform's native tool (`ditto` / `Expand-Archive`
///     / `tar` — the Dart archive packages don't preserve symlinks or exec
///     bits, which the .app frameworks and Linux bundle need)
///  3. write a tiny detached "swap script" that waits for this process to
///     exit, moves the old install aside, moves the new build into place,
///     relaunches the app, and cleans up
///  4. spawn the script detached and `exit(0)`
///
/// The running executable is never overwritten while alive — Windows locks it
/// outright, and on POSIX swapping directories under a live process is asking
/// for trouble. The swap script sidesteps both by working after exit.
class UpdateInstaller {
  /// Root for downloads, extraction, swap scripts, and the old-build backup.
  Directory _stagingRoot() => Directory(
      '${Directory.systemTemp.path}${Platform.pathSeparator}playbridge_update');

  /// Download [url] (following redirects — GitHub assets 302 to a CDN) into
  /// the staging dir. [onProgress] gets 0..1, or null without Content-Length.
  Future<File> download(String url,
      {void Function(double? fraction)? onProgress}) async {
    final staging = _stagingRoot();
    if (staging.existsSync()) staging.deleteSync(recursive: true);
    staging.createSync(recursive: true);

    final name = Uri.parse(url).pathSegments.isNotEmpty
        ? Uri.parse(url).pathSegments.last
        : 'update-archive';
    final target = File('${staging.path}${Platform.pathSeparator}$name');

    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15)
      ..userAgent = 'PlayBridge-Desktop-Updater';
    try {
      final req = await client.getUrl(Uri.parse(url));
      final resp = await req.close();
      if (resp.statusCode != 200) {
        throw HttpException('HTTP ${resp.statusCode} downloading $url');
      }
      final total = resp.contentLength; // -1 when unknown
      var received = 0;
      final sink = target.openWrite();
      try {
        await for (final chunk in resp) {
          received += chunk.length;
          sink.add(chunk);
          onProgress?.call(total > 0 ? received / total : null);
        }
        await sink.flush();
      } finally {
        await sink.close();
      }
      debugPrint(
          'UpdateInstaller: downloaded $received bytes → ${target.path}');
      return target;
    } finally {
      client.close(force: true);
    }
  }

  /// Extract, stage, spawn the swap script, and exit the process.
  ///
  /// Throws (and leaves the app running) if anything fails *before* the
  /// handoff — after the script is spawned this method calls `exit(0)` and
  /// never returns.
  Future<void> installAndRestart(File archive) async {
    final extracted =
        Directory('${archive.parent.path}${Platform.pathSeparator}extracted');
    extracted.createSync(recursive: true);
    await _extract(archive, extracted);

    final install = _installRoot();
    final payload = _payloadRoot(extracted);
    _assertLooksLikeApp(payload);
    _assertWritable(install.parent);

    final backup = Directory(
        '${install.parent.path}${Platform.pathSeparator}.${_basename(install.path)}.old');

    final script = Platform.isWindows
        ? _writeWindowsSwapScript(
            installDir: install.path,
            payloadDir: payload.path,
            backupDir: backup.path,
          )
        : _writePosixSwapScript(
            installDir: install.path,
            payloadDir: payload.path,
            backupDir: backup.path,
          );

    debugPrint('UpdateInstaller: handing off to swap script ${script.path}');
    if (Platform.isWindows) {
      // `start "" /min` so the console window doesn't flash over the app.
      await Process.start(
        'cmd.exe',
        ['/c', 'start', '', '/min', 'cmd', '/c', script.path],
        mode: ProcessStartMode.detached,
      );
    } else {
      await Process.start('/bin/bash', [script.path],
          mode: ProcessStartMode.detached);
    }
    // Clean exit so the WS server / tray / mpv tear down normally; the script
    // waits on our PID before touching anything.
    exit(0);
  }

  /// Open [url] in the default browser (manual-update fallback). Best-effort.
  void openInBrowser(String url) {
    try {
      if (Platform.isMacOS) {
        Process.start('open', [url], mode: ProcessStartMode.detached);
      } else if (Platform.isWindows) {
        Process.start('rundll32', ['url.dll,FileProtocolHandler', url],
            mode: ProcessStartMode.detached);
      } else {
        Process.start('xdg-open', [url], mode: ProcessStartMode.detached);
      }
    } catch (e) {
      debugPrint('UpdateInstaller: openInBrowser failed: $e');
    }
  }

  // ── per-OS layout ──────────────────────────────────────────────────────────

  /// The directory the swap replaces:
  ///  - macOS: the `.app` bundle (resolvedExecutable is `<app>/Contents/MacOS/<bin>`)
  ///  - Windows: the folder holding the exe (zip root = Release folder contents)
  ///  - Linux: the `bundle` dir holding the executable (tarball contains `bundle/`)
  Directory _installRoot() {
    final exe = Platform.resolvedExecutable;
    if (Platform.isMacOS) {
      return File(exe).parent.parent.parent; // MacOS → Contents → <app>.app
    }
    return File(exe).parent;
  }

  /// The freshly-extracted counterpart of [_installRoot].
  Directory _payloadRoot(Directory extracted) {
    if (Platform.isMacOS) {
      for (final entry in extracted.listSync()) {
        if (entry is Directory && entry.path.endsWith('.app')) return entry;
      }
      throw StateError('No .app bundle found in the downloaded archive');
    }
    if (Platform.isLinux) {
      final bundle =
          Directory('${extracted.path}${Platform.pathSeparator}bundle');
      if (!bundle.existsSync()) {
        throw StateError('No bundle/ dir found in the downloaded archive');
      }
      return bundle;
    }
    return extracted; // Windows: zip root is the install dir contents
  }

  /// Sanity check: the payload must contain our executable, so a truncated or
  /// mis-shaped archive can't wipe out a working install.
  void _assertLooksLikeApp(Directory payload) {
    final String probe;
    if (Platform.isMacOS) {
      probe = '${payload.path}/Contents/MacOS';
    } else {
      final exeName = _basename(Platform.resolvedExecutable);
      probe = '${payload.path}${Platform.pathSeparator}$exeName';
    }
    if (!File(probe).existsSync() && !Directory(probe).existsSync()) {
      throw StateError('Downloaded archive is missing $probe');
    }
  }

  void _assertWritable(Directory dir) {
    final probe =
        File('${dir.path}${Platform.pathSeparator}.playbridge_write_probe');
    try {
      probe.writeAsStringSync('x');
      probe.deleteSync();
    } catch (_) {
      throw StateError(
          "Can't write to ${dir.path} — the app isn't installed in a "
          'user-writable location. Update it manually instead.');
    }
  }

  // ── extraction ─────────────────────────────────────────────────────────────

  Future<void> _extract(File archive, Directory dest) async {
    final List<String> cmd;
    if (Platform.isMacOS) {
      // ditto preserves symlinks, exec bits, and resource forks — required
      // for the frameworks inside the .app.
      cmd = ['ditto', '-x', '-k', archive.path, dest.path];
    } else if (Platform.isWindows) {
      cmd = [
        'powershell',
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        "Expand-Archive -LiteralPath '${archive.path}' "
            "-DestinationPath '${dest.path}' -Force",
      ];
    } else {
      cmd = ['tar', '-xzf', archive.path, '-C', dest.path];
    }
    final result = await Process.run(cmd.first, cmd.sublist(1));
    if (result.exitCode != 0) {
      throw ProcessException(cmd.first, cmd.sublist(1),
          'extract failed: ${result.stderr}', result.exitCode);
    }
  }

  // ── swap scripts ───────────────────────────────────────────────────────────

  File _writePosixSwapScript({
    required String installDir,
    required String payloadDir,
    required String backupDir,
  }) {
    final relaunch = Platform.isMacOS
        ? 'open -n "$installDir"'
        : 'nohup "$installDir/${_basename(Platform.resolvedExecutable)}" '
            '>/dev/null 2>&1 &';
    final script =
        File('${_stagingRoot().path}${Platform.pathSeparator}swap.sh');
    script.writeAsStringSync('''
#!/bin/bash
# PlayBridge self-update swap script (generated; safe to delete).
PID=$pid
# Wait (max ~60s) for the app to exit.
for i in \$(seq 1 120); do
  kill -0 "\$PID" 2>/dev/null || break
  sleep 0.5
done
rm -rf "$backupDir"
mv "$installDir" "$backupDir" || exit 1
if ! mv "$payloadDir" "$installDir"; then
  mv "$backupDir" "$installDir"   # roll back
  exit 1
fi
$relaunch
rm -rf "$backupDir" "${_stagingRoot().path}" 2>/dev/null
''');
    Process.runSync('chmod', ['+x', script.path]);
    return script;
  }

  File _writeWindowsSwapScript({
    required String installDir,
    required String payloadDir,
    required String backupDir,
  }) {
    final exeName = _basename(Platform.resolvedExecutable);
    final script =
        File('${_stagingRoot().path}${Platform.pathSeparator}swap.bat');
    script.writeAsStringSync('''
@echo off
rem PlayBridge self-update swap script (generated; safe to delete).
setlocal enabledelayedexpansion
set PID=$pid

:waitloop
tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
if not errorlevel 1 (
  timeout /t 1 /nobreak >nul
  goto waitloop
)

if exist "$backupDir" rd /s /q "$backupDir"

rem The exe lock can linger briefly after exit (AV scans etc.) — retry.
set tries=0
:swap
move "$installDir" "$backupDir" >nul 2>&1
if errorlevel 1 (
  set /a tries+=1
  if !tries! lss 30 (
    timeout /t 1 /nobreak >nul
    goto swap
  )
  exit /b 1
)

move "$payloadDir" "$installDir" >nul 2>&1
if errorlevel 1 (
  move "$backupDir" "$installDir" >nul 2>&1
  exit /b 1
)

start "" "$installDir\\$exeName"
rd /s /q "$backupDir" >nul 2>&1
(goto) 2>nul & del "%~f0"
''');
    return script;
  }

  static String _basename(String path) =>
      path.split(Platform.pathSeparator).last;
}
