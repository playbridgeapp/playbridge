import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';
import '../player_engine.dart';

class MpvEngine extends PlayerEngine {
  MpvEngine() {
    _configureMpv();
    _subs.addAll([
      player.stream.playing.listen((_) => notifyListeners()),
      player.stream.position.listen((_) => _emitThrottled()),
      player.stream.duration.listen((_) => notifyListeners()),
      player.stream.buffering.listen((_) => notifyListeners()),
      player.stream.completed.listen((done) {
        if (done) onCompleted?.call();
        notifyListeners();
      }),
      player.stream.tracks.listen((_) => notifyListeners()),
      player.stream.track.listen((_) => notifyListeners()),
      player.stream.error.listen((e) {
        debugPrint('[mpv] error: $e');
        notifyListeners();
      }),
    ]);
  }

  final Player player = Player();
  final List<StreamSubscription> _subs = [];
  VoidCallback? onCompleted;

  DateTime _lastPositionEmit = DateTime.fromMillisecondsSinceEpoch(0);
  static const _positionEmitInterval = Duration(milliseconds: 200);

  void _emitThrottled() {
    final now = DateTime.now();
    if (now.difference(_lastPositionEmit) < _positionEmitInterval) return;
    _lastPositionEmit = now;
    notifyListeners();
  }

  @override
  String get state {
    if (player.state.completed) return 'ended';
    if (player.state.buffering) return 'buffering';
    if (player.state.playing) return 'playing';
    return 'paused'; // Simplified, Controller handles 'idle'
  }

  @override
  int get positionMs => player.state.position.inMilliseconds;
  @override
  int get durationMs => player.state.duration.inMilliseconds;

  @override
  Tracks get tracks => player.state.tracks;
  @override
  Track get track => player.state.track;

  @override
  Future<void> setAudioTrack(dynamic t) => player.setAudioTrack(t as AudioTrack);
  @override
  Future<void> setSubtitleTrack(dynamic t) => player.setSubtitleTrack(t as SubtitleTrack);

  @override
  Future<void> open(QueueItem item) => openPlaylist([item], 0);

  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) async {
    final playlist = Playlist(
      items.map((i) => Media(i.url, httpHeaders: i.headers)).toList(),
      index: startIndex,
    );
    await player.open(playlist, play: true);

    // External subtitles for the current item
    final item = items[startIndex.clamp(0, items.length - 1)];
    final subs = item.subtitles ?? const <String>[];
    if (subs.isNotEmpty) {
      final native = player.platform;
      if (native is NativePlayer) {
        for (var i = 0; i < subs.length; i++) {
          try {
            await native.command(['sub-add', subs[i], 'auto', 'External ${i + 1}']);
          } catch (e) {
            debugPrint('[mpv] sub-add failed: $e');
          }
        }
      }
    }
    await player.play();
  }

  @override
  Future<void> resume() => player.play();
  @override
  Future<void> pause() => player.pause();
  @override
  Future<void> seek(Duration position) => player.seek(position);
  @override
  Future<void> stop() => player.stop();

  @override
  Future<void> dispose() async {
    _statsTimer?.cancel();
    for (final s in _subs) {
      await s.cancel();
    }
    await player.dispose();
    super.dispose();
  }

  Timer? _statsTimer;
  Future<void> _configureMpv() async {
    final native = player.platform;
    if (native is NativePlayer) {
      try {
        await native.setProperty('hwdec', 'auto-safe');
        await native.setProperty('cache', 'yes');
        await native.setProperty('demuxer-max-bytes', '314572800');
        await native.setProperty('demuxer-max-back-bytes', '104857600');
      } catch (e) {
        debugPrint('[mpv] failed to tune: $e');
      }
      _startStatsLogger(native);
    }
  }

  void _startStatsLogger(NativePlayer native) {
    _statsTimer?.cancel();
    _statsTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      if (player.state.position == Duration.zero) return;
      try {
        final hw = await native.getProperty('hwdec-current');
        final br = await native.getProperty('video-bitrate');
        debugPrint('[mpv stats] hw=$hw | br=$br');
      } catch (_) {}
    });
  }
}
