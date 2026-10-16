import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/playback_request_preparer.dart';
import 'package:playbridge_desktop/media_kind.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/receiver_server.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _FakeEngine extends PlayerEngine {
  String _state = 'idle';
  int _positionMs = 0;
  int _durationMs = 1000;
  bool shouldThrowOnOpen = false;
  Object? openError;

  @override
  String get state => _state;
  void setStateForTest(String s) {
    _state = s;
    notifyListeners();
  }

  @override
  int get positionMs => _positionMs;
  void setPositionMsForTest(int p) {
    _positionMs = p;
    notifyListeners();
  }

  @override
  int get durationMs => _durationMs;
  void setDurationMsForTest(int d) {
    _durationMs = d;
    notifyListeners();
  }

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
    if (shouldThrowOnOpen) {
      throw openError ?? StateError('failed to open media');
    }
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
    _state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    super.dispose();
  }

  void emitEngineError(String msg) {
    onError?.call(msg);
  }
}

class _HttpStatusCarrier {
  _HttpStatusCarrier(this.statusCode);
  final int statusCode;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('extractHttpStatus and formatPlaybackErrorMessage', () {
    test('extracts HTTP status from integers and structured statusCode', () {
      expect(extractHttpStatus(403), 403);
      expect(extractHttpStatus(404), 404);
      expect(extractHttpStatus(500), 500);
      expect(extractHttpStatus(80), isNull);
      expect(extractHttpStatus(8080), isNull);
      expect(extractHttpStatus(0), isNull);
      expect(extractHttpStatus(600), isNull);
      expect(extractHttpStatus(_HttpStatusCarrier(403)), 403);
      expect(extractHttpStatus(null), isNull);
    });

    test('extracts HTTP status from exact mpv/ffmpeg "HTTP error NNN" pattern',
        () {
      expect(extractHttpStatus('HTTP error 403 Forbidden'), 403);
      expect(extractHttpStatus('HTTP error 404 Not Found'), 404);
      expect(extractHttpStatus('HTTP error 500'), 500);
      expect(extractHttpStatus('HTTP error 403'), 403);
      expect(
        extractHttpStatus(
          'Failed to open https://token:secret@example.com/v.m3u8: HTTP error 403 Forbidden',
        ),
        403,
      );
    });

    test(
        'gives no code for URLs containing /403/, http_403, or :8403 inside "Failed to open <url>"',
        () {
      expect(
        extractHttpStatus('Failed to open https://example.com/403/video.mp4'),
        isNull,
      );
      expect(
        extractHttpStatus(
            'Failed to open https://example.com/http_403/video.mp4'),
        isNull,
      );
      expect(
        extractHttpStatus('Failed to open https://example.com:8403/video.mp4'),
        isNull,
      );
    });

    test('drops other heuristics and gives no code', () {
      expect(extractHttpStatus('HTTP 403'), isNull);
      expect(extractHttpStatus('HTTP/1.1 403 Forbidden'), isNull);
      expect(extractHttpStatus('Server returned 403 Forbidden'), isNull);
      expect(extractHttpStatus('server returned: 502'), isNull);
      expect(extractHttpStatus('http_401'), isNull);
      expect(extractHttpStatus('403 Forbidden'), isNull);
      expect(extractHttpStatus('http error 403'), isNull); // case-sensitive
      expect(extractHttpStatus('Connection refused to 127.0.0.1:8080'), isNull);
      expect(extractHttpStatus('Error reading 400 bytes from socket'), isNull);
      expect(extractHttpStatus(''), isNull);
    });

    test('formats user-safe error message and never leaks URLs or tokens', () {
      const secretUrlError =
          'Failed to open https://token:xyz123@cdn.example.com/stream.m3u8: HTTP error 403 Forbidden';
      final msgWithStatus = formatPlaybackErrorMessage(secretUrlError);
      expect(msgWithStatus, "The media couldn't be loaded (HTTP 403)");
      expect(msgWithStatus.contains('token'), isFalse);
      expect(msgWithStatus.contains('example.com'), isFalse);
      expect(msgWithStatus.contains('https'), isFalse);

      const urlWith403Path =
          'Failed to open https://user:pass@cdn.example.com/403/video.mp4';
      final msgUrlWith403 = formatPlaybackErrorMessage(urlWith403Path);
      expect(msgUrlWith403, "The media couldn't be loaded");
      expect(msgUrlWith403.contains('user'), isFalse);
      expect(msgUrlWith403.contains('pass'), isFalse);
      expect(msgUrlWith403.contains('example.com'), isFalse);
      expect(msgUrlWith403.contains('403'), isFalse);

      const urlWithPort =
          'Failed to open https://cdn.example.com:8403/video.mp4';
      final msgUrlWithPort = formatPlaybackErrorMessage(urlWithPort);
      expect(msgUrlWithPort, "The media couldn't be loaded");
      expect(msgUrlWithPort.contains('8403'), isFalse);

      const urlWithHttp403 =
          'Failed to open https://cdn.example.com/http_403/video.mp4';
      final msgUrlWithHttp403 = formatPlaybackErrorMessage(urlWithHttp403);
      expect(msgUrlWithHttp403, "The media couldn't be loaded");
      expect(msgUrlWithHttp403.contains('http_403'), isFalse);

      const noStatusError =
          'Failed to open https://user:pass@cdn.example.com/video.mp4';
      final msgWithoutStatus = formatPlaybackErrorMessage(noStatusError);
      expect(msgWithoutStatus, "The media couldn't be loaded");
      expect(msgWithoutStatus.contains('user'), isFalse);
      expect(msgWithoutStatus.contains('pass'), isFalse);
      expect(msgWithoutStatus.contains('example.com'), isFalse);

      final explicitStatus =
          formatPlaybackErrorMessage('anything', httpStatus: 503);
      expect(explicitStatus, "The media couldn't be loaded (HTTP 503)");
    });
  });

  group('PlayerController error handling', () {
    late _FakeEngine engine;
    late PlayerController controller;

    setUp(() {
      engine = _FakeEngine();
      controller = PlayerController(engineForTest: engine);
    });

    tearDown(() {
      controller.dispose();
    });

    test('synchronous open failure emits PlaybackFailure and stops', () async {
      engine.shouldThrowOnOpen = true;
      engine.openError = Exception(
        'Failed to open https://secret@example.com/video.mp4: HTTP error 403 Forbidden',
      );

      PlaybackFailure? reportedFailure;
      controller.onTerminalError = (failure) {
        reportedFailure = failure;
      };

      await controller.playUrl(
        'https://token:secret@example.com/video.mp4',
        title: 'Secret Video',
      );

      expect(reportedFailure, isNotNull);
      expect(
        reportedFailure!.error,
        "The media couldn't be loaded (HTTP 403)",
      );
      expect(reportedFailure!.title, 'Secret Video');
      expect(reportedFailure!.mediaKind, MediaKind.video);
      expect(reportedFailure!.error.contains('secret'), isFalse);
      expect(reportedFailure!.error.contains('example.com'), isFalse);

      // Player transitioned to idle after terminal stop
      expect(controller.state, 'idle');
      expect(controller.hasTerminalError, isFalse);
    });

    test('asynchronous engine error event triggers terminal error and stop',
        () async {
      PlaybackFailure? reportedFailure;
      controller.onTerminalError = (failure) {
        reportedFailure = failure;
      };

      await controller.playUrl(
        'https://token:secret@example.com/video.mp4',
        title: 'My Video',
      );

      final activePlaybackId = controller.playbackId;
      final activeItemId = controller.currentItemId;
      expect(activePlaybackId, isNotNull);
      expect(activeItemId, isNotNull);

      // Engine signals error
      engine.emitEngineError(
        'Failed to open https://example.com/video.mp4: HTTP error 404 Not Found',
      );
      await controller.terminalErrorFutureForTest;

      expect(reportedFailure, isNotNull);
      expect(reportedFailure!.playbackId, activePlaybackId);
      expect(reportedFailure!.currentItemId, activeItemId);
      expect(
        reportedFailure!.error,
        "The media couldn't be loaded (HTTP 404)",
      );

      expect(controller.state, 'idle');
    });

    test('duplicate errors on the same item are ignored', () async {
      var failureCount = 0;
      controller.onTerminalError = (_) {
        failureCount++;
      };

      await controller.playUrl('https://example.com/video.mp4');

      engine.emitEngineError('HTTP error 403 Forbidden');
      await controller.terminalErrorFutureForTest;
      engine.emitEngineError('HTTP error 403 Forbidden');
      await pumpEventQueue();

      expect(failureCount, 1);
    });

    test('next load resets error state cleanly', () async {
      PlaybackFailure? reportedFailure;
      controller.onTerminalError = (f) => reportedFailure = f;

      await controller.playUrl('https://example.com/fail.mp4');
      engine.emitEngineError('HTTP error 403 Forbidden');
      await controller.terminalErrorFutureForTest;
      expect(reportedFailure, isNotNull);

      // Next load
      reportedFailure = null;
      await controller.playUrl('https://example.com/success.mp4');
      expect(controller.hasTerminalError, isFalse);
      expect(controller.state, 'playing');
      expect(reportedFailure, isNull);
    });
  });

  group('ReceiverServer terminal error broadcast', () {
    late _FakeEngine engine;
    late PlayerController controller;
    late PairingStore store;
    late ReceiverServer server;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      PlaybackRequestPreparer.clearFailedHosts();
      await StreamProxyServer.instance.stop();
      store = await PairingStore.load();
      engine = _FakeEngine();
      controller = PlayerController(engineForTest: engine, store: store);
      server = ReceiverServer(player: controller, store: store);
      server.attachPlayerListenersForTest();
    });

    tearDown(() async {
      await server.stop();
      controller.dispose();
      await StreamProxyServer.instance.stop();
      PlaybackRequestPreparer.clearFailedHosts();
    });

    test(
        'broadcasts exactly one error status frame before transitioning to idle',
        () async {
      final broadcastFrames = <Map<String, Object?>>[];
      server.onBroadcastForTest = (msg) {
        broadcastFrames.add(Map<String, Object?>.from(msg));
      };

      await controller.playUrl(
        'https://token:secret@example.com/video.mp4',
        title: 'Target Movie',
      );

      final playbackId = controller.playbackId;
      final currentItemId = controller.currentItemId;
      broadcastFrames.clear();

      // Trigger engine terminal failure
      engine.emitEngineError(
        'Failed to open https://token:secret@example.com/video.mp4: HTTP error 403 Forbidden',
      );
      await controller.terminalErrorFutureForTest;

      // Find error status frames
      final errorFrames = broadcastFrames
          .where((f) => f['type'] == 'status' && f['state'] == 'error')
          .toList();

      expect(errorFrames.length, 1);
      final errorFrame = errorFrames.first;
      expect(errorFrame['state'], 'error');
      expect(errorFrame['playbackId'], playbackId);
      expect(errorFrame['currentItemId'], currentItemId);
      expect(errorFrame['mediaKind'], 'video');
      expect(errorFrame['title'], 'Target Movie');
      expect(
        errorFrame['error'],
        "The media couldn't be loaded (HTTP 403)",
      );

      // Sanity check: no URL or secret in any broadcast frame
      for (final frame in broadcastFrames) {
        final serialized = frame.toString();
        expect(serialized.contains('token'), isFalse);
        expect(serialized.contains('secret'), isFalse);
        expect(serialized.contains('example.com'), isFalse);
      }

      // Final status frame should be idle (from stop())
      final statusFrames =
          broadcastFrames.where((f) => f['type'] == 'status').toList();
      expect(statusFrames.last['state'], 'idle');

      // Ticking the engine or listeners while stopped does not emit buffering
      engine.setStateForTest('buffering');
      final bufferingFrames = broadcastFrames
          .where((f) => f['type'] == 'status' && f['state'] == 'buffering')
          .toList();
      expect(bufferingFrames, isEmpty);
    });

    test(
        'direct HLS failure in auto mode retries via proxy before terminal error',
        () async {
      await StreamProxyServer.instance.start();
      await store.setStreamProxyMode(StreamProxyMode.auto);
      final broadcastFrames = <Map<String, Object?>>[];
      server.onBroadcastForTest = (msg) {
        broadcastFrames.add(Map<String, Object?>.from(msg));
      };

      await controller.playUrl(
        'https://example.com/live/stream.m3u8',
        title: 'Live Stream',
      );

      broadcastFrames.clear();

      // First failure triggers proxy retry
      engine.emitEngineError('HTTP error 403 Forbidden');
      await controller.retryThroughProxyFutureForTest;

      // Should have retried via proxy without terminal error yet
      final errorFramesFirst = broadcastFrames
          .where((f) => f['type'] == 'status' && f['state'] == 'error')
          .toList();
      expect(errorFramesFirst, isEmpty);
      expect(controller.isCurrentItemProxied, isTrue);

      // Second failure (on proxied stream) is terminal
      engine.emitEngineError('HTTP error 403 Forbidden');
      await controller.terminalErrorFutureForTest;

      final errorFramesSecond = broadcastFrames
          .where((f) => f['type'] == 'status' && f['state'] == 'error')
          .toList();
      expect(errorFramesSecond.length, 1);
      expect(
        errorFramesSecond.first['error'],
        "The media couldn't be loaded (HTTP 403)",
      );
    });
  });
}
