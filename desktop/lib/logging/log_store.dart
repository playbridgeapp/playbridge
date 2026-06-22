import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum LogLevel { verbose, debug, info, warn, error, unknown }

extension LogLevelCode on LogLevel {
  String get code => switch (this) {
        LogLevel.verbose => 'V',
        LogLevel.debug => 'D',
        LogLevel.info => 'I',
        LogLevel.warn => 'W',
        LogLevel.error => 'E',
        LogLevel.unknown => '?',
      };
}

class LogEntry {
  const LogEntry({
    required this.time,
    required this.level,
    required this.tag,
    required this.message,
  });

  final DateTime time;
  final LogLevel level;
  final String tag;
  final String message;

  /// "HH:mm:ss.SSS" — just the time, for the row subtitle.
  String get timeLabel {
    String two(int n) => n.toString().padLeft(2, '0');
    String three(int n) => n.toString().padLeft(3, '0');
    return '${two(time.hour)}:${two(time.minute)}:${two(time.second)}.${three(time.millisecond)}';
  }
}

/// Persistent, opt-in logger for the desktop receiver.
///
/// Logging is OFF by default: persisted logs can contain stream URLs and request
/// headers (incl. Debrid tokens) and are served over the LAN via `GET /logs`, so
/// retention is opt-in. Mirrors the TV player's `FileLogger`.
class LogStore {
  LogStore._();
  static final LogStore instance = LogStore._();

  static const _prefKey = 'logging_enabled';
  static const _logDirName = 'logs';
  static const _logBaseName = 'playbridge.log';
  static const _maxFileSize = 5 * 1024 * 1024; // 5 MB
  static const _maxRotated = 2;
  static const _ringCapacity = 2000;

  SharedPreferences? _prefs;
  Directory? _logDir;
  File? _logFile;
  bool _initialized = false;
  bool _enabled = false;

  bool get enabled => _enabled;

  /// Recent entries for the live viewer (oldest first; newest appended at end).
  final ValueNotifier<List<LogEntry>> entries =
      ValueNotifier<List<LogEntry>>(const []);

  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;
    _prefs = await SharedPreferences.getInstance();
    _enabled = _prefs?.getBool(_prefKey) ?? false;
    try {
      final support = await getApplicationSupportDirectory();
      final dir = Directory('${support.path}/$_logDirName');
      if (!dir.existsSync()) dir.createSync(recursive: true);
      _logDir = dir;
      _logFile = File('${dir.path}/$_logBaseName');
    } catch (_) {
      // Without a writable dir we keep the in-memory ring buffer only.
    }
  }

  Future<void> setEnabled(bool value) async {
    _enabled = value;
    await _prefs?.setBool(_prefKey, value);
    // Wipe persisted (possibly sensitive) logs when turning off.
    if (!value) await clear();
  }

  /// Structured log call.
  void log(LogLevel level, String tag, String message) {
    if (!_enabled) return;
    _record(LogEntry(
        time: DateTime.now(), level: level, tag: tag, message: message));
  }

  /// Tee target for `debugPrint` — parses level/tag heuristically from the line.
  void appendRaw(String line) {
    if (!_enabled || line.isEmpty) return;
    _record(_parseRaw(line));
  }

  void logCrash(Object error, StackTrace stack, {String context = 'uncaught'}) {
    if (!_enabled) return;
    _record(LogEntry(
      time: DateTime.now(),
      level: LogLevel.error,
      tag: 'crash',
      message: '$context: $error\n$stack',
    ));
  }

  /// Combined contents of all log files, oldest first. Used by the HTTP endpoint
  /// and "copy all". Safe to call from non-Flutter code (dart:io only).
  String combinedText() {
    final dir = _logDir;
    if (dir == null || !dir.existsSync()) return '';
    try {
      final files = dir
          .listSync()
          .whereType<File>()
          .where((f) => _isLogFile(f))
          .toList()
        ..sort(
            (a, b) => a.statSync().modified.compareTo(b.statSync().modified));
      return files.map((f) => f.readAsStringSync()).join('\n');
    } catch (_) {
      return '';
    }
  }

  Future<void> clear() async {
    entries.value = const [];
    final dir = _logDir;
    if (dir == null || !dir.existsSync()) return;
    try {
      for (final f in dir.listSync().whereType<File>()) {
        if (_isLogFile(f)) f.deleteSync();
      }
    } catch (_) {}
  }

  // ── internal ──────────────────────────────────────────────────────────

  bool _isLogFile(File f) =>
      f.uri.pathSegments.last.startsWith(_logBaseName.split('.').first);

  void _record(LogEntry e) {
    final next = List<LogEntry>.of(entries.value)..add(e);
    if (next.length > _ringCapacity) {
      next.removeRange(0, next.length - _ringCapacity);
    }
    entries.value = next;
    _writeLine(_format(e));
  }

  void _writeLine(String line) {
    final file = _logFile;
    if (file == null) return;
    try {
      if (file.existsSync() && file.lengthSync() >= _maxFileSize) _rotate();
      file.writeAsStringSync('$line\n', mode: FileMode.append, flush: false);
    } catch (_) {}
  }

  void _rotate() {
    final file = _logFile;
    if (file == null) return;
    try {
      final oldest = File('${file.path}.$_maxRotated');
      if (oldest.existsSync()) oldest.deleteSync();
      for (var i = _maxRotated - 1; i >= 1; i--) {
        final src = File('${file.path}.$i');
        if (src.existsSync()) src.renameSync('${file.path}.${i + 1}');
      }
      if (file.existsSync()) file.renameSync('${file.path}.1');
    } catch (_) {}
  }

  String _format(LogEntry e) {
    String two(int n) => n.toString().padLeft(2, '0');
    String three(int n) => n.toString().padLeft(3, '0');
    final t = e.time;
    final ts =
        '${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}:${two(t.second)}.${three(t.millisecond)}';
    return '$ts ${e.level.code}/${e.tag}: ${e.message}';
  }

  static final _tagPrefix = RegExp(r'^\[([^\]]+)\]\s*(.*)$', dotAll: true);

  LogEntry _parseRaw(String line) {
    var tag = '';
    var msg = line;
    final m = _tagPrefix.firstMatch(line);
    if (m != null) {
      tag = m.group(1)!;
      msg = m.group(2)!;
    }
    final lower = line.toLowerCase();
    final level = (lower.contains('error') || lower.contains('exception'))
        ? LogLevel.error
        : lower.contains('warn')
            ? LogLevel.warn
            : LogLevel.debug;
    return LogEntry(time: DateTime.now(), level: level, tag: tag, message: msg);
  }
}
