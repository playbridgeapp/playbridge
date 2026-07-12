import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/still_watching_controller.dart';

class _FakeEngine extends PlayerEngine {
  String _state = 'idle';
  int pauseCount = 0;
  int resumeCount = 0;
  int stopCount = 0;
  Completer<void>? pauseGate;

  @override
  String get state => _state;
  @override
  int get positionMs => 0;
  @override
  int get durationMs => 10000;
  @override
  dynamic get tracks => null;
  @override
  dynamic get track => null;

  @override
  Future<void> open(QueueItem item) async => _play();
  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) async =>
      _play();
  void _play() {
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> pause() async {
    pauseCount++;
    final gate = pauseGate;
    if (gate != null) await gate.future;
    _state = 'paused';
    notifyListeners();
  }

  @override
  Future<void> resume() async {
    resumeCount++;
    _play();
  }

  @override
  Future<void> stop() async {
    stopCount++;
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> setAudioTrack(dynamic t) async {}
  @override
  Future<void> setSubtitleTrack(dynamic t) async {}

  @override
  Future<void> dispose() async {
    super.dispose();
  }
}

Future<void> _waitUntil(bool Function() condition) async {
  for (var i = 0; i < 300 && !condition(); i++) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(condition(), isTrue);
}

void main() {
  QueueItem item(int n) => QueueItem(url: 'https://x/$n', title: 'Episode $n');

  test('threshold pauses once and Continue grants a fresh interval', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 40),
    );
    await player.playPlaylist([item(1), item(2)], 0);

    await _waitUntil(() => controller.isPrompting);
    expect(engine.pauseCount, 1);

    await controller.continueWatching();
    expect(engine.resumeCount, 1);
    expect(controller.isPrompting, isFalse);
    expect(
        controller.activeElapsed, lessThan(const Duration(milliseconds: 30)));

    await _waitUntil(() => controller.isPrompting);
    expect(engine.pauseCount, 2);
    controller.dispose();
    await player.dispose();
  });

  test('paused time does not count and queue jumps preserve elapsed time',
      () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 100),
    );
    await player.playPlaylist([item(1), item(2)], 0);
    await Future<void>.delayed(const Duration(milliseconds: 45));
    await player.pause();
    await Future<void>.delayed(const Duration(milliseconds: 80));
    expect(controller.isPrompting, isFalse);

    await player.resume();
    await player.jumpTo(1);
    await _waitUntil(() => controller.isPrompting);
    expect(engine.pauseCount, 2); // manual pause + feature pause
    controller.dispose();
    await player.dispose();
  });

  test('expiry stops and clears the playback session', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
      gracePeriod: const Duration(seconds: 1),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);
    await _waitUntil(() => player.state == 'idle');

    expect(engine.stopCount, 1);
    expect(player.queue, isEmpty);
    expect(controller.phase, StillWatchingPhase.inactive);
    controller.dispose();
    await player.dispose();
  });

  test('expiry reports idle after stopping', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    var idleReports = 0;
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
      gracePeriod: const Duration(seconds: 1),
      onAutoStop: () => idleReports++,
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => player.state == 'idle');
    expect(idleReports, 1);
    controller.dispose();
    await player.dispose();
  });

  test('viewer activity grants a fresh interval', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 80),
    );
    await player.playPlaylist([item(1)], 0);
    await Future<void>.delayed(const Duration(milliseconds: 55));
    controller.recordUserActivity();
    await Future<void>.delayed(const Duration(milliseconds: 45));
    expect(controller.isPrompting, isFalse);
    await _waitUntil(() => controller.isPrompting);
    controller.dispose();
    await player.dispose();
  });

  test('new media dismisses an existing prompt', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);
    controller.resetForNewMedia();
    await player.playPlaylist([item(2)], 0);
    expect(controller.isPrompting, isFalse);
    expect(player.currentTitle, 'Episode 2');
    controller.dispose();
    await player.dispose();
  });

  test('new media recovers if the old feature pause finishes late', () async {
    final engine = _FakeEngine()..pauseGate = Completer<void>();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);

    controller.resetForNewMedia();
    await player.playPlaylist([item(2)], 0);
    engine.pauseGate!.complete();
    await _waitUntil(() => engine.resumeCount == 1);

    expect(player.state, 'playing');
    expect(player.currentTitle, 'Episode 2');
    expect(controller.isPrompting, isFalse);
    controller.dispose();
    await player.dispose();
  });

  test('stale feature pause does not override a later viewer pause', () async {
    final engine = _FakeEngine()..pauseGate = Completer<void>();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);

    controller.resetForNewMedia();
    await player.playPlaylist([item(2)], 0);
    controller.recordUserActivity();
    final viewerPause = player.pause();
    engine.pauseGate!.complete();
    await viewerPause;
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(player.state, 'paused');
    expect(engine.resumeCount, 0);
    controller.dispose();
    await player.dispose();
  });

  test('repeated media resets retain one stale-pause recovery', () async {
    final engine = _FakeEngine()..pauseGate = Completer<void>();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);

    controller.resetForNewMedia();
    await player.playPlaylist([item(2)], 0);
    controller.resetForNewMedia();
    await player.playPlaylist([item(3)], 0);
    engine.pauseGate!.complete();
    await _waitUntil(() => engine.resumeCount == 1);

    expect(player.state, 'playing');
    expect(player.currentTitle, 'Episode 3');
    expect(engine.resumeCount, 1);
    controller.dispose();
    await player.dispose();
  });

  test('disabling while prompted resumes feature-paused playback', () async {
    final engine = _FakeEngine();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);

    controller.updateSettings(
      enabled: false,
      threshold: const Duration(milliseconds: 20),
    );
    await Future<void>.delayed(Duration.zero);
    expect(player.state, 'playing');
    expect(controller.phase, StillWatchingPhase.inactive);
    controller.dispose();
    await player.dispose();
  });

  test('Continue waits for an in-flight feature pause before resuming',
      () async {
    final engine = _FakeEngine()..pauseGate = Completer<void>();
    final player = PlayerController(engineForTest: engine);
    final controller = StillWatchingController(
      player: player,
      threshold: const Duration(milliseconds: 20),
    );
    await player.playPlaylist([item(1)], 0);
    await _waitUntil(() => controller.isPrompting);

    final continuing = controller.continueWatching();
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(engine.resumeCount, 0);

    engine.pauseGate!.complete();
    await continuing;
    expect(engine.resumeCount, 1);
    expect(player.state, 'playing');
    expect(controller.phase, StillWatchingPhase.counting);
    controller.dispose();
    await player.dispose();
  });
}
