import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:video_player/video_player.dart';
import '../player_engine.dart';

class FvpEngine extends PlayerEngine {
  VideoPlayerController? _controller;
  VoidCallback? onCompleted;
  bool _isDisposed = false;

  @override
  String get state {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return 'idle';
    if (c.value.isBuffering) return 'buffering';
    if (c.value.isPlaying) return 'playing';
    if (c.value.duration > Duration.zero &&
        c.value.position >= c.value.duration) {
      return 'ended';
    }
    return 'paused';
  }

  @override
  int get positionMs => _controller?.value.position.inMilliseconds ?? 0;
  @override
  int get durationMs => _controller?.value.duration.inMilliseconds ?? 0;

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
    await _controller?.dispose();
    if (_isDisposed) return;

    final controller = VideoPlayerController.networkUrl(
      Uri.parse(item.url),
      httpHeaders: item.headers ?? const {},
    );

    _controller = controller;
    controller.addListener(_onStateChange);

    try {
      await controller.initialize();
      if (_isDisposed) return;
      await controller.play();
    } catch (e) {
      debugPrint('[fvp] init error: $e');
    }
    notifyListeners();
  }

  void _onStateChange() {
    if (_isDisposed) return;
    if (state == 'ended') {
      onCompleted?.call();
    }
    notifyListeners();
  }

  @override
  Future<void> resume() async => _controller?.play();
  @override
  Future<void> pause() async => _controller?.pause();
  @override
  Future<void> seek(Duration position) async => _controller?.seekTo(position);
  @override
  Future<void> stop() async => _controller?.pause();

  @override
  Future<void> dispose() async {
    _isDisposed = true;
    _controller?.removeListener(_onStateChange);
    await _controller?.dispose();
    _controller = null;
    super.dispose();
  }

  VideoPlayerController? get controller => _controller;
}
