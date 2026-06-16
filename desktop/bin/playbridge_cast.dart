// PlayBridge "Play on TV" CLI — invoked by the OS file-manager context menu
// (Windows SystemFileAssociations, macOS Quick Action, Linux Nautilus script).
//
// It connects to the running desktop app over the loopback bridge (the same
// `{port, token}` handshake the native-messaging host uses) and asks it to cast
// the given local file. If the app isn't running, it tries to launch it with
// `--cast-file` so the app casts once a TV is connected.
//
// Usage:  playbridge_cast <file-or-url> [title]
//
// Build:  dart compile exe bin/playbridge_cast.dart -o build/playbridge_cast
// (Plain Dart — no Flutter. Imports only `bridge_paths.dart` + dart: libs.)

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:playbridge_desktop/bridge_paths.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    stderr.writeln('usage: playbridge_cast <file-or-url> [title]');
    exit(2);
  }
  final target = args[0];
  final title = args.length > 1 && args[1].isNotEmpty ? args[1] : null;
  final isUrl = target.startsWith('http://') || target.startsWith('https://');
  final value = isUrl ? target : File(target).absolute.path;

  if (!isUrl && !File(value).existsSync()) {
    stderr.writeln('playbridge_cast: file not found: $value');
    exit(1);
  }

  // 1) Try to forward to the running app.
  final info = await _readBridgeInfo();
  if (info != null) {
    final ok = await _forward(info, isUrl: isUrl, value: value, title: title);
    if (ok) exit(0);
    stderr.writeln('playbridge_cast: app reachable but cast failed '
        '(no TV connected?)');
    // fall through to a launch attempt so the user at least sees the app.
  }

  // 2) App not running (or forward failed) → launch it, passing the file.
  final launched = await _launchApp(value, title);
  exit(launched ? 0 : 1);
}

Future<Map<String, dynamic>?> _readBridgeInfo() async {
  try {
    final file = File(bridgeFilePath());
    if (!await file.exists()) return null;
    final obj = jsonDecode(await file.readAsString());
    return obj is Map<String, dynamic> ? obj : null;
  } catch (_) {
    return null;
  }
}

/// Connect, authenticate with the token, send the cast command, and wait for the
/// app's `result` frame (with a timeout). Returns whether the cast succeeded.
Future<bool> _forward(
  Map<String, dynamic> info, {
  required bool isUrl,
  required String value,
  String? title,
}) async {
  final port = (info['port'] as num?)?.toInt();
  final token = info['token'] as String?;
  if (port == null || token == null) return false;

  Socket? socket;
  try {
    socket = await Socket.connect(InternetAddress.loopbackIPv4, port,
        timeout: const Duration(seconds: 2));
  } catch (_) {
    return false;
  }

  final completer = Completer<bool>();
  final buf = <int>[];
  socket.listen(
    (data) {
      buf.addAll(data);
      int nl;
      while ((nl = buf.indexOf(0x0A)) >= 0) {
        final line = utf8.decode(buf.sublist(0, nl)).trim();
        buf.removeRange(0, nl + 1);
        if (line.isEmpty) continue;
        try {
          final obj = jsonDecode(line);
          if (obj is Map && obj['type'] == 'result' && !completer.isCompleted) {
            completer.complete(obj['ok'] == true);
          }
        } catch (_) {}
      }
    },
    onDone: () {
      if (!completer.isCompleted) completer.complete(false);
    },
    onError: (_) {
      if (!completer.isCompleted) completer.complete(false);
    },
    cancelOnError: true,
  );

  // Auth line, then the command.
  socket.write('${jsonEncode({'token': token})}\n');
  final cmd = isUrl
      ? {'cmd': 'cast', 'url': value, if (title != null) 'title': title}
      : {'cmd': 'cast_file', 'path': value, if (title != null) 'title': title};
  socket.write('${jsonEncode(cmd)}\n');

  final ok = await completer.future
      .timeout(const Duration(seconds: 8), onTimeout: () => false);
  try {
    await socket.close();
  } catch (_) {}
  return ok;
}

/// Best-effort launch of the desktop GUI, passing the file via `--cast-file` so
/// it casts once a TV is connected. Locates the app relative to this binary
/// (packaged builds keep them together), falling back to the same dir.
Future<bool> _launchApp(String value, String? title) async {
  final exeFile = File(Platform.resolvedExecutable);
  final exeDir = exeFile.parent.path;
  final args = [
    '--cast-file',
    value,
    if (title != null) ...['--cast-title', title]
  ];

  try {
    if (Platform.isMacOS) {
      // This binary usually lives in <App>.app/Contents/Resources; the bundle is
      // three levels up. `open -a <bundle> --args …` reuses a running instance.
      final bundle = _macAppBundle(exeFile);
      if (bundle != null) {
        await Process.start('open', ['-a', bundle, '--args', ...args],
            mode: ProcessStartMode.detached);
        return true;
      }
    }
    // Windows/Linux (and macOS fallback): launch a sibling app executable.
    for (final name in _appExeNames()) {
      final candidate = File('$exeDir${Platform.pathSeparator}$name');
      if (candidate.existsSync()) {
        await Process.start(candidate.path, args,
            mode: ProcessStartMode.detached);
        return true;
      }
    }
  } catch (_) {}
  stderr.writeln('playbridge_cast: PlayBridge is not running and could not be '
      'launched — open it and try again.');
  return false;
}

/// Candidate GUI executable names beside this binary (packaged layout).
List<String> _appExeNames() {
  if (Platform.isWindows) return ['playbridge_desktop.exe', 'PlayBridge.exe'];
  return ['playbridge_desktop', 'PlayBridge'];
}

/// Walk up from `<bundle>.app/Contents/Resources/<bin>` to the `.app` path.
String? _macAppBundle(File exeFile) {
  var dir = exeFile.parent;
  for (var i = 0; i < 5; i++) {
    if (dir.path.endsWith('.app')) return dir.path;
    final parent = dir.parent;
    if (parent.path == dir.path) break;
    dir = parent;
  }
  return null;
}
