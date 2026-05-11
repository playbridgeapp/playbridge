import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../player_engine.dart';

class ExternalEngine extends PlayerEngine {
  ExternalEngine({required this.command, required this.argsBuilder});

  final String command;
  final List<String> Function(QueueItem item) argsBuilder;

  Process? _process;
  bool _isDisposed = false;
  String _state = 'idle';
  VoidCallback? onCompleted;

  @override
  String get state => _state;

  @override
  int get positionMs => 0; // Position tracking not implemented for generic external
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
  Future<void> open(QueueItem item) async {
    await stop();
    if (_isDisposed) return;

    final args = argsBuilder(item);
    debugPrint('[external] launching: $command ${args.join(' ')}');

    try {
      _process = await Process.start(command, args);
      _state = 'playing';
      notifyListeners();

      // Listen for exit to trigger next/idle
      _process?.exitCode.then((code) {
        debugPrint('[external] process exited with $code');
        if (!_isDisposed) {
          _state = 'ended';
          notifyListeners();
          onCompleted?.call();
        }
      });
    } catch (e) {
      debugPrint('[external] failed to launch $command: $e');
      _state = 'idle';
      notifyListeners();
    }
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
