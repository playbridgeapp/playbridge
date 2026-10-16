import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/now_casting_screen.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/send_to_tv_screen.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _RecordingTransport implements TvTransport {
  @override
  String? get lastError => null;

  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  @override
  Future<bool> castVideo(PlayPayload video) async => true;

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async => true;

  @override
  Future<bool> queueAdd(PlayPayload item) async => true;

  @override
  Future<bool> sendControl(String command) async => true;

  @override
  Future<void> disconnect() async {}

  @override
  Future<void> dispose() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<TvSenderController> makeSender() async {
    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: await TvConnectionStore.load(),
      transport: _RecordingTransport(),
    );
    addTearDown(sender.dispose);
    return sender;
  }

  group('normalizePlaybackError', () {
    test('normalizes valid error strings', () {
      expect(
        normalizePlaybackError("The media couldn't be loaded (HTTP 403)"),
        "The media couldn't be loaded (HTTP 403)",
      );
      expect(
        normalizePlaybackError('  First line\nSecond line\r\n\tThird line  '),
        'First line Second line Third line',
      );
    });

    test('caps error string at 512 characters', () {
      final long = 'A' * 600;
      final normalized = normalizePlaybackError(long);
      expect(normalized.length, 512);
      expect(normalized, 'A' * 512);
    });

    test('falls back to generic message for non-string or empty input', () {
      const generic = 'Playback failed on the TV';
      expect(normalizePlaybackError(null), generic);
      expect(normalizePlaybackError(''), generic);
      expect(normalizePlaybackError('   \n\t  '), generic);
      expect(normalizePlaybackError(404), generic);
      expect(normalizePlaybackError({'code': 500}), generic);
      expect(normalizePlaybackError(['error']), generic);
      expect(normalizePlaybackError(false), generic);
    });
  });

  group('TvSenderController playback error handling', () {
    test('captures receiver playback error with text and notifies listeners',
        () async {
      final sender = await makeSender();
      var notifications = 0;
      sender.addListener(() => notifications++);

      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'playing',
        'playbackId': 'play-1',
        'title': 'Test Video',
      }));
      expect(sender.isCasting, isTrue);
      expect(sender.lastPlaybackError, isNull);

      final beforeCount = notifications;
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': "The media couldn't be loaded (HTTP 403)",
      }));

      expect(
        sender.lastPlaybackError,
        "The media couldn't be loaded (HTTP 403)",
      );
      expect(sender.isCasting, isFalse);
      expect(notifications, greaterThan(beforeCount));

      // Must survive the subsequent context: idle frame.
      sender.handleReceiverMessage(jsonEncode({
        'type': 'context',
        'active': 'idle',
        'engine': 'idle',
      }));
      expect(
        sender.lastPlaybackError,
        "The media couldn't be loaded (HTTP 403)",
      );
    });

    test('falls back to generic message when error field is missing or invalid',
        () async {
      final sender = await makeSender();

      // Missing error field
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-missing',
      }));
      expect(sender.lastPlaybackError, 'Playback failed on the TV');

      sender.clearPlaybackError();
      expect(sender.lastPlaybackError, isNull);

      // Non-string error field (e.g. numeric error code)
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-num',
        'error': 500,
      }));
      expect(sender.lastPlaybackError, 'Playback failed on the TV');

      sender.clearPlaybackError();

      // Blank string error field
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-blank',
        'error': '   \n  ',
      }));
      expect(sender.lastPlaybackError, 'Playback failed on the TV');
    });

    test('ignores error status with mismatched playbackId', () async {
      final sender = await makeSender();

      // Establish current playback
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'playing',
        'playbackId': 'play-active',
        'title': 'Active Stream',
      }));
      expect(sender.isCasting, isTrue);

      // Stale error frame from a previous/different playbackId
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-stale',
        'error': 'Stale playback failed',
      }));

      // Must be ignored
      expect(sender.lastPlaybackError, isNull);
      expect(sender.isCasting, isTrue);
      expect(sender.castingTitle, 'Active Stream');

      // Error matching active playbackId is accepted
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-active',
        'error': 'Active stream failed',
      }));
      expect(sender.lastPlaybackError, 'Active stream failed');
      expect(sender.isCasting, isFalse);
    });

    test('clears error on next cast', () async {
      final sender = await makeSender();

      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': 'Failure reason',
      }));
      expect(sender.lastPlaybackError, 'Failure reason');

      // Starting a new cast clears the playback error
      await sender.castVideo(
        PlayPayload(url: 'https://example.com/video.mp4', title: 'New Video'),
      );
      expect(sender.lastPlaybackError, isNull);
    });

    test('clears error when new playback starts', () async {
      final sender = await makeSender();

      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': 'Failure reason',
      }));
      expect(sender.lastPlaybackError, 'Failure reason');

      // Receiver starts playing a new stream
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'playing',
        'playbackId': 'play-2',
        'title': 'New Stream',
      }));
      expect(sender.lastPlaybackError, isNull);
    });

    test('clears error on disconnect', () async {
      final sender = await makeSender();

      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': 'Failure reason',
      }));
      expect(sender.lastPlaybackError, 'Failure reason');

      await sender.disconnect();
      expect(sender.lastPlaybackError, isNull);
    });

    test('clearPlaybackError clears error and notifies listeners', () async {
      final sender = await makeSender();

      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': 'Custom Error',
      }));
      expect(sender.lastPlaybackError, 'Custom Error');

      var notified = false;
      sender.addListener(() => notified = true);

      sender.clearPlaybackError();
      expect(sender.lastPlaybackError, isNull);
      expect(notified, isTrue);
    });
  });

  group('UI rendering of playback error', () {
    testWidgets('NowCastingScreen displays receiver playback error',
        (tester) async {
      final sender = await makeSender();
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': "The media couldn't be loaded (HTTP 403)",
      }));

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: NowCastingScreen(controller: sender),
          ),
        ),
      );

      expect(
        find.text("The media couldn't be loaded (HTTP 403)"),
        findsOneWidget,
      );
      expect(find.text('Playback failed on the TV.'), findsOneWidget);
    });

    testWidgets('SendToTvScreen shows banner and dismisses on tap',
        (tester) async {
      final sender = await makeSender();
      sender.handleReceiverMessage(jsonEncode({
        'type': 'status',
        'state': 'error',
        'playbackId': 'play-1',
        'error': 'Network timeout reaching stream',
      }));

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SendToTvScreen(controller: sender),
          ),
        ),
      );

      expect(find.text('Network timeout reaching stream'), findsOneWidget);
      expect(find.byIcon(Icons.error_outline), findsWidgets);

      final dismissButton = find.byTooltip('Dismiss');
      expect(dismissButton, findsOneWidget);

      await tester.tap(dismissButton);
      await tester.pump();

      expect(sender.lastPlaybackError, isNull);
      expect(find.text('Network timeout reaching stream'), findsNothing);
    });
  });
}
