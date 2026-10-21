import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/mpv_engine.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/remote_timeline.dart';
import 'package:playbridge_desktop/skip_segment.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Engine extends PlayerEngine {
  void emit() => notifyListeners();
  @override
  Future<void> dispose() async {
    super.dispose();
  }

  String currentState = 'idle';
  int position = 10000;
  int duration = 60000;
  bool live = false;
  int? buffered;
  List<ChapterInfo> chapterList = const [];

  @override
  String get state => currentState;
  @override
  int get positionMs => position;
  @override
  int get durationMs => live ? 0 : duration;
  @override
  bool get isLive => live;
  @override
  List<ChapterInfo> get chapters => chapterList;
  @override
  int? get bufferedMs => buffered;
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
    currentState = 'playing';
    notifyListeners();
  }

  @override
  Future<void> resume() async {}
  @override
  Future<void> pause() async {}
  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> stop() async {
    currentState = 'idle';
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('buildTimelineMessage', () {
    test('sorts, trims, caps and maps open-ended segments to null', () {
      final message = buildTimelineMessage(
        playbackId: 'p',
        currentItemId: 'i',
        chapters: [
          const ChapterInfo(startMs: 312000),
          const ChapterInfo(startMs: 0, title: '  Opening  '),
          const ChapterInfo(startMs: 5000, title: '   '),
          const ChapterInfo(startMs: -1, title: 'Bad'),
        ],
        segments: const [
          SkipSegment(
              type: 'outro', startMs: 5400000, endMs: skipSegmentOpenEndedMs),
          SkipSegment(type: 'intro', startMs: 30000, endMs: 95000),
          SkipSegment(type: 'credits', startMs: 1, endMs: 2),
          SkipSegment(type: 'recap', startMs: 10, endMs: 5),
        ],
      );
      expect(message, {
        'type': 'timeline',
        'playbackId': 'p',
        'currentItemId': 'i',
        'chapters': [
          {'startMs': 0, 'title': 'Opening'},
          {'startMs': 5000},
          {'startMs': 312000},
        ],
        'segments': [
          {'type': 'intro', 'startMs': 30000, 'endMs': 95000},
          {'type': 'outro', 'startMs': 5400000, 'endMs': null},
        ],
      });
    });

    test('matches the protocol timeline fixture', () {
      final fixture = jsonDecode(File(
              '../protocol/tests/fixtures/receiver-capabilities/timeline-v1.json')
          .readAsStringSync());
      expect(
        buildTimelineMessage(
          playbackId: 'playback-1',
          currentItemId: 'item-1',
          chapters: const [
            ChapterInfo(startMs: 312000),
            ChapterInfo(startMs: 0, title: 'Opening'),
          ],
          segments: const [
            SkipSegment(type: 'intro', startMs: 30000, endMs: 95000),
            SkipSegment(
                type: 'outro', startMs: 5400000, endMs: skipSegmentOpenEndedMs),
          ],
        ),
        fixture,
      );
    });

    test('omits null identity and bounds list and title sizes', () {
      final message = buildTimelineMessage(
        chapters: [
          for (var i = 300; i > 0; i--)
            ChapterInfo(startMs: i * 1000, title: 'é' * 250),
        ],
        segments: [
          for (var i = 0; i < 20; i++)
            SkipSegment(type: 'preview', startMs: i * 10, endMs: i * 10 + 5),
        ],
      );
      expect(message.containsKey('playbackId'), isFalse);
      expect(message.containsKey('currentItemId'), isFalse);
      final chapters = message['chapters']! as List;
      expect(chapters, hasLength(timelineMaxChapters));
      expect((chapters.first as Map)['startMs'], 1000);
      expect(((chapters.first as Map)['title'] as String).runes.length,
          timelineMaxTitleLength);
      expect(message['segments'] as List, hasLength(timelineMaxSegments));
    });
  });

  test('mpv chapter-list parsing skips malformed entries and placeholders', () {
    expect(
      parseMpvChapterList('[{"title":"Opening \\"Q\\"","time":0.000000},'
          '{"title":"(unnamed)","time":2.5},{"title":"","time":4},'
          '{"time":"x"},7]'),
      const [
        ChapterInfo(startMs: 0, title: 'Opening "Q"'),
        ChapterInfo(startMs: 2500),
        ChapterInfo(startMs: 4000, title: ''),
      ],
    );
    expect(parseMpvChapterList('not json'), isEmpty);
    expect(parseMpvChapterList('{}'), isEmpty);
  });

  group('receiver timeline and buffered reporting', () {
    late _Engine engine;
    late PlayerController player;
    late ReceiverServer server;
    late List<Map<String, Object?>> timelines;
    late List<Map<String, Object?>> statuses;
    late List<Map<String, Object?>> direct;
    QueueItem item(int index) => QueueItem(
        url: 'https://example.invalid/$index.mp4', title: 'Item $index');

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      await StreamProxyServer.instance.stop();
      final store = await PairingStore.load();
      engine = _Engine();
      player = PlayerController(engineForTest: engine);
      server = ReceiverServer(player: player, store: store);
      timelines = [];
      statuses = [];
      direct = [];
      server.onBroadcastForTest = (frame) {
        if (frame['type'] == 'timeline') timelines.add(frame);
        if (frame['type'] == 'status') statuses.add(frame);
      };
      server.onSendForTest = (id, frame) {
        if (frame['type'] == 'timeline') direct.add(frame);
      };
      server.attachPlayerListenersForTest();
      await player.playPlaylist([item(1), item(2)], 0);
    });
    tearDown(() async {
      await server.stop();
      player.dispose();
      await StreamProxyServer.instance.stop();
    });

    test('announces the item, then chapters and segments, without repeats',
        () async {
      final first = player.currentItemId;
      expect(timelines.last, {
        'type': 'timeline',
        'playbackId': player.playbackId,
        'currentItemId': first,
        'chapters': <Object?>[],
        'segments': <Object?>[],
      });
      engine.chapterList = const [ChapterInfo(startMs: 0, title: 'Opening')];
      engine.emit();
      player.publishSkipSegments(const [
        SkipSegment(
            type: 'outro', startMs: 50000, endMs: skipSegmentOpenEndedMs)
      ]);
      expect(timelines.last['chapters'], [
        {'startMs': 0, 'title': 'Opening'}
      ]);
      expect(timelines.last['segments'], [
        {'type': 'outro', 'startMs': 50000, 'endMs': null}
      ]);
      final count = timelines.length;
      engine.emit();
      player.publishSkipSegments(const [
        SkipSegment(
            type: 'outro', startMs: 50000, endMs: skipSegmentOpenEndedMs)
      ]);
      expect(timelines, hasLength(count));
    });

    test('item changes clear segments and chapters from the previous item',
        () async {
      engine.chapterList = const [ChapterInfo(startMs: 1000)];
      player.publishSkipSegments(
          const [SkipSegment(type: 'intro', startMs: 0, endMs: 9000)]);
      final before = timelines.length;
      final opening = <Map<String, Object?>>[];
      void captureWhileOpening() {
        if (player.isOpening) {
          opening.add(buildTimelineMessage(
            playbackId: player.playbackId,
            currentItemId: player.currentItemId,
            chapters: player.chapters,
            segments: player.skipSegments,
          ));
        }
      }

      player.addListener(captureWhileOpening);
      await player.jumpTo(1);
      player.removeListener(captureWhileOpening);
      expect(opening, isNotEmpty);
      // The engine still reports the old file's chapters until it loads, but
      // nothing from item 1 may be attributed to item 2.
      for (final frame in [...opening, ...timelines.skip(before)]) {
        if (frame['currentItemId'] != player.currentItemId) continue;
        expect(frame['segments'], isEmpty);
      }
      for (final frame in opening) {
        expect(frame['chapters'], isEmpty);
      }
      expect(timelines.last['currentItemId'], player.currentItemId);
      expect(timelines.last['segments'], isEmpty);
      // A late result published for the old item does not resurface.
      expect(player.skipSegments, isEmpty);
    });

    test('context query sends the current timeline to the requester', () async {
      player.publishSkipSegments(
          const [SkipSegment(type: 'recap', startMs: 0, endMs: 9000)]);
      await server.handleCommandForTest(const ContextQueryCmd(),
          connectionId: 7);
      await server.handleCommandForTest(const ContextQueryCmd(),
          connectionId: 7);
      expect(direct, hasLength(2));
      expect(direct.first, direct.last);
      expect(direct.last['segments'], [
        {'type': 'recap', 'startMs': 0, 'endMs': 9000}
      ]);
    });

    test('images report an empty timeline', () async {
      player.publishSkipSegments(
          const [SkipSegment(type: 'intro', startMs: 0, endMs: 9000)]);
      engine.chapterList = const [ChapterInfo(startMs: 1000)];
      await player.playPlaylist([
        QueueItem(
            url: 'https://example.invalid/a.jpg',
            title: 'Image',
            declaredMediaKind: 'image')
      ], 0);
      player.publishSkipSegments(
          const [SkipSegment(type: 'intro', startMs: 0, endMs: 9000)]);
      engine.emit();
      expect(timelines.last['chapters'], isEmpty);
      expect(timelines.last['segments'], isEmpty);
      expect(statuses.last.containsKey('buffered'), isFalse);
    });

    test('status.buffered is absolute, clamped, and omitted when unknown',
        () async {
      engine.emit();
      expect(statuses.last.containsKey('buffered'), isFalse);
      engine.buffered = 25000;
      engine.emit();
      expect(statuses.last['buffered'], 25000);
      engine.buffered = 5000; // behind the playhead after a seek
      engine.emit();
      expect(statuses.last['buffered'], 10000);
      engine.buffered = 90000;
      engine.emit();
      expect(statuses.last['buffered'], 60000);
      engine.buffered = 0;
      engine.emit();
      expect(statuses.last.containsKey('buffered'), isFalse);
      engine
        ..buffered = 25000
        ..live = true;
      engine.emit();
      expect(statuses.last.containsKey('buffered'), isFalse);
    });
  });
}
