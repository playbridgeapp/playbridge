import 'dart:async';
import 'package:flutter/foundation.dart';
import 'player_engine.dart';
import 'engines/mpv_engine.dart';
import 'pairing_store.dart';
import 'engines/playback_request_preparer.dart';
import 'stream_proxy_server.dart';

/// Coordinator that delegates playback to the active [PlayerEngine].
class PlayerController extends ChangeNotifier {
  /// [engineForTest] injects a fake engine (unit tests); production leaves it null.
  PlayerController({
    EngineType initialEngine = EngineType.mpvInternal,
    PlayerEngine? engineForTest,
    bool preselectHlsQuality = false,
    this.store,
  }) : _preselectHlsQuality = preselectHlsQuality {
    if (engineForTest != null) {
      _currentType = initialEngine;
      _engine = engineForTest;
      if (_engine is MpvEngine) {
        (_engine as MpvEngine).onCompleted = _onCompleted;
        (_engine as MpvEngine).onError = _onError;
      }
      _engine.addListener(notifyListeners);
      _hasInited = true;
    } else {
      _setEngine(initialEngine);
    }
  }

  final PairingStore? store;
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
        _engine = MpvEngine(preselectHlsQuality: _preselectHlsQuality);
        (_engine as MpvEngine).onCompleted = _onCompleted;
        (_engine as MpvEngine).onError = _onError;
    }

    _engine.addListener(notifyListeners);
    _hasInited = true;
    notifyListeners();
  }

  /// Test hook: simulate engine end-of-file.
  @visibleForTesting
  void notifyCompletedForTest() => _onCompleted();

  bool _hasInited = false;
  bool _preselectHlsQuality;
  EngineType get engineType => _currentType;
  PlayerEngine get engine => _engine;

  bool get preselectHlsQuality => _preselectHlsQuality;

  /// Applies to the next item opened; current playback is left untouched.
  void setPreselectHlsQuality(bool value) {
    if (_preselectHlsQuality == value) return;
    _preselectHlsQuality = value;
    final engine = _engine;
    if (engine is MpvEngine) engine.preselectHlsQuality = value;
    notifyListeners();
  }

  Future<void> switchEngine(EngineType type) async {
    if (type == _currentType) return;
    await _waitForProxyToggle();

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

  /// True while a new item is opening — [PlaybackSurface] paints black over the
  /// VO so a frozen last-frame of the previous item cannot flash (esp. after
  /// the window was unfocused). Lifted only after the engine has real media.
  bool _opening = false;
  bool get isOpening => _opening;
  bool _proxyToggleInProgress = false;
  bool get proxyToggleInProgress => _proxyToggleInProgress;
  bool _videoOutputChanging = false;
  bool get videoOutputChanging => _videoOutputChanging;
  Future<void>? _proxyToggleFuture;
  int _proxyToggleGeneration = 0;

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
  bool get hardwareVideoOutput => store?.hardwareVideoOutput ?? true;

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
    String? contentType,
    String? playlistBody,
    String? audioUrl,
    bool isRemote = false,
  }) async {
    await playPlaylist(
      [
        QueueItem(
          url: url,
          title: title ?? url,
          headers: headers,
          subtitles: subtitles,
          contentType: contentType,
          playlistBody: playlistBody,
          audioUrl: audioUrl,
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
    await _waitForProxyToggle();
    if (items.isEmpty) return;
    // Mask before queue/reveal so stop→unfocus→play B never flashes video A.
    // Do not disable the mpv video track — that left a permanent black picture.
    _opening = true;
    notifyListeners();

    final mode = store?.streamProxyMode ?? StreamProxyMode.off;
    final prepared = await Future.wait(
      items.map((item) => PlaybackRequestPreparer.prepare(item, mode)),
    );

    _queue
      ..clear()
      ..addAll(prepared);
    _setIndex(startIndex.clamp(0, prepared.length - 1));
    if (isRemote) {
      playRequests.value++;
    }
    notifyListeners();
    try {
      await _engine.openPlaylist(_queue, _currentIndex);
      unawaited(_applyStartPosition());
      // Keep the mask until the demuxer has real media so the first paint is B.
      var retries = 0;
      while (_engine.durationMs <= 0 && retries < 40) {
        await Future.delayed(const Duration(milliseconds: 50));
        retries++;
      }
    } finally {
      _opening = false;
      notifyListeners();
    }
  }

  /// Append an item to the live queue (`queue_add`). If nothing is playing,
  /// starts the item directly (preserves the previous desktop behavior;
  /// Android instead buffers idle queue_adds until the next session).
  Future<void> queueAdd(QueueItem item, {bool isRemote = false}) async {
    await _waitForProxyToggle();
    if (_currentIndex < 0 || _queue.isEmpty) {
      await playPlaylist([item], 0, isRemote: isRemote);
      return;
    }
    final mode = store?.streamProxyMode ?? StreamProxyMode.off;
    final prepared = await PlaybackRequestPreparer.prepare(item, mode);
    _queue.add(prepared);
    queueChanges.value++;
    notifyListeners();
  }

  Future<void> jumpTo(int index) async {
    await _waitForProxyToggle();
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
    // media_kit can emit completed=true on demuxer EOF while still opening /
    // buffering a progressive HTTP URL (token CDNs that stall). Tearing down
    // immediately looks like "never waits for buffer". Only stop after real
    // playback progressed; true end-of-file is always past that threshold.
    final pos = positionMs;
    final dur = durationMs;
    final meaningfullyPlayed = pos >= 1500 || (dur > 0 && pos >= 500);
    if (!meaningfullyPlayed) {
      debugPrint(
          '[player] ignoring early completed (pos=${pos}ms dur=${dur}ms) — still buffering?');
      return;
    }
    if (hasNext) {
      unawaited(next());
      return;
    }
    // Last item finished — clear the session. Leaving the queue loaded left
    // mpv sitting on the final frame ("paused at end") and the UI never left
    // the video view (main.dart only hides it when the queue empties).
    debugPrint('[player] playlist finished — stopping');
    unawaited(stop());
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

  /// Recreates libmpv so media_kit can construct a new video output using the
  /// selected software or hardware renderer. The renderer choice is immutable
  /// after [VideoController] creation, so changing a property on the live
  /// player is not sufficient.
  Future<void> setHardwareVideoOutput(bool enabled) async {
    if (_videoOutputChanging || hardwareVideoOutput == enabled) return;
    await _waitForProxyToggle();
    await store?.setHardwareVideoOutput(enabled);

    final oldEngine = _engine;
    if (oldEngine is! MpvEngine) {
      notifyListeners();
      return;
    }

    _videoOutputChanging = true;
    _opening = true;
    notifyListeners();

    final itemIndex = _currentIndex;
    final currentPosition = oldEngine.positionMs;
    final currentVolume = oldEngine.volume;
    final wasPlaying = state == 'playing' || state == 'buffering';

    try {
      oldEngine.removeListener(notifyListeners);
      await oldEngine.dispose();

      _hasInited = false;
      _setEngine(_currentType);

      if (itemIndex >= 0 && itemIndex < _queue.length) {
        await _engine.openPlaylist(
          _queue,
          itemIndex,
          play: wasPlaying,
        );
        await _engine.setVolume(currentVolume);

        var retries = 0;
        while (_engine.durationMs <= 0 && retries < 40) {
          await Future.delayed(const Duration(milliseconds: 50));
          retries++;
        }
        if (currentPosition > 0 && _engine.durationMs > currentPosition) {
          await _engine.seek(Duration(milliseconds: currentPosition));
        }
        if (!wasPlaying) await _engine.pause();
      }
    } catch (error) {
      debugPrint('[player] video output switch failed (${error.runtimeType})');
    } finally {
      _opening = false;
      _videoOutputChanging = false;
      notifyListeners();
    }
  }

  Future<void> stop() async {
    await _waitForProxyToggle();
    await _engine.stop();
    _queue.clear();
    _setIndex(-1);
    _opening = false;
    queueChanges.value++;
    notifyListeners();
  }

  void _onError(String errorMsg) {
    if (_proxyToggleInProgress) return;
    if (store?.streamProxyMode == StreamProxyMode.auto) {
      final currentItem = _currentIndex >= 0 && _currentIndex < _queue.length
          ? _queue[_currentIndex]
          : null;
      if (currentItem != null) {
        final url = currentItem.url;
        final isAlreadyProxied = url.startsWith('http://127.0.0.1') ||
            url.startsWith('http://localhost');
        final isDataOrFile = url.startsWith('data:') ||
            url.startsWith('file:') ||
            (url.startsWith('/') && url.toLowerCase().endsWith('.m3u8'));
        if (!isAlreadyProxied &&
            !isDataOrFile &&
            url.toLowerCase().contains('.m3u8')) {
          // Direct HLS playback failed. Mark the host to route through local proxy on retry.
          PlaybackRequestPreparer.markHostFailed(url);
          debugPrint(
              '[player-controller] Direct HLS playback failed. Retrying via stream proxy...');

          // Re-prepare the item forcing proxy mode
          unawaited(_retryThroughProxy(currentItem));
        }
      }
    }
  }

  /// True when the current item's URL is being routed through the loopback
  /// stream proxy rather than played directly.
  bool get isCurrentItemProxied {
    if (_currentIndex < 0 || _currentIndex >= _queue.length) return false;
    final url = _queue[_currentIndex].url;
    return url.contains('127.0.0.1') && url.contains('/s/');
  }

  Future<void> _retryThroughProxy(QueueItem item) async {
    final preparedItem = await PlaybackRequestPreparer.prepare(
      item,
      StreamProxyMode.always,
    );
    if (_currentIndex < 0 || _currentIndex >= _queue.length) return;
    _queue[_currentIndex] = preparedItem;
    await _engine.openPlaylist(_queue, _currentIndex);
  }

  /// Seamlessly switches the current item between direct ↔ proxied playback.
  /// Saves the current position and re-opens the stream so the transition is
  /// invisible to the user.
  Future<bool> toggleProxy() {
    if (_proxyToggleInProgress ||
        _currentIndex < 0 ||
        _currentIndex >= _queue.length) {
      return Future.value(false);
    }

    final generation = ++_proxyToggleGeneration;
    final operation = _runProxyToggle();
    _proxyToggleFuture = operation.then<void>(
      (_) {
        if (_proxyToggleGeneration == generation) _proxyToggleFuture = null;
      },
      onError: (Object error, StackTrace stack) {
        if (_proxyToggleGeneration == generation) _proxyToggleFuture = null;
      },
    );
    return operation;
  }

  Future<void> _waitForProxyToggle() async {
    final pending = _proxyToggleFuture;
    if (pending != null) await pending;
  }

  Future<bool> _runProxyToggle() async {
    _proxyToggleInProgress = true;
    notifyListeners();

    try {
      return await _toggleProxyInternal();
    } finally {
      _proxyToggleInProgress = false;
      notifyListeners();
    }
  }

  Future<bool> _toggleProxyInternal() async {
    final item = _queue[_currentIndex];
    final itemIndex = _currentIndex;
    final currentPos = _engine.positionMs;
    final wasPlaying = state == 'playing' || state == 'buffering';

    QueueItem replacement;

    if (isCurrentItemProxied) {
      // ── Proxied → Direct ──
      final directUrl = item.originalUrl;
      if (directUrl == null) {
        debugPrint('[player] cannot toggle to direct: originalUrl is null');
        return false;
      }
      replacement = QueueItem(
        url: directUrl,
        title: item.title,
        headers: item.originalHeaders,
        subtitles: item.subtitles,
        startPositionMs: currentPos > 0 ? currentPos : null,
        bingeGroup: item.bingeGroup,
        season: item.season,
        episode: item.episode,
        imdbId: item.imdbId,
        backdropUrl: item.backdropUrl,
        posterUrl: item.posterUrl,
        logoUrl: item.logoUrl,
        overview: item.overview,
        year: item.year,
        rating: item.rating,
        runtime: item.runtime,
        episodeTitle: item.episodeTitle,
        contentType: item.contentType,
        // Keep demuxed LL-HLS handoff across proxy toggles.
        playlistBody: item.playlistBody,
        audioUrl: item.audioUrl,
      );
      debugPrint('[player] toggling to direct playback at ${currentPos}ms');
    } else {
      // ── Direct → Proxied ──
      final headers = item.headers ?? {};
      late final String loopbackUrl;
      try {
        loopbackUrl =
            await StreamProxyServer.instance.registerSession(item.url, headers);
      } catch (error) {
        debugPrint(
            '[player] proxy toggle unavailable (${error.runtimeType}); keeping direct playback');
        return false;
      }
      // Proxy the video media playlist. Companion audio (if any) still hits the
      // CDN with session= auth — playlistBody stays for re-open strategy.
      String? proxiedAudio = item.audioUrl;
      if (proxiedAudio != null && proxiedAudio.isNotEmpty) {
        try {
          proxiedAudio = await StreamProxyServer.instance
              .registerSession(proxiedAudio, headers);
        } catch (_) {
          // Keep direct audio URL; session= often works without proxy.
        }
      }
      replacement = QueueItem(
        url: loopbackUrl,
        title: item.title,
        headers: null,
        subtitles: item.subtitles,
        startPositionMs: currentPos > 0 ? currentPos : null,
        originalUrl: item.url,
        originalHeaders: headers,
        bingeGroup: item.bingeGroup,
        season: item.season,
        episode: item.episode,
        imdbId: item.imdbId,
        backdropUrl: item.backdropUrl,
        posterUrl: item.posterUrl,
        logoUrl: item.logoUrl,
        overview: item.overview,
        year: item.year,
        rating: item.rating,
        runtime: item.runtime,
        episodeTitle: item.episodeTitle,
        contentType: item.contentType,
        playlistBody: item.playlistBody,
        audioUrl: proxiedAudio,
      );
      debugPrint('[player] toggling to proxied playback at ${currentPos}ms');
    }

    _queue[_currentIndex] = replacement;
    notifyListeners();

    try {
      await _engine.openPlaylist(
        _queue,
        itemIndex,
        play: wasPlaying,
      );
    } catch (error) {
      if (_currentIndex == itemIndex &&
          identical(_queue[itemIndex], replacement)) {
        _queue[itemIndex] = item;
        notifyListeners();
        try {
          await _engine.openPlaylist(
            _queue,
            itemIndex,
            play: wasPlaying,
          );
          if (currentPos > 0) {
            await _engine.seek(Duration(milliseconds: currentPos));
          }
        } catch (restoreError) {
          debugPrint(
              '[player] proxy toggle rollback failed (${restoreError.runtimeType})');
        }
      }
      debugPrint(
          '[player] proxy toggle failed (${error.runtimeType}); restored previous route where possible');
      return false;
    }
    unawaited(_applyStartPosition());

    // Wait for the engine to initialise before completing the route switch.
    var retries = 0;
    while (_engine.durationMs <= 0 && retries < 40) {
      await Future.delayed(const Duration(milliseconds: 50));
      retries++;
    }

    return true;
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
