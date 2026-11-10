import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/together/desktop_together.dart';
import 'package:playbridge_desktop/together/watch_together_join.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'desktop_linked_playback_test.dart' show Engine, waitFor;
import 'extension_device_selection_test.dart' as fixtures;

/// The engine's side of a Watch Together handle: records commands, hands out events.
class FakeRoom implements TogetherNative {
  final commands = <Map<String, Object?>>[];
  final pending = <String>[];

  @override
  int abiVersion() => togetherAbiVersion;

  @override
  int start() => 1;

  @override
  bool submit(int handle, String commandJson) {
    commands.add((jsonDecode(commandJson) as Map).cast<String, Object?>());
    return true;
  }

  @override
  String? next(int handle) => pending.isEmpty ? null : pending.removeAt(0);

  @override
  void cancel(int handle) {}

  @override
  void free(int handle) {}

  List<String> get names => [for (final c in commands) c['command']! as String];
  Map<String, Object?> last(String name) =>
      commands.lastWhere((c) => c['command'] == name);
}

const invite = 'https://relay.test/j/00000000000000000000000000000001#k=key';

Map<String, Object?> room({bool host = false}) => {
      'event': 'room',
      'room': 'r1',
      'invite': invite,
      'relay': 'relay.test',
      'member': 'me',
      'box_pk': 'box',
      'sign_pk': 'sign',
      'host': host,
    };

Map<String, Object?> load(String id, {int startMs = 0}) => {
      'event': 'player',
      'command': {
        'action': 'load',
        'item': {
          'id': id,
          'title': 'Clip $id',
          'source': {'type': 'url', 'url': 'https://media.example/$id.mp4'},
        },
        'headers': {'Referer': 'https://media.example/'},
        'start_ms': startMs,
      },
    };

Map<String, Object?> player(String action,
        [Map<String, Object?> extra = const {}]) =>
    {
      'event': 'player',
      'command': {'action': action, ...extra},
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() async => StreamProxyServer.instance.stop());

  Future<
      ({
        DesktopTogether together,
        FakeRoom room,
        TogetherClient client,
        PlayerController player,
        Engine engine,
        fixtures.FakeTransport transport,
        TvSenderController sender,
      })> fixture() async {
    final transport = fixtures.FakeTransport();
    final store = await TvConnectionStore.load();
    await store.upsert(fixtures.saved('tv', name: 'Living Room TV'));
    final identity = await PairingStore.load();
    final sender = TvSenderController(
        identity: identity, store: store, transport: transport);
    sender.bindTransportForTest(discoveredDevices: [fixtures.discovered('tv')]);
    final engine = Engine();
    final player = PlayerController(engineForTest: engine);
    final fake = FakeRoom();
    final client =
        TogetherClient(native: fake, pollInterval: const Duration(hours: 1));
    final together = DesktopTogether(
      player: player,
      sender: sender,
      prefs: identity.prefs,
      deviceName: () => 'Office Mac',
      createClient: () => client,
      liveWait: const Duration(milliseconds: 50),
    );
    addTearDown(together.dispose);
    addTearDown(sender.dispose);
    addTearDown(player.dispose);
    return (
      together: together,
      room: fake,
      client: client,
      player: player,
      engine: engine,
      transport: transport,
      sender: sender,
    );
  }

  void deliver(
      TogetherClient client, FakeRoom room, Map<String, Object?> event) {
    room.pending.add(jsonEncode(event));
    client.poll();
  }

  Future<void> joinAndLoad(
      ({
        DesktopTogether together,
        FakeRoom room,
        TogetherClient client,
        PlayerController player,
        Engine engine,
        fixtures.FakeTransport transport,
        TvSenderController sender,
      }) f) async {
    f.together.join(invite, watchHere: true);
    deliver(f.client, f.room, room());
    deliver(f.client, f.room, load('a', startMs: 5000));
    await waitFor(() => f.room.names.contains('loaded'));
  }

  test(
      'joins as a desktop screen and plays the party video paused, outside history',
      () async {
    final f = await fixture();
    await joinAndLoad(f);

    expect(f.room.commands.first, {
      'command': 'join',
      'invite': invite,
      'name': 'Office Mac',
      'kind': 'desktop',
      'screen': true,
    });
    expect(f.together.party!.watching, isTrue);
    final item = f.player.queue.single;
    expect(item.url, 'https://media.example/a.mp4');
    expect(item.headers, {'Referer': 'https://media.example/'});
    expect(item.skipHistory, isTrue);
    expect(f.player.currentItemId, 'a');
    expect(f.engine.state, 'paused');
    expect(f.engine.positionMs, 5000);
    expect(f.room.last('loaded'), {
      'command': 'loaded',
      'item': 'a',
      'duration_ms': 100000,
    });
  });

  test(
      'play, pause and seek here go to the party; its commands drive the player',
      () async {
    final f = await fixture();
    await joinAndLoad(f);

    await f.player.resume();
    expect(f.engine.state, 'paused', reason: 'the party decides');
    expect(f.room.names.last, 'play');
    await f.player.seek(const Duration(seconds: 42));
    expect(f.room.last('seek'), {'command': 'seek', 'position_ms': 42000});
    expect(f.engine.positionMs, 5000);

    deliver(f.client, f.room, player('play'));
    await waitFor(() => f.engine.state == 'playing');
    expect(f.room.last('snapshot')['playing'], isTrue);
    deliver(f.client, f.room, player('seek', {'position_ms': 30000}));
    await waitFor(() => f.engine.positionMs == 30000);
    deliver(f.client, f.room, player('pause'));
    await waitFor(() => f.engine.state == 'paused');
  });

  test('a live video reports no length after the wait', () async {
    final f = await fixture();
    f.engine.durationMs = 0;
    await joinAndLoad(f);
    expect(f.room.last('loaded'), {'command': 'loaded', 'item': 'a'});

    deliver(f.client, f.room, {'event': 'live', 'live': true});
    expect(f.together.party!.live, isTrue);
    deliver(f.client, f.room, player('go_live'));
    await waitFor(() => f.engine.state == 'playing');
  });

  test('something else playing turns this computer into a remote', () async {
    final f = await fixture();
    await joinAndLoad(f);

    await f.player.playUrl('https://media.example/other.mp4');

    expect(f.room.last('screen'), {'command': 'screen', 'screen': false});
    expect(f.together.party!.watching, isFalse);
    expect(f.player.inParty, isFalse);
    await f.player.pause();
    expect(f.room.names.where((n) => n == 'pause'), isEmpty);
  });

  test('a remote leaves the player alone', () async {
    final f = await fixture();
    f.together.join(invite, watchHere: false);
    deliver(f.client, f.room, room());
    deliver(f.client, f.room, load('a'));
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(f.room.commands.first['screen'], isFalse);
    expect(f.player.queue, isEmpty);
    expect(f.together.playParty(), isTrue);
    expect(f.room.names.last, 'play');
  });

  test('a paired phone adds this computer and gets its keys', () async {
    final f = await fixture();
    final reply = f.together.joinFromPhone(invite, 'Pixel');
    expect(f.room.last('join')['screen'], isTrue);
    deliver(f.client, f.room, room());

    final joined = await reply;
    expect(joined.toJson(), {
      'type': 'watch_together_joined',
      'ok': true,
      'member': 'me',
      'box_pk': 'box',
      'sign_pk': 'sign',
    });
  });

  test('the party ending stops its video here', () async {
    final f = await fixture();
    await joinAndLoad(f);
    final stops = f.engine.stops;

    deliver(f.client, f.room,
        {'event': 'stopped', 'room': 'r1', 'reason': 'room_full'});

    await waitFor(() => f.engine.stops > stops);
    expect(f.together.state, isA<TogetherEnded>());
    expect(f.player.inParty, isFalse);
  });

  test('sharing a video follows who may change it', () async {
    final f = await fixture();
    f.together.join(invite, watchHere: false);
    deliver(f.client, f.room, room());

    expect(f.together.shareVideo(url: 'https://media.example/b.mp4'),
        'Only the host can change the video');
    expect(f.together.shareVideo(url: 'file:///Users/me/b.mp4'),
        'Only web links can play in a watch party');
    deliver(f.client, f.room, {'event': 'policy', 'policy': 'ask'});
    expect(f.together.shareVideo(url: 'https://media.example/b.mp4'),
        'Asked the host to play it');
    expect(f.room.names.last, 'request_play');
    deliver(f.client, f.room, {'event': 'policy', 'policy': 'anyone'});
    expect(f.together.shareVideo(url: 'https://www.media.example/c.mp4'),
        'Playing in the watch party');
    final item = f.room.last('load')['item']! as Map;
    expect(item['site'], 'media.example');
    expect(item['source'],
        {'type': 'url', 'url': 'https://www.media.example/c.mp4'});
  });

  test('adds the connected TV as its own and hands it host duties', () async {
    final f = await fixture();
    f.transport.features = {watchTogetherFeature};
    expect((await f.sender.selectDevice('playBridge:tv'))['ok'], isTrue);
    f.together.start(watchHere: false);
    deliver(f.client, f.room, room(host: true));
    expect(f.sender.selectedTvSupportsWatchTogether, isTrue);

    final adding = f.together.addConnectedTv();
    await waitFor(() => f.transport.raw.isNotEmpty);
    expect(jsonDecode(f.transport.raw.single), {
      'type': 'watch_together_join',
      'invite': invite,
      'from': 'Office Mac',
    });
    f.transport.text.add(jsonEncode({
      'type': 'watch_together_joined',
      'ok': true,
      'member': 'tv1',
      'box_pk': 'tvbox',
      'sign_pk': 'tvsign',
    }));
    await adding;

    expect(f.room.last('add_own'),
        {'command': 'add_own', 'member': 'tv1', 'box_pk': 'tvbox'});
    expect(f.room.last('delegate'),
        {'command': 'delegate', 'member': 'tv1', 'sign_pk': 'tvsign'});
    expect(f.together.party!.own, {'tv1'});
  });

  test('parses a paired phone\'s join and refuses a bad one', () {
    final join = parseCommand(jsonEncode(
        {'type': 'watch_together_join', 'invite': invite, 'from': 'Pixel'}));
    expect(join, isA<WatchTogetherJoinCmd>());
    expect((join as WatchTogetherJoinCmd).from, 'Pixel');
    expect(parseCommand('{"type":"watch_together_join","from":"x"}'),
        isA<UnknownCmd>());
  });
}
