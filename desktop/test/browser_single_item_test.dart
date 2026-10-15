import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/now_casting_screen.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Exercises the real BrowserTransport playlist validation without starting
// sender services or opening any socket.
class _BrowserTransportProbe extends BrowserTransport {
  final videos = <PlayPayload>[];

  @override
  Future<bool> castVideo(PlayPayload video) async {
    videos.add(video);
    return true;
  }
}

class _RecordingTransport extends TvTransport {
  _RecordingTransport(this.protocol, this.capabilities);

  @override
  final TvProtocol protocol;
  @override
  final Map<String, dynamic> capabilities;
  final playlists = <PlaylistPayload>[];
  final jumps = <int>[];
  final queued = <PlayPayload>[];
  final _states = StreamController<SenderConnectionState>.broadcast();
  @override
  SenderConnectionState currentState = SenderConnectionState.disconnected;
  @override
  Stream<SenderConnectionState> get state => _states.stream;
  @override
  Stream<String> get messages => const Stream.empty();
  @override
  Stream<TvCredentials> get credentials => const Stream.empty();

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    currentState = SenderConnectionState.connected;
    _states.add(currentState);
  }

  @override
  Future<void> disconnect() async {}
  @override
  Future<bool> castVideo(PlayPayload video) async => true;
  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    playlists.add(playlist);
    return true;
  }

  @override
  Future<bool> playlistJump(int index) async {
    jumps.add(index);
    return true;
  }

  @override
  Future<bool> queueAdd(PlayPayload item) async {
    queued.add(item);
    return true;
  }

  @override
  Future<bool> sendControl(String command) async => true;
  @override
  Future<void> dispose() => _states.close();
}

// Invalid multi-file requests must be rejected even before inspecting files,
// which also prevents proxy registrations and LAN interface lookup.
class _UntouchedFile implements File {
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('Rejected cast accessed a file');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));

  final first = PlayPayload(url: 'https://example.test/one.mp4', title: 'One');
  final second = PlayPayload(url: 'https://example.test/two.mp4', title: 'Two');

  Future<({TvSenderController sender, _RecordingTransport transport})> make({
    TvProtocol protocol = TvProtocol.webBrowser,
    Map<String, dynamic> capabilities = const {},
    Future<StreamProxyLease> Function(Iterable<String>)? retainProxyUrls,
  }) async {
    final transport = _RecordingTransport(protocol, capabilities);
    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: await TvConnectionStore.load(),
      transport: transport,
      retainProxyUrls: retainProxyUrls ?? (_) async => StreamProxyLease(() {}),
    );
    addTearDown(sender.dispose);
    sender.bindTransportForTest();
    await sender.reconnect(TvRecord(
      uuid: 'fake-session',
      protocol: protocol,
      name: 'Living Room',
      host: '192.0.2.1',
      port: 8770,
      capabilities: capabilities,
      lastConnected: DateTime(2026),
    ));
    await Future<void>.value();
    expect(sender.isConnected, isTrue);
    return (sender: sender, transport: transport);
  }

  test('browser transport refuses multiple, empty and nonzero-index playlists',
      () async {
    final transport = _BrowserTransportProbe();
    addTearDown(transport.dispose);
    for (final playlist in [
      PlaylistPayload(items: [first, second]),
      PlaylistPayload(items: [first], startIndex: 1),
      PlaylistPayload(items: [first], startIndex: -1),
      PlaylistPayload(),
    ]) {
      expect(await transport.castPlaylist(playlist), isFalse);
      expect(transport.lastError, browserSingleItemMessage);
    }
    expect(transport.videos, isEmpty);
    expect(
        await transport.castPlaylist(PlaylistPayload(items: [first])), isTrue);
    expect(transport.videos.single, first);
    expect(transport.lastError, isNull);
  });

  test('multiple local files are refused before file or proxy work', () async {
    var retains = 0;
    final f = await make(retainProxyUrls: (_) async {
      retains++;
      return StreamProxyLease(() {});
    });
    expect(
      await f.sender.castLocalFiles([_UntouchedFile(), _UntouchedFile()]),
      isFalse,
    );
    expect(f.sender.lastCastError, browserSingleItemMessage);
    expect(f.transport.playlists, isEmpty);
    expect(retains, 0);
    expect(f.sender.isCasting, isFalse);
  });

  test('playlist and linked callers cannot bypass browser rejection', () async {
    var retains = 0;
    final f = await make(retainProxyUrls: (_) async {
      retains++;
      return StreamProxyLease(() {});
    });
    for (final playlist in [
      PlaylistPayload(items: [first, second]),
      PlaylistPayload(items: [first], startIndex: 1),
      PlaylistPayload(items: [first], startIndex: -1),
    ]) {
      expect(await f.sender.castPlaylist(playlist), isFalse);
      expect(f.sender.lastCastError, browserSingleItemMessage);
      expect(
        await f.sender.castLinkedPlaylist(playlist, canCommit: () => true),
        isFalse,
      );
      expect(f.sender.lastCastError, browserSingleItemMessage);
    }
    expect(f.transport.playlists, isEmpty);
    expect(retains, 0);
  });

  testWidgets('single browser item plays without playlist or jump controls',
      (tester) async {
    final f = await make();
    expect(
        await f.sender.castPlaylist(PlaylistPayload(items: [first])), isTrue);
    expect(f.transport.playlists.single.items.single, first);
    expect(f.sender.castingTitle, 'One');
    expect(f.sender.castItems, [(index: 0, title: 'One')]);
    expect(f.sender.lastCastError, isNull);
    expect(f.sender.canPlaylistJump, isFalse);
    expect(await f.sender.playlistJumpTo(0), isFalse);
    expect(await f.sender.queueAdd(second), isFalse);
    expect(f.transport.jumps, isEmpty);
    expect(f.transport.queued, isEmpty);

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: NowCastingScreen(controller: f.sender)),
    ));
    expect(find.text('One'), findsOneWidget);
    expect(find.textContaining('PLAYLIST'), findsNothing);

    // Even a stale/unexpected playlist snapshot cannot offer browser jumps.
    f.sender.handleReceiverMessage(jsonEncode({
      'type': 'playlist_status',
      'items': [
        {'index': 0, 'title': 'One'},
        {'index': 1, 'title': 'Two'},
      ],
    }));
    await tester.pump();
    expect(find.textContaining('PLAYLIST'), findsNothing);
    expect(find.text('Two'), findsNothing);
  });

  test('rejected replacement leaves the current browser item and lease intact',
      () async {
    var closed = 0;
    var retained = 0;
    final f = await make(retainProxyUrls: (_) async {
      retained++;
      return StreamProxyLease(() => closed++);
    });
    expect(
        await f.sender.castPlaylist(PlaylistPayload(items: [first])), isTrue);
    expect(await f.sender.castPlaylist(PlaylistPayload(items: [first, second])),
        isFalse);
    expect(f.sender.castingTitle, 'One');
    expect(f.sender.castItems, [(index: 0, title: 'One')]);
    expect(retained, 1);
    expect(closed, 0);
    expect(f.transport.playlists, hasLength(1));
    expect(await f.sender.stopCast(), isTrue);
    expect(closed, 1);
  });

  testWidgets('native playlist, start index, queue and jumps remain available',
      (tester) async {
    final f = await make(protocol: TvProtocol.playBridge);
    final playlist = PlaylistPayload(items: [first, second], startIndex: 1);
    expect(await f.sender.castPlaylist(playlist), isTrue);
    expect(f.transport.playlists.single, playlist);
    expect(f.transport.playlists.single.startIndex, 1);
    f.sender.handleReceiverMessage(jsonEncode({
      'type': 'status',
      'state': 'playing',
      'title': 'Two',
    }));
    f.sender.handleReceiverMessage(jsonEncode({
      'type': 'playlist_status',
      'currentIndex': 1,
      'items': [
        {'index': 0, 'title': 'One'},
        {'index': 1, 'title': 'Two'},
      ],
    }));
    expect(f.sender.canPlaylistJump, isTrue);
    expect(await f.sender.queueAdd(first), isTrue);
    expect(f.transport.queued, [first]);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: NowCastingScreen(controller: f.sender)),
    ));
    expect(find.text('PLAYLIST  ·  2 ITEMS'), findsOneWidget);
    await tester.tap(find.text('One'));
    await tester.pump();
    expect(f.transport.jumps, [0]);
  });

  testWidgets('native v1 queue policy still gates playlist UI and execution',
      (tester) async {
    final f = await make(protocol: TvProtocol.playBridge, capabilities: {
      'version': 1,
      'surfaces': {
        'player': {
          'engines': {
            'mpv': {
              'transport': ['play', 'pause', 'stop']
            },
          },
        },
      },
    });
    f.sender.handleReceiverMessage(jsonEncode({
      'type': 'status',
      'state': 'playing',
      'title': 'One',
    }));
    f.sender.handleReceiverMessage(jsonEncode({
      'type': 'playlist_status',
      'items': [
        {'index': 0, 'title': 'One'},
        {'index': 1, 'title': 'Two'},
      ],
    }));
    expect(f.sender.canPlaylistJump, isFalse);
    expect(await f.sender.playlistJump(1), isFalse);
    expect(f.transport.jumps, isEmpty);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: NowCastingScreen(controller: f.sender)),
    ));
    expect(find.textContaining('PLAYLIST'), findsNothing);
  });
}
