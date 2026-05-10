import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';

typedef QueueItem = ({
  String url,
  String title,
  Map<String, String>? headers,
  List<String>? subtitles,
});

/// Thin wrapper over media_kit's [Player] that exposes only what the
/// receiver needs and broadcasts a coarse "state" string for status messages.
class PlayerController extends ChangeNotifier {
  PlayerController() {
    _configureMpv();
    _subs.addAll([
      player.stream.playing.listen((playing) {
        debugPrint('[player] playing=$playing');
        _emit();
      }),
      player.stream.position.listen((_) => _emit()),
      player.stream.duration.listen((_) => _emit()),
      player.stream.buffering.listen((b) {
        debugPrint('[player] buffering=$b');
        _emit();
      }),
      player.stream.completed.listen((done) {
        debugPrint('[player] completed=$done');
        if (done) _onCompleted();
        _emit();
      }),
      player.stream.tracks.listen((_) => _emit()),
      player.stream.track.listen((_) => _emit()),
      player.stream.error.listen((e) {
        debugPrint('[player] error: $e');
        _lastError = e;
        _emit();
      }),
    ]);
  }

  Tracks get tracks => player.state.tracks;
  Track get track => player.state.track;

  Future<void> setAudioTrack(AudioTrack t) => player.setAudioTrack(t);
  Future<void> setSubtitleTrack(SubtitleTrack t) => player.setSubtitleTrack(t);

  final Player player = Player();
  final List<StreamSubscription> _subs = [];

  /// Tune libmpv for slow upstream resolvers (e.g. PlayBridge Hub's
  /// `/api/play/series/...` endpoint that performs an addon lookup before
  /// returning a redirect). Defaults are too aggressive for that flow.
  Future<void> _configureMpv() async {
    final native = player.platform;
    if (native is NativePlayer) {
      try {
        // Overall network operation timeout (default 60s — be explicit).
        await native.setProperty('network-timeout', '120');
        // ffmpeg/lavf options applied to both stream and demuxer layers:
        //   timeout    — TCP/TLS connect & I/O timeout, in microseconds
        //   reconnect* — auto-resume when the upstream drops mid-stream
        const lavf =
            'timeout=30000000,reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5';
        await native.setProperty('stream-lavf-o-add', lavf);
        await native.setProperty('demuxer-lavf-o-add', lavf);
        // Don't auto-pause on minor cache underruns — try to keep playing.
        await native.setProperty('cache-pause', 'no');
        // Don't pause when buffer fills up momentarily.
        await native.setProperty('cache-pause-wait', '1');
      } catch (e) {
        debugPrint('[player] failed to tune mpv: $e');
      }
    }
  }

  /// Fires whenever the active playlist index changes (jump, next, prev,
  /// auto-advance). The server listens to this to broadcast playlist_status.
  final ValueNotifier<int> indexChanges = ValueNotifier<int>(-1);

  final List<QueueItem> _queue = [];
  int _currentIndex = -1;
  String? _lastError;

  String? get currentTitle =>
      _currentIndex >= 0 && _currentIndex < _queue.length
          ? _queue[_currentIndex].title
          : null;

  int get currentIndex => _currentIndex;
  List<QueueItem> get queue => List.unmodifiable(_queue);
  String? get lastError => _lastError;

  bool get hasPrevious => _currentIndex > 0;
  bool get hasNext => _currentIndex >= 0 && _currentIndex < _queue.length - 1;

  /// State string matching what the TV sends today: idle | buffering | playing | paused | ended
  String get state {
    if (player.state.completed) return 'ended';
    if (player.state.buffering) return 'buffering';
    if (player.state.playing) return 'playing';
    if (_currentIndex < 0) return 'idle';
    return 'paused';
  }

  int get positionMs => player.state.position.inMilliseconds;
  int get durationMs => player.state.duration.inMilliseconds;

  Future<void> playUrl(
    String url, {
    String? title,
    Map<String, String>? headers,
    List<String>? subtitles,
  }) async {
    _queue
      ..clear()
      ..add((
        url: url,
        title: title ?? url,
        headers: headers,
        subtitles: subtitles,
      ));
    _setIndex(0);
    await _open(_queue[0]);
  }

  Future<void> playPlaylist(List<QueueItem> items, int startIndex) async {
    if (items.isEmpty) return;
    _queue
      ..clear()
      ..addAll(items);
    _setIndex(startIndex.clamp(0, items.length - 1));
    await _open(_queue[_currentIndex]);
  }

  Future<void> jumpTo(int index) async {
    if (index < 0 || index >= _queue.length) return;
    _setIndex(index);
    await _open(_queue[index]);
  }

  Future<void> next() async {
    if (!hasNext) return;
    await jumpTo(_currentIndex + 1);
  }

  Future<void> previous() async {
    if (!hasPrevious) return;
    await jumpTo(_currentIndex - 1);
  }

  Future<void> _open(QueueItem item) async {
    _lastError = null;
    final media = Media(item.url, httpHeaders: item.headers);
    // Pass `play: true` explicitly so playback always starts immediately
    // regardless of whatever default media_kit ships on this platform.
    await player.open(media, play: true);

    // Attach external subtitle tracks (if any) without auto-selecting them —
    // user picks from the subtitle menu. Uses mpv's `sub-add` directly so
    // multiple URLs accumulate as additional subtitle tracks.
    final subs = item.subtitles ?? const <String>[];
    if (subs.isNotEmpty) {
      final native = player.platform;
      if (native is NativePlayer) {
        for (var i = 0; i < subs.length; i++) {
          try {
            await native.command(['sub-add', subs[i], 'auto', 'External ${i + 1}']);
          } catch (e) {
            debugPrint('[player] sub-add failed for ${subs[i]}: $e');
          }
        }
      }
    }

    // Belt-and-suspenders: some upstreams arrive paused after open() returns;
    // an explicit play() once subtitles are attached guarantees we're rolling.
    await player.play();
    _emit();
  }

  void _onCompleted() {
    if (hasNext) {
      // Fire-and-forget — `_open` is async but we don't want to block the
      // stream listener. Errors surface via the player's error stream.
      unawaited(next());
    }
  }

  void _setIndex(int i) {
    _currentIndex = i;
    indexChanges.value = i;
  }

  Future<void> resume() => player.play();
  Future<void> pause() => player.pause();
  Future<void> seek(Duration position) => player.seek(position);
  Future<void> stop() async {
    await player.stop();
    _queue.clear();
    _setIndex(-1);
    _emit();
  }

  void _emit() {
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    for (final s in _subs) {
      await s.cancel();
    }
    indexChanges.dispose();
    await player.dispose();
    super.dispose();
  }
}
