import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'player_engine.dart';
import 'engines/mpv_engine.dart';
import 'engines/external_engine.dart';

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
      case EngineType.mpvExternal:
        _engine = ExternalEngine(
          command: 'mpv',
          argsBuilder: (item, m3u, startIdx) => [
            if (m3u != null) ...[
              m3u.path,
              '--playlist-start=$startIdx',
            ] else
              item.url,
            '--fs',
            '--force-window=yes',
            if (item.title != null) '--title=${item.title}',
            if (item.headers != null)
              '--http-header-fields=${item.headers!.entries.map((e) => "${e.key}: ${e.value}").join(",")}',
          ],
        );
        (_engine as ExternalEngine).onCompleted = _onCompleted;
      case EngineType.vlcExternal:
        final vlcCmd = Platform.isMacOS ? '/Applications/VLC.app/Contents/MacOS/VLC' : 'vlc';
        _engine = ExternalEngine(
          command: vlcCmd,
          argsBuilder: (item, m3u, startIdx) => [
            if (m3u != null) ...[
              m3u.path,
              '--playlist-index=$startIdx',
            ] else
              item.url,
            '--fullscreen',
            '--play-and-exit',
            if (item.headers != null)
              '--http-user-agent=${item.headers!['User-Agent'] ?? 'PlayBridge'}',
          ],
        );
        (_engine as ExternalEngine).onCompleted = _onCompleted;
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

    debugPrint('[player] switching engine: $_currentType -> $type at ${currentPos.inSeconds}s');

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

  dynamic get tracks => _engine.tracks;
  dynamic get track => _engine.track;
  Future<void> setAudioTrack(dynamic t) => _engine.setAudioTrack(t);
  Future<void> setSubtitleTrack(dynamic t) => _engine.setSubtitleTrack(t);

  Future<void> playUrl(
    String url, {
    String? title,
    Map<String, String>? headers,
    List<String>? subtitles,
    bool isRemote = false,
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
    if (isRemote) {
      playRequests.value++;
    }
    await _engine.openPlaylist(_queue, _currentIndex);
  }

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
  }

  Future<void> jumpTo(int index) async {
    if (index < 0 || index >= _queue.length) return;
    _setIndex(index);
    await _engine.openPlaylist(_queue, _currentIndex);
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

  void _setIndex(int i) {
    _currentIndex = i;
    indexChanges.value = i;
  }

  Future<void> resume() => _engine.resume();
  Future<void> pause() => _engine.pause();
  Future<void> seek(Duration position) => _engine.seek(position);
  Future<void> stop() async {
    await _engine.stop();
    _queue.clear();
    _setIndex(-1);
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    indexChanges.dispose();
    playRequests.dispose();
    await _engine.dispose();
    super.dispose();
  }
}
