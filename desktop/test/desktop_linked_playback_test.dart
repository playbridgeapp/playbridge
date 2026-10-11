import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/bridge_paths.dart';
import 'package:playbridge_desktop/desktop_linked_playback.dart';
import 'package:playbridge_desktop/extension_bridge.dart';
import 'package:playbridge_desktop/linked_playback.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'extension_device_selection_test.dart' as fixtures;

class Engine extends PlayerEngine {
  @override
  String state = 'idle';
  @override
  int positionMs = 0;
  @override
  int durationMs = 100000;
  int stops = 0;
  int opens = 0;
  @override
  dynamic get tracks => null;
  @override
  dynamic get track => null;
  @override
  Future<void> setAudioTrack(dynamic value) async {}
  @override
  Future<void> setSubtitleTrack(dynamic value) async {}
  @override
  Future<void> open(QueueItem item) => openPlaylist([item], 0);
  @override
  Future<void> openPlaylist(List<QueueItem> items, int index,
      {bool play = true}) async {
    opens++;
    state = play ? 'playing' : 'paused';
    notifyListeners();
  }

  @override
  Future<void> resume() async {
    state = 'playing';
    notifyListeners();
  }

  @override
  Future<void> pause() async {
    state = 'paused';
    notifyListeners();
  }

  @override
  Future<void> seek(Duration value) async {
    positionMs = value.inMilliseconds;
  }

  @override
  Future<void> stop() async {
    stops++;
    state = 'idle';
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    super.dispose();
  }
}

class Transport extends fixtures.FakeTransport {
  final playlists = <PlaylistPayload>[];
  final additions = <PlayPayload>[];
  final jumps = <int>[];
  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    playlists.add(PlaylistPayload()..mergeFromMessage(playlist));
    return true;
  }

  @override
  Future<bool> queueAdd(PlayPayload item) async {
    additions.add(PlayPayload()..mergeFromMessage(item));
    return true;
  }

  @override
  Future<bool> playlistJump(int index) async {
    jumps.add(index);
    return true;
  }
}

Map<String, dynamic> media(String id) =>
    {'id': id, 'url': 'https://media.example/$id.mp4', 'title': 'Episode $id'};
Future<void> waitFor(bool Function() condition) async {
  final deadline = DateTime.now().add(const Duration(seconds: 5));
  while (!condition() && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(condition(), true);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() async => StreamProxyServer.instance.stop());
  Future<
      ({
        PlayerController player,
        TvSenderController sender,
        Transport transport
      })> fixture() async {
    final transport = Transport();
    final store = await TvConnectionStore.load();
    await store.upsert(fixtures.saved('tv'));
    final sender = TvSenderController(
        identity: await PairingStore.load(),
        store: store,
        transport: transport);
    sender.bindTransportForTest(discoveredDevices: [fixtures.discovered('tv')]);
    final player = PlayerController(engineForTest: Engine());
    addTearDown(sender.dispose);
    addTearDown(player.dispose);
    return (player: player, sender: sender, transport: transport);
  }

  test(
      'local linked media uses the actual queue with proxy policy and resume/subtitles',
      () async {
    final f = await fixture();
    await StreamProxyServer.instance.start();
    final events = <Map>[];
    final coordinator =
        LinkedPlaybackCoordinator(DesktopLinkedPlayback(f.player, f.sender));
    addTearDown(coordinator.dispose);
    final owner = Object();
    final opened = await coordinator.handle({
      'type': 'linked_play',
      'bridgeRequestId': 'open',
      'destination': 'local',
      'origin': 'https://streams.example',
      'allowedPrivateOrigins': ['http://192.168.1.3'],
      'payload': {
        'items': [
          {
            ...media('a'),
            'startPositionMs': 12000,
            'headers': {'Cookie': 'fixture'},
            'subtitleResources': [
              {
                'url': 'http://192.168.1.3/sub.vtt',
                'headers': {'Authorization': 'fixture'}
              }
            ]
          }
        ],
        'startIndex': 0,
        'skipPreplay': true
      }
    }, owner, events.add);
    expect(opened['ok'], true);
    final queued = f.player.queue.single;
    expect(queued.enforcePageNetworkPolicy, true);
    expect(queued.allowedPrivateOrigins, ['http://192.168.1.3']);
    expect(queued.headers, isNull);
    expect(StreamProxyServer.instance.ownsUrl(queued.url), true);
    expect(StreamProxyServer.instance.ownsUrl(queued.subtitles!.single), true);
    expect(queued.originalHeaders, {'Cookie': 'fixture'});
    expect(queued.skipPreplay, true);
    expect(f.player.positionMs, 12000);
    Future<Map> op(String type, Map? payload) => coordinator.handle({
          'type': type,
          'bridgeRequestId': type,
          'sessionId': opened['sessionId'],
          if (payload != null) 'payload': payload
        }, owner, events.add);
    await op('linked_ping', null);
    expect(
        (await op('linked_append', {
          'items': [media('b')]
        }))['ok'],
        true);
    expect(f.player.queue, hasLength(2));
    expect((await op('linked_jump', {'index': 1}))['ok'], true);
    expect(f.player.currentIndex, 1);
    await op('linked_unlink', null);
    expect(f.player.queue, hasLength(2));
    expect(f.player.state, 'playing');
    expect(coordinator.controllerOrigin, isNull);
  });

  test('PlayBridge uses playlist and queue_add and emits receiver progress',
      () async {
    final f = await fixture();
    await f.sender.selectDevice('playBridge:tv');
    final events = <Map>[];
    final coordinator =
        LinkedPlaybackCoordinator(DesktopLinkedPlayback(f.player, f.sender));
    addTearDown(coordinator.dispose);
    final owner = Object();
    final opened = await coordinator.handle({
      'type': 'linked_open',
      'bridgeRequestId': 'open',
      'origin': 'https://streams.example',
      'allowedPrivateOrigins': ['http://192.168.1.3'],
      'payload': {
        'items': [media('a')],
        'startIndex': 0,
        'metadata': {'title': 'Series'}
      }
    }, owner, events.add);
    expect(opened['ok'], true);
    final playlist = f.transport.playlists.single;
    expect(playlist.items.single.detectedBy, 'linked_page');
    expect(playlist.items.single.allowedPrivateOrigins, ['http://192.168.1.3']);
    expect(playlist.hasProgressWebhook(), false);
    expect(playlist.visualMetadata.title, 'Series');
    Future<Map> op(String type, Map? payload) => coordinator.handle({
          'type': type,
          'bridgeRequestId': type,
          'sessionId': opened['sessionId'],
          if (payload != null) 'payload': payload
        }, owner, events.add);
    await op('linked_ping', null);
    expect(
        (await op('linked_append', {
          'items': [media('b')]
        }))['ok'],
        true);
    expect(f.transport.additions.single.url, 'https://media.example/b.mp4');
    await op('linked_jump', {'index': 1});
    expect(f.transport.jumps, [1]);
    f.transport.text.add(jsonEncode({
      'type': 'playlist_status',
      'currentIndex': 1,
      'items': [
        {'index': 0, 'title': 'A'},
        {'index': 1, 'title': 'B'}
      ]
    }));
    f.transport.text.add(jsonEncode({
      'type': 'status',
      'state': 'paused',
      'position': 12000,
      'duration': 24000,
      'title': 'B',
      'playbackId': 'tv-playback'
    }));
    await waitFor(() => events.any((event) =>
        event['event'] == 'statechange' &&
        (event['detail'] as Map)['state'] == 'paused'));
    final state = events
        .lastWhere((event) => event['event'] == 'statechange')['detail'] as Map;
    expect(state['currentIndex'], 1);
    expect(state['positionMs'], 12000);
    expect(state['durationMs'], 24000);
    await f.sender.disconnect();
    expect(coordinator.sessionId, isNull);
    expect(events.last['detail'], {'reason': 'receiver_changed'});
  });

  test('native bridge routes linked IDs independently of blocked legacy FIFO',
      () async {
    final directory = await Directory.systemTemp.createTemp('linked-wire-');
    try {
      await IOOverrides.runWithIOOverrides(() async {
        final f = await fixture();
        await StreamProxyServer.instance.start();
        final gate = Completer<void>();
        var showing = false;
        final bridge =
            ExtensionBridge(f.sender, f.player, onShowApp: (_) async {
          showing = true;
          await gate.future;
        });
        Socket? socket;
        StreamSubscription<String>? subscription;
        final frames = <Map>[];
        try {
          await bridge.start();
          final info =
              jsonDecode(await File(bridgeFilePath()).readAsString()) as Map;
          final client = await Socket.connect(
              InternetAddress.loopbackIPv4, info['port'] as int);
          socket = client;
          subscription = client
              .cast<List<int>>()
              .transform(utf8.decoder)
              .transform(const LineSplitter())
              .listen((line) => frames.add(jsonDecode(line) as Map));
          void send(Map value) => client.writeln(jsonEncode(value));
          send({'token': info['token']});
          await waitFor(() => frames.any((frame) => frame['type'] == 'state'));
          expect((frames.last['capabilities'] as Map)['linkedPlayback'], 1);
          send({'cmd': 'show_app'});
          await waitFor(() => showing);
          send({
            'type': 'linked_open',
            'bridgeRequestId': 'invalid-open',
            'origin': 'https://streams.example',
            'payload': {'items': []}
          });
          await waitFor(() => frames
              .any((frame) => frame['bridgeRequestId'] == 'invalid-open'));
          expect(frames.last, {
            'type': 'linked_result',
            'bridgeRequestId': 'invalid-open',
            'ok': false,
            'error': 'invalid_request'
          });
          expect(frames.where((frame) => frame['type'] == 'result'), isEmpty);
          gate.complete();
          await waitFor(() => frames.any((frame) => frame['type'] == 'result'));
          send({
            'type': 'linked_play',
            'bridgeRequestId': 'start',
            'origin': 'https://streams.example',
            'destination': 'local',
            'payload': {
              'items': [media('a')],
              'startIndex': 0
            }
          });
          await waitFor(
              () => frames.any((frame) => frame['bridgeRequestId'] == 'start'));
          final opened = frames
              .singleWhere((frame) => frame['bridgeRequestId'] == 'start');
          expect(opened['ok'], true);
          send({
            'type': 'linked_ping',
            'bridgeRequestId': 'ready',
            'sessionId': opened['sessionId']
          });
          await waitFor(
              () => frames.any((frame) => frame['bridgeRequestId'] == 'ready'));
          expect(frames.where((frame) => frame['event'] == 'needitems'),
              hasLength(1));
          send({
            'type': 'linked_unlink',
            'bridgeRequestId': 'release',
            'sessionId': opened['sessionId'],
            'reason': 'navigation'
          });
          await waitFor(() =>
              frames.any((frame) => frame['bridgeRequestId'] == 'release'));
          expect(
              frames
                  .singleWhere((frame) => frame['event'] == 'ended')['detail'],
              {'reason': 'navigation'});
          expect(
              frames.where((frame) => frame['type'] == 'result'), hasLength(1));
          expect(frames.where((frame) => frame['type'] == 'linked_result'),
              hasLength(4));
          expect(f.player.state, 'playing');
        } finally {
          if (!gate.isCompleted) gate.complete();
          socket?.destroy();
          await subscription?.cancel();
          await Future<void>.delayed(const Duration(milliseconds: 20));
          await bridge.stop();
        }
      }, fixtures.BridgeFiles(directory));
    } finally {
      await directory.delete(recursive: true);
    }
  });
}
