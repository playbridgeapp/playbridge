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
      player.stream.volume.listen((_) => notifyListeners()),
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
  double get volume => player.state.volume / 100.0;

  @override
  Tracks get tracks => player.state.tracks;
  @override
  Track get track => player.state.track;

  @override
  Future<void> setAudioTrack(dynamic t) =>
      player.setAudioTrack(t as AudioTrack);
  @override
  Future<void> setSubtitleTrack(dynamic t) =>
      player.setSubtitleTrack(t as SubtitleTrack);

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
            await native
                .command(['sub-add', subs[i], 'auto', 'External ${i + 1}']);
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
  Future<void> setVolume(double volume) => player.setVolume(volume * 100.0);
  @override
  Future<void> stop() => player.stop();

  @override
  Future<void> dispose() async {
    _statsTimer?.cancel();
    stats.dispose();
    for (final s in _subs) {
      await s.cancel();
    }
    await player.dispose();
    super.dispose();
  }

  Future<void> _configureMpv() async {
    final native = player.platform;
    if (native is NativePlayer) {
      try {
        await native.setProperty('hwdec', 'auto-safe');
        await native.setProperty('cache', 'yes');
        await native.setProperty('demuxer-max-bytes', '314572800');
        await native.setProperty('demuxer-max-back-bytes', '104857600');
        await native.setProperty('network-timeout', '60');
      } catch (e) {
        debugPrint('[mpv] failed to tune: $e');
      }
    }
  }

  // ─── Live playback stats (driven on-demand by the stats overlay) ───────────

  /// Latest sampled stats, or null while collection is off. Sampled ~1/sec only
  /// while [setStatsCollecting] is enabled, so there's zero cost when hidden.
  final ValueNotifier<PlaybackStats?> stats =
      ValueNotifier<PlaybackStats?>(null);
  Timer? _statsTimer;

  void setStatsCollecting(bool active) {
    if (active) {
      if (_statsTimer != null) return;
      unawaited(_pollStats());
      _statsTimer =
          Timer.periodic(const Duration(seconds: 1), (_) => _pollStats());
    } else {
      _statsTimer?.cancel();
      _statsTimer = null;
      stats.value = null;
    }
  }

  Future<void> _pollStats() async {
    final native = player.platform;
    if (native is! NativePlayer) return;

    Future<String?> read(String prop) async {
      try {
        final v = await native.getProperty(prop);
        return v.isEmpty ? null : v;
      } catch (_) {
        return null;
      }
    }

    int? asInt(String? s) =>
        s == null ? null : int.tryParse(s.split('.').first);
    double? asDouble(String? s) => s == null ? null : double.tryParse(s);

    final r = await Future.wait([
      read('frame-drop-count'), // 0  dropped by VO
      read('decoder-frame-drop-count'), // 1  dropped by decoder
      read('estimated-vf-fps'), // 2
      read('container-fps'), // 3
      read('estimated-display-fps'), // 4
      read('video-bitrate'), // 5
      read('audio-bitrate'), // 6
      read('hwdec-current'), // 7
      read('avsync'), // 8
      read('width'), // 9
      read('height'), // 10
      read('video-codec'), // 11
      read('demuxer-cache-duration'), // 12
    ]);

    stats.value = PlaybackStats(
      droppedVo: asInt(r[0]) ?? 0,
      droppedDecoder: asInt(r[1]) ?? 0,
      fps: asDouble(r[2]),
      containerFps: asDouble(r[3]),
      displayFps: asDouble(r[4]),
      videoBitrate: asDouble(r[5]),
      audioBitrate: asDouble(r[6]),
      hwdec: r[7],
      avsync: asDouble(r[8]),
      width: asInt(r[9]),
      height: asInt(r[10]),
      videoCodec: r[11],
      cacheDuration: asDouble(r[12]),
    );
  }
}
