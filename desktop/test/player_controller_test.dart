import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';

/// Minimal engine for [PlayerController] unit tests (no media_kit / mpv).
class _FakeEngine extends PlayerEngine {
  String _state = 'idle';
  int openCount = 0;
  int stopCount = 0;
  int lastOpenIndex = -1;

  @override
  String get state => _state;
  @override
  int get positionMs => 0;
  @override
  int get durationMs => 1000;
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
    openCount++;
    _state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> openPlaylist(List<QueueItem> items, int startIndex) async {
    openCount++;
    lastOpenIndex = startIndex;
    _state = 'playing';
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
  Future<void> seek(Duration position) async {}

  @override
  Future<void> stop() async {
    stopCount++;
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async {}
}

void main() {
  QueueItem item(int n) => QueueItem(url: 'https://x/$n.mp4', title: 'Ep$n');

  test('completion with next item advances the queue', () async {
    final engine = _FakeEngine();
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
    final engine = _FakeEngine();
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
    final engine = _FakeEngine();
    final c = PlayerController(engineForTest: engine);
    await c.playPlaylist([item(1)], 0);

    c.notifyCompletedForTest();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(engine.stopCount, 1);
    expect(c.queue, isEmpty);
    expect(c.state, 'idle');
  });
}
