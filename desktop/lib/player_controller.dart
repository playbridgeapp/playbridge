import 'dart:async';
import 'package:flutter/foundation.dart';
import 'player_engine.dart';
import 'engines/mpv_engine.dart';

/// Coordinator that delegates playback to the active [PlayerEngine].
class PlayerController extends ChangeNotifier {
  PlayerController({EngineType initialEngine = EngineType.mpvInternal}) {
    _setEngine(initialEngine);
  }

  late PlayerEngine _engine;
  EngineType _currentType = EngineType.mpvInternal;

  void _setEngine(EngineType type) {
    if (type == _currentType && _hasInited) return;

    if (_hasInited) {
      _engine.removeListener(notifyListeners);
      _engine.dispose();
    }

    _currentType = type;
    switch (type) {
      case EngineType.mpvInternal:
        _engine = MpvEngine();
        (_engine as MpvEngine).onCompleted = _onCompleted;
    }

    _engine.addListener(notifyListeners);
    _hasInited = true;
    notifyListeners();
  }

  bool _hasInited = false;
  EngineType get engineType => _currentType;
  PlayerEngine get engine => _engine;

  Future<void> switchEngine(EngineType type) async {
    if (type == _currentType) return;

    // 1. Capture current state
    final wasPlaying = state == 'playing';
    final currentPos = Duration(milliseconds: _engine.positionMs);
    final currentItem = _currentIndex >= 0 && _currentIndex < _queue.length
        ? _queue[_currentIndex]
        : null;

    debugPrint(
        '[player] switching engine: $_currentType -> $type at ${currentPos.inSeconds}s');

    // 2. Tear down and swap
    _setEngine(type);

    // 3. Restore state in the new engine
    if (currentItem != null) {
      await _engine.open(currentItem);

      // Give the new engine a moment to initialize before seeking.
      // Some engines need a valid duration before they can seek.
      int retries = 0;
      while (_engine.durationMs <= 0 && retries < 20) {
        await Future.delayed(const Duration(milliseconds: 100));
        retries++;
      }

      if (currentPos > Duration.zero) {
        await _engine.seek(currentPos);
      }

      if (!wasPlaying) {
        await _engine.pause();
      }
    }
  }

  /// Fires whenever a new play session is explicitly requested (via playUrl or
  /// playPlaylist). The UI uses this to force focus/fullscreen.
  final ValueNotifier<int> playRequests = ValueNotifier<int>(0);

  /// Fires whenever the active playlist index changes (jump, next, prev,
  /// auto-advance). The server listens to this to broadcast playlist_status.
  final ValueNotifier<int> indexChanges = ValueNotifier<int>(-1);

  /// Bumped whenever the queue *contents* change without the index moving
  /// (queue_add, stop). The server also listens to this for playlist_status.
  final ValueNotifier<int> queueChanges = ValueNotifier<int>(0);

  final List<QueueItem> _queue = [];
  int _currentIndex = -1;

  String? get currentTitle =>
      _currentIndex >= 0 && _currentIndex < _queue.length
          ? _queue[_currentIndex].title
          : null;

  int get currentIndex => _currentIndex;
  List<QueueItem> get queue => List.unmodifiable(_queue);

  bool get hasPrevious => _currentIndex > 0;
  bool get hasNext => _currentIndex >= 0 && _currentIndex < _queue.length - 1;

  String get state {
    if (_currentIndex < 0) return 'idle';
    return _engine.state;
  }

  int get positionMs => _engine.positionMs;
  int get durationMs => _engine.durationMs;
  double get volume => _engine.volume;

  dynamic get tracks => _engine.tracks;
  dynamic get track => _engine.track;
  Future<void> setAudioTrack(dynamic t) => _engine.setAudioTrack(t);
  Future<void> setSubtitleTrack(dynamic t) => _engine.setSubtitleTrack(t);

  // Engine-agnostic track surface for the phone remote (`tracks` message +
  // `audio_track:`/`sub_track:` control commands).
  List<TrackInfo> get audioTrackInfos => _engine.audioTrackInfos;
  List<TrackInfo> get subtitleTrackInfos => _engine.subtitleTrackInfos;
  Future<void> selectAudioTrackById(String id) =>
      _engine.selectAudioTrackById(id);
  Future<void> selectSubtitleTrackById(String id) =>
      _engine.selectSubtitleTrackById(id);

  Future<void> playUrl(
    String url, {
    String? title,
    Map<String, String>? headers,
    List<String>? subtitles,
    bool isRemote = false,
  }) async {
    await playPlaylist(
      [
        QueueItem(
          url: url,
          title: title ?? url,
          headers: headers,
          subtitles: subtitles,
        ),
      ],
      0,
      isRemote: isRemote,
    );
  }

  /// Play a single [QueueItem] as a one-item playlist (replaces the queue).
  Future<void> playItem(QueueItem item, {bool isRemote = false}) =>
      playPlaylist([item], 0, isRemote: isRemote);

  Future<void> playPlaylist(
    List<QueueItem> items,
    int startIndex, {
    bool isRemote = false,
  }) async {
    if (items.isEmpty) return;
    _queue
      ..clear()
      ..addAll(items);
    _setIndex(startIndex.clamp(0, items.length - 1));
    if (isRemote) {
      playRequests.value++;
    }
    await _engine.openPlaylist(_queue, _currentIndex);
    unawaited(_applyStartPosition());
  }

  /// Append an item to the live queue (`queue_add`). If nothing is playing,
  /// starts the item directly (preserves the previous desktop behavior;
  /// Android instead buffers idle queue_adds until the next session).
  Future<void> queueAdd(QueueItem item, {bool isRemote = false}) async {
    if (_currentIndex < 0 || _queue.isEmpty) {
      await playPlaylist([item], 0, isRemote: isRemote);
      return;
    }
    _queue.add(item);
    queueChanges.value++;
    notifyListeners();
  }

  Future<void> jumpTo(int index) async {
    if (index < 0 || index >= _queue.length) return;
    _setIndex(index);
    await _engine.openPlaylist(_queue, _currentIndex);
    unawaited(_applyStartPosition());
  }

  Future<void> next() async {
    if (!hasNext) return;
    await jumpTo(_currentIndex + 1);
  }

  Future<void> previous() async {
    if (!hasPrevious) return;
    await jumpTo(_currentIndex - 1);
  }

  void _onCompleted() {
    if (hasNext) {
      unawaited(next());
    }
  }

  /// Honour the current item's `start_position_ms` (phone resume store).
  /// Consumes the value so replaying the same item starts from zero. Waits for
  /// the engine to report a duration first — mpv can't seek before that — and
  /// skips the seek when the resume point is past the end (stale store entry).
  Future<void> _applyStartPosition() async {
    final item = _currentIndex >= 0 && _currentIndex < _queue.length
        ? _queue[_currentIndex]
        : null;
    final startMs = item?.startPositionMs;
    if (item == null || startMs == null || startMs <= 0) return;
    item.startPositionMs = null; // consume

    var retries = 0;
    while (_engine.durationMs <= 0 && retries < 20) {
      await Future.delayed(const Duration(milliseconds: 100));
      retries++;
    }
    // Only resume if the item is still the active one and the position is sane.
    if (_currentIndex < 0 ||
        _currentIndex >= _queue.length ||
        !identical(_queue[_currentIndex], item)) {
      return;
    }
    final durMs = _engine.durationMs;
    if (durMs > 0 && startMs >= durMs) return;
    debugPrint('[player] resuming at ${startMs}ms');
    await _engine.seek(Duration(milliseconds: startMs));
  }

  void _setIndex(int i) {
    _currentIndex = i;
    indexChanges.value = i;
  }

  Future<void> resume() => _engine.resume();
  Future<void> pause() => _engine.pause();
  Future<void> seek(Duration position) => _engine.seek(position);
  Future<void> setVolume(double volume) => _engine.setVolume(volume);
  Future<void> stop() async {
    await _engine.stop();
    _queue.clear();
    _setIndex(-1);
    queueChanges.value++;
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    indexChanges.dispose();
    queueChanges.dispose();
    playRequests.dispose();
    await _engine.dispose();
    super.dispose();
  }
}
