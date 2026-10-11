import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/bridge_paths.dart';
import 'package:playbridge_desktop/extension_bridge.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

TvRecord saved(String id, {String? name}) => TvRecord(
    uuid: id,
    name: name ?? id,
    host: '192.0.2.1',
    port: 8765,
    token: 'fixture-token',
    certFingerprint: 'sha256/fixture-pin',
    lastConnected: DateTime(2026));
DiscoveredTv discovered(String id, {String? name, int? port = 8765}) =>
    DiscoveredTv(
        uuid: id,
        protocol: TvProtocol.playBridge,
        name: name ?? id,
        host: '192.0.2.2',
        port: port,
        wssPort: null);

class FakeTransport implements TvTransport {
  final states = StreamController<SenderConnectionState>.broadcast();
  final creds = StreamController<TvCredentials>.broadcast();
  final text = StreamController<String>.broadcast();
  final codes = StreamController<String>.broadcast();
  int connections = 0;
  int disconnects = 0;
  int generation = 0;
  String? token;
  String? pin;
  DiscoveredTv? target;
  bool fail = false;
  bool pair = false;
  Completer<void>? gate;
  @override
  TvProtocol get protocol => TvProtocol.playBridge;
  @override
  Stream<SenderConnectionState> get state => states.stream;
  @override
  Stream<TvCredentials> get credentials => creds.stream;
  @override
  Stream<String> get messages => text.stream;
  @override
  Stream<String> get sasCode => codes.stream;
  @override
  Map<String, dynamic> get capabilities => {};
  @override
  Future<bool> sendContextQuery() async => true;
  @override
  Future<void> connect(
      {required DiscoveredTv tv,
      required String deviceName,
      required String deviceUUID,
      String? token,
      String? expectedPin}) async {
    final current = ++generation;
    connections++;
    target = tv;
    this.token = token;
    pin = expectedPin;
    states.add(SenderConnectionState.connecting);
    if (gate != null) await gate!.future;
    if (current != generation) return;
    if (pair) {
      states.add(SenderConnectionState.waitingForCodeInput);
    } else {
      states.add(
          fail ? SenderConnectionState.error : SenderConnectionState.connected);
    }
  }

  @override
  Future<void> disconnect() async {
    generation++;
    disconnects++;
    states.add(SenderConnectionState.disconnected);
  }

  @override
  Future<void> dispose() async {
    await states.close();
    await creds.close();
    await text.close();
    await codes.close();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class RoutingSender extends TvSenderController {
  RoutingSender(
      {required super.identity, required super.store, required super.transport})
      : super(
            reconnectDelay: const Duration(milliseconds: 1),
            reconnectGiveUp: 1);
  final casts = <String>[];
  int scans = 0;
  @override
  Future<bool> castUrl(String url,
      {Map<String, String>? headers,
      String? title,
      String? contentType,
      String? playlistBody,
      String? audioUrl}) async {
    casts.add(url);
    return true;
  }

  @override
  Future<void> rescan() async {
    scans++;
    notifyListeners();
  }
}

class LocalPlayer extends ChangeNotifier implements PlayerController {
  final plays = <String>[];
  String playbackState = 'idle';
  void updateState(String value) {
    playbackState = value;
    notifyListeners();
  }

  @override
  String get state => playbackState;
  @override
  Future<void> playUrl(String url,
      {String? title,
      Map<String, String>? headers,
      List<String>? subtitles,
      String? contentType,
      String? declaredMediaKind,
      int? displayDurationMs,
      String? playlistBody,
      String? audioUrl,
      bool isRemote = false,
      bool localUserInitiated = false}) async {
    plays.add(url);
  }

  @override
  Future<void> dispose() async {
    super.dispose();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

base class BridgeFiles extends IOOverrides {
  BridgeFiles(this.directory);
  final Directory directory;
  @override
  Directory createDirectory(String path) =>
      super.createDirectory(path == bridgeDirPath() ? directory.path : path);
  @override
  File createFile(String path) => super.createFile(
      path == bridgeFilePath() ? '${directory.path}/bridge.json' : path);
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  Future<
      ({
        RoutingSender sender,
        TvConnectionStore store,
        FakeTransport transport
      })> fixture({
    List<TvRecord> records = const [],
    List<DiscoveredTv> devices = const [],
    PlaybackSelection? selection,
  }) async {
    final store = await TvConnectionStore.load();
    for (final record in records) {
      await store.upsert(record);
    }
    if (selection != null) await store.setSelection(selection);
    final transport = FakeTransport();
    final sender = RoutingSender(
        identity: await PairingStore.load(),
        store: store,
        transport: transport);
    sender.bindTransportForTest(discoveredDevices: devices);
    addTearDown(sender.dispose);
    return (sender: sender, store: store, transport: transport);
  }

  test('selection defaults local, persists only identity and forget resets it',
      () async {
    final store = await TvConnectionStore.load();
    await store.upsert(saved('tv'));
    expect(store.selection.isLocal, isTrue);
    await store
        .setSelection(const PlaybackSelection.tv(TvProtocol.playBridge, 'tv'));
    expect((await TvConnectionStore.load()).selection.identityKey,
        'playBridge:tv');
    final prefs = await SharedPreferences.getInstance();
    expect(jsonDecode(prefs.getString('pb.sender.playback_selection')!),
        {'kind': 'tv', 'protocol': 'playBridge', 'uuid': 'tv'});
    await store.forget('other');
    expect(store.selection.identityKey, 'playBridge:tv');
    await store.forget('tv');
    expect(store.selection.isLocal, isTrue);
    expect(
        PlaybackSelection.fromJson(
            {'kind': 'tv', 'protocol': 'unknown', 'uuid': 'x'}).isLocal,
        isTrue);
  });

  test('launch restores selected TV with saved token/pin and fresh addresses',
      () async {
    final f = await fixture(
        records: [saved('tv')],
        devices: [discovered('tv')],
        selection: const PlaybackSelection.tv(TvProtocol.playBridge, 'tv'));
    await f.sender.restoreSelection();
    await settle();
    expect(f.transport.token, 'fixture-token');
    expect(f.transport.pin, 'sha256/fixture-pin');
    expect(f.transport.target!.host, '192.0.2.2');
    expect(f.sender.selectedTvConnected, isTrue);
    f.transport.fail = true;
    f.transport.states.add(SenderConnectionState.disconnected);
    await Future<void>.delayed(const Duration(milliseconds: 30));
    expect(f.sender.selection.identityKey, 'playBridge:tv');
    expect(f.store.selection.identityKey, 'playBridge:tv');
    await f.sender.disconnect();
    await settle();
    final connections = f.transport.connections;
    f.transport.states.add(SenderConnectionState.error);
    await settle();
    expect(f.transport.connections, connections);
    expect(f.store.selection.isLocal, isTrue);
  });

  test(
      'device union sorts active, saved online, saved offline, then discovered',
      () async {
    final f = await fixture(records: [
      saved('offline', name: 'Alpha'),
      saved('live', name: 'Zulu'),
      saved('active', name: 'Last')
    ], devices: [
      discovered('new', name: 'Aardvark'),
      discovered('live', name: 'Fresh Zulu')
    ], selection: const PlaybackSelection.tv(TvProtocol.playBridge, 'active'));
    final rows = f.sender.extensionDevices;
    expect(rows.map((row) => row['id']), [
      'playBridge:active',
      'playBridge:live',
      'playBridge:offline',
      'playBridge:new'
    ]);
    expect(rows.first['active'], isTrue);
    expect(rows.first['online'], isFalse);
    expect(rows[1]['name'], 'Fresh Zulu');
    expect(rows[1]['saved'], isTrue);
    expect(rows[1]['online'], isTrue);
    expect(rows.last['paired'], isFalse);
    expect(rows.last['saved'], isFalse);
    for (final row in rows) {
      expect(row.keys, isNot(contains('token')));
      expect(row.keys, isNot(contains('host')));
    }
  });

  test('unknown, unpaired and nonconnectable choices never change selection',
      () async {
    final f = await fixture(
        devices: [discovered('new'), discovered('bad', port: null)]);
    expect((await f.sender.selectDevice('missing'))['error'], 'unknown_device');
    expect((await f.sender.selectDevice('playBridge:new'))['error'],
        'pairing_required');
    expect((await f.sender.selectDevice('playBridge:bad'))['error'],
        'not_connectable');
    expect(f.store.selection.isLocal, isTrue);
    expect(f.transport.connections, 0);
    f.transport.pair = true;
    await f.sender.connectToDiscovered(discovered('new'));
    await settle();
    expect(f.store.selection.isLocal, isTrue);
    f.transport.creds
        .add(const TvCredentials('new-pair-token', 'sha256/new-pin'));
    f.transport.states.add(SenderConnectionState.connected);
    await settle();
    expect(f.store.selection.identityKey, 'playBridge:new');
  });

  test('cancelling new TV pairing preserves the previous saved selection',
      () async {
    final f = await fixture(
        records: [saved('previous')],
        devices: [discovered('new')],
        selection:
            const PlaybackSelection.tv(TvProtocol.playBridge, 'previous'));
    f.transport.pair = true;
    await f.sender.connectToDiscovered(discovered('new'));
    await settle();
    expect(f.sender.state, SenderConnectionState.waitingForCodeInput);
    await f.sender.cancelPairing();
    await settle();
    expect(f.transport.disconnects, 1);
    expect(f.sender.state, SenderConnectionState.disconnected);
    expect(f.sender.selection.identityKey, 'playBridge:previous');
    expect((await TvConnectionStore.load()).selection.identityKey,
        'playBridge:previous');

    // A late pairing completion must not overwrite the preserved destination.
    f.transport.creds
        .add(const TvCredentials('new-pair-token', 'sha256/new-pin'));
    f.transport.states.add(SenderConnectionState.connected);
    await settle();
    expect(f.sender.selection.identityKey, 'playBridge:previous');
    expect(f.store.selection.identityKey, 'playBridge:previous');
    expect(f.store.byIdentity(TvProtocol.playBridge, 'new'), isNull);

    await f.sender.disconnect();
    expect(f.sender.selection.isLocal, isTrue);
    expect((await TvConnectionStore.load()).selection.isLocal, isTrue);
  });

  test('saved success and offline/failure/timeout preserve the chosen TV',
      () async {
    final f = await fixture(records: [saved('tv')]);
    expect(await f.sender.selectDevice('playBridge:tv'),
        {'ok': true, 'target': 'tv', 'targetName': 'tv'});
    await f.sender.disconnect();
    await settle();
    f.transport.fail = true;
    expect((await f.sender.selectDevice('playBridge:tv'))['error'], 'offline');
    f.sender.bindTransportForTest(discoveredDevices: [discovered('tv')]);
    expect((await f.sender.selectDevice('playBridge:tv'))['error'],
        'connect_failed');
    f.transport.fail = false;
    f.transport.gate = Completer<void>();
    expect(
        (await f.sender.selectDevice('playBridge:tv',
            timeout: const Duration(milliseconds: 20)))['ok'],
        isFalse);
    f.transport.gate!.complete();
    await settle();
    expect(f.sender.selectedTvConnected, isFalse);
    expect(f.store.selection.identityKey, 'playBridge:tv');
    await f.sender.forget('tv');
    expect(f.sender.selection.isLocal, isTrue);
  });

  test(
      'bridge deduplicates state per client but always replies to list_devices',
      () async {
    final directory = await Directory.systemTemp.createTemp('extension-state-');
    try {
      await IOOverrides.runWithIOOverrides(() async {
        final f = await fixture();
        final player = LocalPlayer();
        final bridge = ExtensionBridge(f.sender, player);
        final sockets = <Socket>[];
        final clients = <StreamIterator<String>>[];
        try {
          await bridge.start();
          final info =
              jsonDecode(await File(bridgeFilePath()).readAsString()) as Map;
          Future<String> next(int client) async {
            expect(
                await clients[client]
                    .moveNext()
                    .timeout(const Duration(seconds: 3)),
                isTrue);
            return clients[client].current;
          }

          void send(int client, Map value) =>
              sockets[client].writeln(jsonEncode(value));

          String? initial;
          for (var client = 0; client < 2; client++) {
            final socket = await Socket.connect(
                InternetAddress.loopbackIPv4, info['port'] as int);
            sockets.add(socket);
            clients.add(StreamIterator(socket
                .cast<List<int>>()
                .transform(utf8.decoder)
                .transform(const LineSplitter())));
            send(client, {'token': info['token']});
            expect(
                jsonDecode(await next(client)), {'type': 'hello', 'ok': true});
            final state = await next(client);
            expect(jsonDecode(state)['type'], 'state');
            initial ??= state;
            expect(state, initial);
          }

          // Duplicate sender/player notifications must not enqueue state frames.
          // A command result provides a barrier without relying on socket timing.
          Future<void> expectNoPushes() async {
            for (var client = 0; client < 2; client++) {
              send(client, {'cmd': 'refresh_devices'});
              expect(jsonDecode(await next(client)),
                  {'type': 'result', 'ok': true});
            }
          }

          for (var i = 0; i < 15; i++) {
            await f.sender.rescan();
            player.updateState('idle');
          }
          await expectNoPushes();
          for (var client = 0; client < 2; client++) {
            send(client, {'cmd': 'list_devices'});
            send(client, {'cmd': 'list_devices'});
            expect(await next(client), initial);
            expect(await next(client), initial);
          }
          await expectNoPushes();

          player.updateState('playing');
          String? changed;
          for (var client = 0; client < 2; client++) {
            final state = await next(client);
            expect(jsonDecode(state)['state'], 'playing');
            expect(state, isNot(initial));
            changed ??= state;
            expect(state, changed);
          }
          player.updateState('playing');
          await expectNoPushes();
          player.updateState('idle');
          for (var client = 0; client < 2; client++) {
            expect(await next(client), initial);
          }
          send(0, {'cmd': 'select_local'});
          expect(jsonDecode(await next(0))['target'], 'local');
          await expectNoPushes();
        } finally {
          for (final socket in sockets) {
            socket.destroy();
          }
          for (final client in clients) {
            await client.cancel();
          }
          await settle();
          await bridge.stop();
          await player.dispose();
        }
      }, BridgeFiles(directory));
    } finally {
      await directory.delete(recursive: true);
    }
  });

  test(
      'bridge casts by selection, one-off local leaves it intact, commands reply FIFO',
      () async {
    final directory =
        await Directory.systemTemp.createTemp('extension-selection-');
    try {
      await IOOverrides.runWithIOOverrides(() async {
        final f = await fixture(records: [saved('tv', name: 'Living Room')]);
        final player = LocalPlayer();
        final shows = <String?>[];
        final showGate = Completer<void>();
        final bridge = ExtensionBridge(f.sender, player, onShowApp: (id) async {
          shows.add(id);
          await showGate.future;
        });
        Socket? socket;
        StreamIterator<String>? frames;
        try {
          await bridge.start();
          final info =
              jsonDecode(await File(bridgeFilePath()).readAsString()) as Map;
          socket = await Socket.connect(
              InternetAddress.loopbackIPv4, info['port'] as int);
          final lines = StreamIterator(socket
              .cast<List<int>>()
              .transform(utf8.decoder)
              .transform(const LineSplitter()));
          frames = lines;
          Future<Map> next(String type) async {
            while (await lines.moveNext().timeout(const Duration(seconds: 3))) {
              final value = jsonDecode(lines.current) as Map;
              if (value['type'] == type) return value;
            }
            throw StateError('Missing $type');
          }

          void send(Map value) => socket!.writeln(jsonEncode(value));
          send({'token': info['token']});
          await next('hello');
          final initial = await next('state');
          expect(initial['capabilities'],
              {'selectDevice': 1, 'linkedPlayback': 1});
          expect(initial['selection'], {'kind': 'local'});
          send({'cmd': 'select_device', 'id': 'playBridge:tv'});
          expect((await next('result'))['targetName'], 'Living Room');
          send({
            'cmd': 'cast',
            'url': 'https://fixture/movie',
            'destination': 'local'
          });
          expect(await next('result'), {
            'type': 'result',
            'ok': true,
            'target': 'local',
            'targetName': 'This computer'
          });
          expect(f.sender.selection.identityKey, 'playBridge:tv');
          expect(player.plays, ['https://fixture/movie']);
          send({'cmd': 'cast', 'url': 'https://fixture/tv'});
          expect((await next('result'))['target'], 'tv');
          expect(f.sender.casts, ['https://fixture/tv']);
          f.transport.fail = true;
          f.transport.states.add(SenderConnectionState.disconnected);
          await settle();
          send({'cmd': 'cast', 'url': 'https://fixture/offline'});
          expect(await next('result'), {
            'type': 'result',
            'ok': false,
            'error': 'destination_offline',
            'targetName': 'Living Room'
          });
          expect(player.plays, ['https://fixture/movie']);
          send({'cmd': 'show_app', 'pairId': 'playBridge:new'});
          send({'cmd': 'refresh_devices'});
          await settle();
          expect(shows, ['playBridge:new']);
          expect(f.sender.scans, 0);
          showGate.complete();
          expect(await next('result'), {'type': 'result', 'ok': true});
          expect(await next('result'), {'type': 'result', 'ok': true});
          expect(f.sender.scans, 1);
          send({'cmd': 'select_local'});
          expect((await next('result'))['target'], 'local');
          expect(f.store.selection.isLocal, isTrue);
        } finally {
          socket?.destroy();
          await frames?.cancel();
          await settle();
          await bridge.stop();
          await player.dispose();
        }
      }, BridgeFiles(directory));
    } finally {
      await directory.delete(recursive: true);
    }
  });
}
