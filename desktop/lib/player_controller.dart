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
      // Position events fire on every mpv frame — rebuilding the entire
      // AnimatedBuilder tree (with its BackdropFilters) at 60 Hz starves the
      // video texture of GPU. Throttle to 5 Hz; the scrubber/clock is plenty
      // smooth at that rate.
      player.stream.position.listen((_) => _emitThrottled()),
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

  DateTime _lastPositionEmit = DateTime.fromMillisecondsSinceEpoch(0);
  static const _positionEmitInterval = Duration(milliseconds: 200);

  void _emitThrottled() {
    final now = DateTime.now();
    if (now.difference(_lastPositionEmit) < _positionEmitInterval) return;
    _lastPositionEmit = now;
    notifyListeners();
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
        // Hardware decoding — videotoolbox on macOS, d3d11va on Windows,
        // vaapi/nvdec on Linux. Without this, h264/hevc 1080p+ pegs the CPU.
        await native.setProperty('hwdec', 'auto-safe');

        // — Network buffering ———————————————————————————————————————————————
        // libmpv defaults to ~1 second of read-ahead. We crank everything up;
        // whether it actually helps depends on whether the upstream allows
        // buffering ahead of playback (some hosts rate-limit to realtime).
        await native.setProperty('cache', 'yes');
        await native.setProperty('cache-secs', '60');
        await native.setProperty('demuxer-readahead-secs', '60');
        await native.setProperty('demuxer-max-bytes', '314572800'); // 300 MiB
        await native.setProperty('demuxer-max-back-bytes', '104857600'); // 100 MiB
        await native.setProperty('stream-buffer-size', '8MiB');

        await native.setProperty('network-timeout', '120');
        const lavf =
            'timeout=30000000,reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5';
        await native.setProperty('stream-lavf-o-add', lavf);
        await native.setProperty('demuxer-lavf-o-add', lavf);
        // When the upstream rate-limits to realtime, pausing for cache just
        // stutters worse — keep playing and tolerate the occasional drop.
        await native.setProperty('cache-pause', 'no');
        await native.setProperty('cache-pause-wait', '1');

        // Echo what mpv accepted, so we can tell if a setting was silently
        // rejected (some are option-only, not runtime-tunable).
        for (final p in const [
          'demuxer-readahead-secs',
          'demuxer-max-bytes',
          'cache-secs',
          'stream-buffer-size',
        ]) {
          try {
            final v = await native.getProperty(p);
            debugPrint('[player cfg] $p = $v');
          } catch (e) {
            debugPrint('[player cfg] $p — not readable ($e)');
          }
        }
      } catch (e) {
        debugPrint('[player] failed to tune mpv: $e');
      }
      _startStatsLogger(native);
    }
  }

  Timer? _statsTimer;

  /// Periodically dumps mpv's view of the world to the debug console so we
  /// can tell whether playback lag is decoder-side (hwdec failing, frames
  /// dropping) or compositor-side (mpv healthy but Flutter can't keep up).
  void _startStatsLogger(NativePlayer native) {
    _statsTimer?.cancel();
    const fields = [
      'hwdec-current',
      'width',
      'height',
      'container-fps',
      'frame-drop-count',
      'vo-delayed-frame-count',
      'video-bitrate',
      'demuxer-cache-duration',
      'paused-for-cache',
      'cache-buffering-state',
    ];
    _statsTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      // Only spew when something is actually playing.
      if (_currentIndex < 0) return;
      final parts = <String>[];
      for (final f in fields) {
        try {
          final v = await native.getProperty(f);
          if (v.toString().isNotEmpty) parts.add('$f=$v');
        } catch (_) {
          // property not available on this build — silently skip
        }
      }
      if (parts.isNotEmpty) debugPrint('[mpv stats] ${parts.join(' | ')}');
    });
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
    _statsTimer?.cancel();
    for (final s in _subs) {
      await s.cancel();
    }
    indexChanges.dispose();
    await player.dispose();
    super.dispose();
  }
}
