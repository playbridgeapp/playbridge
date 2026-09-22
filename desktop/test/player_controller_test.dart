import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/media_kind.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';

/// Minimal engine for [PlayerController] unit tests (no media_kit / mpv).
class _FakeEngine extends PlayerEngine {
  String _state = 'idle';
  int openCount = 0;
  int stopCount = 0;
  int lastOpenIndex = -1;
  int positionMsValue = 0;
  int? lastSeekMs;
  int durationMsValue = 1000;
  bool lastOpenPlay = true;
  int failuresRemaining = 0;
  Duration openDelay = Duration.zero;

  void setStateForTest(String value) => _state = value;

  @override
  String get state => _state;
  @override
  int get positionMs => positionMsValue;
  @override
  int get durationMs => durationMsValue;
  @override
  dynamic get tracks => null;
  @override
  dynamic get track => null;

  @override
  Future<void> setAudioTrack(dynamic t) async {}
  @override
  Future<void> setSubtitleTrack(dynamic t) async {}

  @override
  Future<void> open(QueueItem item) async {
    if (openDelay > Duration.zero) await Future<void>.delayed(openDelay);
    openCount++;
    lastOpenPlay = true;
    if (failuresRemaining > 0) {
      failuresRemaining--;
      throw StateError('open failed');
    }
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> openPlaylist(
    List<QueueItem> items,
    int startIndex, {
    bool play = true,
  }) async {
    if (openDelay > Duration.zero) await Future<void>.delayed(openDelay);
    openCount++;
    lastOpenIndex = startIndex;
    lastOpenPlay = play;
    if (failuresRemaining > 0) {
      failuresRemaining--;
      throw StateError('open failed');
    }
    _state = play ? 'playing' : 'paused';
    notifyListeners();
  }

  @override
  Future<void> resume() async {
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> pause() async {
    _state = 'paused';
    notifyListeners();
  }

  @override
  Future<void> seek(Duration position) async {
    lastSeekMs = position.inMilliseconds;
  }

  @override
  Future<void> stop() async {
    stopCount++;
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    super.dispose();
  }
}

void main() {
  setUp(() async {
    await StreamProxyServer.instance.stop();
  });

  tearDown(() async {
    await StreamProxyServer.instance.stop();
  });

  QueueItem item(int n) => QueueItem(url: 'https://x/$n.mp4', title: 'Ep$n');

  test('mixed queue switches between timed media and static image', () async {
    final engine = _FakeEngine();
    final controller = PlayerController(engineForTest: engine);
    final mixed = [
      item(1),
      QueueItem(
        url: 'https://x/photo.jpg',
        title: 'Photo',
        declaredMediaKind: 'image',
      ),
      QueueItem(
        url: 'https://x/song.mp3',
        title: 'Song',
        declaredMediaKind: 'audio',
      ),
    ];

    await controller.playPlaylist(mixed, 0);
    expect(controller.currentMediaKind, MediaKind.video);
    expect(engine.openCount, 1);

    await controller.next();
    expect(controller.currentMediaKind, MediaKind.image);
    expect(controller.state, 'paused');
    expect(engine.stopCount, 1);

    controller.zoomImage(2);
    controller.panImage(40, -20);
    controller.rotateImage(90);
    expect(controller.imageScale, 2);
    expect(controller.imageOffsetX, 40);
    expect(controller.imageOffsetY, -20);
    expect(controller.imageRotationDegrees, 90);

    controller.resetImageTransform();
    controller.setImageViewportSize(1000, 600);
    controller.setImageTransformAnchor(0.75, 0.5);
    controller.zoomImage(2);
    expect(controller.imageOffsetX, -250);
    expect(controller.imageOffsetY, 0);

    await controller.next();
    expect(controller.currentMediaKind, MediaKind.audio);
    expect(controller.imageScale, 1);
    expect(controller.imageOffsetX, 0);
    expect(controller.imageOffsetY, 0);
    expect(controller.imageRotationDegrees, 0);
    expect(controller.state, 'playing');
    expect(engine.openCount, 2);
  });

  test('shared queue keeps stable ids across add, move, remove, and jump',
      () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1), item(2)], 0);
    final playbackId = controller.playbackId;
    final firstId = controller.queueItemIds[0];
    final secondId = controller.queueItemIds[1];
    final initialRevision = controller.queueRevision;

    await controller.queueAddAll([item(3), item(4)]);
    expect(controller.playbackId, playbackId);
    expect(controller.queueItemIds.take(2), [firstId, secondId]);
    expect(controller.queueRevision, initialRevision + 1);

    expect(await controller.moveQueueItem(secondId, null), isTrue);
    expect(controller.currentItemId, firstId);
    expect(controller.queueItemIds.last, secondId);

    expect(await controller.jumpToItem(secondId), isTrue);
    expect(controller.currentItemId, secondId);
    expect(controller.queueRevision, initialRevision + 3);
    expect(await controller.removeQueueItems({secondId}), isTrue);
    expect(controller.currentItemId, isNot(secondId));
    expect(controller.queueItemIds, isNot(contains(secondId)));
  });

  test('guarded index jump advances the queue revision exactly once', () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1), item(2)], 0);
    final playbackId = controller.playbackId;
    final revision = controller.queueRevision;

    expect(
      await controller.jumpToGuarded(1, ifPlaybackId: playbackId),
      isTrue,
    );

    expect(controller.currentIndex, 1);
    expect(controller.queueRevision, revision + 1);
  });

  test('queue append rejects batches larger than 50 without mutation',
      () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1)], 0);
    final revision = controller.queueRevision;

    expect(
      await controller.queueAddAll(
        List.generate(51, (index) => item(index + 2)),
      ),
      isFalse,
    );

    expect(controller.queue.length, 1);
    expect(controller.queueRevision, revision);
  });

  test('queue append rejects a resulting queue larger than 200', () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist(List.generate(200, item), 0);
    final revision = controller.queueRevision;

    expect(await controller.queueAddAll([item(201)]), isFalse);

    expect(controller.queue.length, 200);
    expect(controller.queueRevision, revision);
  });

  test(
      'playlist replacement rejects more than 200 items without changing playback',
      () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1)], 0);
    final playbackId = controller.playbackId;
    final revision = controller.queueRevision;

    await expectLater(
      controller.playPlaylist(List.generate(201, (index) => item(index)), 0),
      throwsArgumentError,
    );

    expect(controller.playbackId, playbackId);
    expect(controller.queue.length, 1);
    expect(controller.queueRevision, revision);
  });

  test('batch removal and move-before preserve the active item identity',
      () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1), item(2), item(3), item(4)], 1);
    final activeId = controller.currentItemId!;
    final firstId = controller.queueItemIds.first;
    final lastId = controller.queueItemIds.last;

    expect(await controller.moveQueueItem(lastId, firstId), isTrue);
    expect(controller.queueItemIds.first, lastId);
    expect(controller.currentItemId, activeId);

    expect(await controller.removeQueueItems({firstId, lastId}), isTrue);
    expect(controller.queueItemIds, isNot(contains(firstId)));
    expect(controller.queueItemIds, isNot(contains(lastId)));
    expect(controller.currentItemId, activeId);
  });

  test('new playlist replaces playback identity and stop clears it', () async {
    final controller = PlayerController(engineForTest: _FakeEngine());
    await controller.playPlaylist([item(1)], 0);
    final firstPlaybackId = controller.playbackId;
    await controller.playPlaylist([item(2)], 0);
    expect(controller.playbackId, isNot(firstPlaybackId));
    expect(controller.currentItemId, isNotNull);

    await controller.stop();
    expect(controller.playbackId, isNull);
    expect(controller.currentItemId, isNull);
    expect(controller.queueItemIds, isEmpty);
  });

  test('HLS preselection preference defaults off and can be changed', () {
    final c = PlayerController(engineForTest: _FakeEngine());

    expect(c.preselectHlsQuality, isFalse);
    c.setPreselectHlsQuality(true);
    expect(c.preselectHlsQuality, isTrue);
  });

  test('completion with next item advances the queue', () async {
    final engine = _FakeEngine()..positionMsValue = 5000;
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1), item(2), item(3)], 0);
    expect(c.currentIndex, 0);
    final opensBefore = engine.openCount;

    c.notifyCompletedForTest();
    // next() is async — wait a tick
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(c.currentIndex, 1);
    expect(engine.openCount, greaterThan(opensBefore));
    expect(engine.stopCount, 0);
    expect(c.queue.length, 3);
  });

  test('completion on last item stops and clears the queue', () async {
    final engine = _FakeEngine()..positionMsValue = 5000;
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1), item(2)], 1);
    expect(c.currentIndex, 1);
    expect(c.hasNext, isFalse);

    c.notifyCompletedForTest();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(engine.stopCount, 1);
    expect(c.queue, isEmpty);
    expect(c.currentIndex, -1);
    expect(c.state, 'idle');
  });

  test('completion of a single video stops', () async {
    final engine = _FakeEngine()..positionMsValue = 5000;
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1)], 0);

    c.notifyCompletedForTest();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(engine.stopCount, 1);
    expect(c.queue, isEmpty);
    expect(c.state, 'idle');
  });

  test('early completed while still at pos 0 does not stop', () async {
    final engine = _FakeEngine()
      ..positionMsValue = 0
      ..durationMsValue = 0;
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1)], 0);

    c.notifyCompletedForTest();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(engine.stopCount, 0);
    expect(c.queue, isNotEmpty);
  });

  test('early completed does not skip to the next playlist item', () async {
    final engine = _FakeEngine()
      ..positionMsValue = 0
      ..durationMsValue = 0;
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1), item(2)], 0);
    final opensBefore = engine.openCount;

    c.notifyCompletedForTest();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(c.currentIndex, 0);
    expect(engine.openCount, opensBefore);
    expect(engine.stopCount, 0);
  });

  test('toggles direct and proxied playback at the current position', () async {
    await StreamProxyServer.instance.start();
    final engine = _FakeEngine()..positionMsValue = 250;
    final c = PlayerController(engineForTest: engine);
    final directUrl = 'https://example.com/stream.m3u8';
    final headers = {'User-Agent': 'Test browser'};

    await c.playItem(
      QueueItem(
          url: directUrl, title: 'Stream', headers: headers, skipHistory: true),
    );
    expect(c.isCurrentItemProxied, isFalse);

    expect(await c.toggleProxy(), isTrue);
    expect(c.isCurrentItemProxied, isTrue);
    expect(c.queue.single.skipHistory, isTrue);
    expect(c.queue.single.originalUrl, directUrl);
    expect(c.queue.single.originalHeaders, headers);
    expect(c.queue.single.startPositionMs, isNull);
    expect(engine.lastSeekMs, 250);

    expect(await c.toggleProxy(), isTrue);
    expect(c.isCurrentItemProxied, isFalse);
    expect(c.queue.single.skipHistory, isTrue);
    expect(c.queue.single.url, directUrl);
    expect(c.queue.single.headers, headers);
    expect(engine.lastSeekMs, 250);
    expect(engine.openCount, 3);
  });

  test('leaves direct playback unchanged when the proxy is unavailable',
      () async {
    final engine = _FakeEngine();
    final c = PlayerController(engineForTest: engine);
    final directUrl = 'https://example.com/stream.m3u8';
    await c.playItem(QueueItem(url: directUrl, title: 'Stream'));

    expect(await c.toggleProxy(), isFalse);
    expect(c.isCurrentItemProxied, isFalse);
    expect(c.queue.single.url, directUrl);
  });

  test('preserves paused and buffering playback intent during toggles',
      () async {
    await StreamProxyServer.instance.start();
    final engine = _FakeEngine();
    final c = PlayerController(engineForTest: engine);
    await c.playItem(
      QueueItem(url: 'https://example.com/stream.m3u8', title: 'Stream'),
    );

    engine.setStateForTest('paused');
    expect(await c.toggleProxy(), isTrue);
    expect(engine.lastOpenPlay, isFalse);
    expect(engine.state, 'paused');

    engine.setStateForTest('buffering');
    expect(await c.toggleProxy(), isTrue);
    expect(engine.lastOpenPlay, isTrue);
    expect(engine.state, 'playing');
  });

  test('reopens the original route when the replacement open fails', () async {
    await StreamProxyServer.instance.start();
    final engine = _FakeEngine();
    final c = PlayerController(engineForTest: engine);
    final directUrl = 'https://example.com/stream.m3u8';
    await c.playItem(QueueItem(url: directUrl, title: 'Stream'));
    engine.failuresRemaining = 1;

    expect(await c.toggleProxy(), isFalse);
    expect(c.queue.single.url, directUrl);
    expect(engine.openCount, 3);
    expect(engine.state, 'playing');
  });

  test('serializes stop behind an in-flight toggle', () async {
    await StreamProxyServer.instance.start();
    final engine = _FakeEngine()..openDelay = const Duration(milliseconds: 20);
    final c = PlayerController(engineForTest: engine);
    await c.playItem(
      QueueItem(url: 'https://example.com/stream.m3u8', title: 'Stream'),
    );

    final toggle = c.toggleProxy();
    final stop = c.stop();
    await toggle;
    await stop;

    expect(c.currentIndex, -1);
    expect(c.queue, isEmpty);
  });
}
