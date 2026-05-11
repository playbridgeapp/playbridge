import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import '../player_engine.dart';

class ExternalEngine extends PlayerEngine {
  ExternalEngine({required this.command, required this.argsBuilder});

  final String command;
  final List<String> Function(QueueItem item, File? m3u, int startIndex)
      argsBuilder;

  Process? _process;
  bool _isDisposed = false;
  String _state = 'idle';
  VoidCallback? onCompleted;

  @override
  String get state => _state;

  @override
  int get positionMs => 0;
  @override
  int get durationMs => 0;

  @override
  get tracks => null;
  @override
  get track => null;
  @override
  Future<void> setAudioTrack(dynamic t) async {}
  @override
  Future<void> setSubtitleTrack(dynamic t) async {}

  @override
  Future<void> open(QueueItem item) => openPlaylist([item], 0);

  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) async {
    await stop();
    if (_isDisposed) return;

    File? m3u;
    if (items.length > 1) {
      m3u = await _generateM3u(items);
    }

    final args = argsBuilder(items[startIndex.clamp(0, items.length - 1)], m3u, startIndex);
    debugPrint('[external] launching: $command ${args.join(' ')}');

    try {
      _process = await Process.start(command, args);
      _state = 'playing';
      notifyListeners();

      _process?.exitCode.then((code) {
        debugPrint('[external] process exited with $code');
        if (!_isDisposed) {
          _state = 'ended';
          notifyListeners();
          onCompleted?.call();
        }
        // Cleanup temp file
        m3u?.delete().catchError((_) => m3u!);
      });
    } catch (e) {
      debugPrint('[external] failed to launch $command: $e');
      _state = 'idle';
      notifyListeners();
    }
  }

  Future<File> _generateM3u(List<QueueItem> items) async {
    final temp = await getTemporaryDirectory();
    final file = File('${temp.path}/pb_playlist_${DateTime.now().millisecondsSinceEpoch}.m3u');
    final buffer = StringBuffer('#EXTM3U\n');
    for (final item in items) {
      buffer.writeln('#EXTINF:-1,${item.title}');
      buffer.writeln(item.url);
    }
    await file.writeAsString(buffer.toString());
    return file;
  }

  @override
  Future<void> resume() async {} // External players handle their own play/pause
  @override
  Future<void> pause() async {}
  @override
  Future<void> seek(Duration position) async {}

  @override
  Future<void> stop() async {
    _process?.kill();
    _process = null;
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    _isDisposed = true;
    await stop();
    super.dispose();
  }
}
