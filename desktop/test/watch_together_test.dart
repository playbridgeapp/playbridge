import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/together/desktop_together.dart';
import 'package:playbridge_desktop/together/saved_parties.dart';
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
      })> fixture({SavedPartyVault? vault}) async {
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
      savedParties: SavedPartyStore(vault ?? MemorySavedPartyVault()),
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

  test('the party video ending stops playback and keeps this computer watching',
      () async {
    final f = await fixture();
    await joinAndLoad(f);
    final stops = f.engine.stops;

    // A real end: mpv reports end of file at the video's length.
    f.engine.positionMs = f.engine.durationMs;
    f.player.notifyCompletedForTest();
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(f.engine.stops, stops + 1, reason: 'closes player at end of video');
    expect(f.together.state, isA<TogetherInParty>());
    expect(f.together.party!.watching, isTrue);
    expect(f.player.inParty, isFalse);
    expect(f.player.currentItemId, isNull);
    expect(f.room.names, isNot(contains('screen')));

    // The next party video plays here too.
    deliver(f.client, f.room, load('b'));
    await waitFor(() => f.player.currentItemId == 'b');
    expect(f.together.party!.watching, isTrue);
    expect(f.player.inParty, isTrue);
  });

  test(
      'ended event without clear stops player and returns party to waiting as a screen',
      () async {
    final f = await fixture();
    await joinAndLoad(f);
    final stops = f.engine.stops;

    deliver(f.client, f.room, {'event': 'ended', 'item': 'a'});
    await waitFor(() => f.engine.stops == stops + 1);

    expect(f.together.state, isA<TogetherInParty>());
    expect(f.together.party!.item, isNull);
    expect(f.together.party!.watching, isTrue);
    expect(f.player.inParty, isFalse);
    expect(f.player.currentItemId, isNull);
    expect(f.room.names, isNot(contains('screen')));

    deliver(f.client, f.room, load('b'));
    await waitFor(() => f.player.currentItemId == 'b');
    expect(f.together.party!.watching, isTrue);
    expect(f.player.inParty, isTrue);
  });

  test('stopping the party video here stops watching here', () async {
    final f = await fixture();
    await joinAndLoad(f);
    await f.player.stop();
    expect(f.room.last('screen'), {'command': 'screen', 'screen': false});
    expect(f.together.party!.watching, isFalse);
  });

  test('a length that arrives after the live wait is still reported', () async {
    final f = await fixture();
    f.engine.durationMs = 0;
    await joinAndLoad(f);
    expect(f.room.last('loaded'), {'command': 'loaded', 'item': 'a'});

    f.engine.durationMs = 1420000;
    f.engine.changed();
    expect(f.room.last('loaded'),
        {'command': 'loaded', 'item': 'a', 'duration_ms': 1420000});
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

  test(
      'says when a refused party may try again, and when the relay is unreachable',
      () async {
    final f = await fixture();
    await joinAndLoad(f);
    deliver(f.client, f.room, {'event': 'unreachable'});
    expect(f.together.party!.notice, "Can't reach relay.test. Still trying…");

    deliver(f.client, f.room, {
      'event': 'stopped',
      'room': 'r1',
      'reason': 'room_limit',
      'retry_after': 700,
    });
    await waitFor(() => f.together.state is TogetherEnded);
    expect(
        (f.together.state as TogetherEnded).message,
        'Too many watch parties are open on your network. '
        'End one, or try again in 12 minutes.');
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

  test('adds the connected TV as its own without host duties', () async {
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
    // Only the host acts as host: the TV gets no host duties.
    expect(f.room.names, isNot(contains('delegate')));
    expect(f.together.party!.own, {'tv1'});
  });

  group('saved parties', () {
    const roomId = '00000000000000000000000000000001';
    final endsAt = DateTime.now().millisecondsSinceEpoch + 3600000;
    Map<String, Object?> savedRoom({bool host = true}) => {
          ...room(host: host),
          'room': roomId,
          'ends_at': endsAt.toDouble(),
          'saved': 'SAVED-TEXT',
        };

    test('a host comes back as host after a restart, from the list or the link',
        () async {
      final vault = MemorySavedPartyVault();
      final first = await fixture(vault: vault);
      first.together.start(watchHere: false);
      deliver(first.client, first.room, savedRoom());
      await waitFor(() => first.together.savedParties.parties.isNotEmpty);
      final saved = first.together.savedParties.parties.single;
      expect(saved.host, isTrue);
      expect(saved.endsAtMs, endsAt);
      expect(saved.title, 'Your party');
      expect(vault.items.values.single, contains('SAVED-TEXT'));
      expect(saved.toString(), isNot(contains('SAVED-TEXT')));

      // Opening the current party's link changes nothing.
      first.together.join(invite, watchHere: false);
      expect(first.room.names, isNot(contains('join')));

      // A new run of the app: the link rejoins with the saved keys.
      final second = await fixture(vault: vault);
      await second.together.savedParties.load();
      expect(
          second.together.savedParties.parties.map((p) => p.roomId), [roomId]);
      second.together.join(invite, watchHere: false);
      expect(second.room.names, isNot(contains('join')));
      expect(second.room.last('rejoin')['saved'], 'SAVED-TEXT');
      expect(second.together.state, isA<TogetherStarting>());
    });

    test('a rejoin restores own TVs, and an ended party is forgotten',
        () async {
      final vault = MemorySavedPartyVault()
        ..items[roomId] = jsonEncode(SavedParty(
          roomId: roomId,
          relay: 'relay.test',
          host: true,
          saved: 'SAVED-TEXT',
          joinedAtMs: DateTime.now().millisecondsSinceEpoch,
          endsAtMs: endsAt,
          own: const [SavedOwnDevice('tv1', 'tvbox')],
        ).toJson());
      final f = await fixture(vault: vault);
      await f.together.savedParties.load();
      f.together
          .rejoin(f.together.savedParties.parties.single, watchHere: false);
      deliver(f.client, f.room, savedRoom());
      await waitFor(() => f.room.names.contains('add_own'));
      expect(f.room.last('add_own'),
          {'command': 'add_own', 'member': 'tv1', 'box_pk': 'tvbox'});
      await waitFor(() => f.together.party!.own.contains('tv1'));

      deliver(f.client, f.room,
          {'event': 'stopped', 'room': roomId, 'reason': 'expired'});
      await waitFor(() => vault.items.isEmpty);
      expect(f.together.savedParties.parties, isEmpty);
    });

    test('a guest remembers the host\'s name', () async {
      final f = await fixture();
      f.together.join(invite, watchHere: false);
      deliver(f.client, f.room, savedRoom(host: false));
      await waitFor(() => f.together.savedParties.parties.isNotEmpty);
      deliver(f.client, f.room, {
        'event': 'member',
        'member': 'h1',
        'name': 'Sam',
        'kind': 'phone',
        'host': true,
      });
      await waitFor(
          () => f.together.savedParties.parties.single.hostName == 'Sam');
      expect(f.together.savedParties.parties.single.title, "Sam's party");
    });
  });

  test('saved party rules', () {
    const now = 1800000000000;
    SavedParty party(String room, {int joinedAt = now, int? endsAt}) =>
        SavedParty(
          roomId: room,
          relay: 'r',
          host: false,
          saved: 's',
          joinedAtMs: joinedAt,
          endsAtMs: SavedParties.endsAt(endsAt, joinedAt),
        );
    expect(party('a').endsAtMs, now + SavedParties.undatedLifetimeMs);
    final many = [
      for (var i = 0; i < 22; i++) party('r$i', joinedAt: now + i),
      party('gone', endsAt: now - 1),
    ];
    final live = SavedParties.live(many, now);
    expect(live.length, SavedParties.maxSaved);
    expect(live.first.roomId, 'r21');
    expect(live.any((p) => p.roomId == 'gone'), isFalse);
    expect(SavedParties.endsIn(party('a', endsAt: now + 130 * 60000), now),
        'Ends in 2 h 10 min');
    expect(SavedParties.endsIn(party('a', endsAt: now + 1000), now),
        'Ends in less than a minute');
    expect(
        watchTogetherRoomId(
            'https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=a&h=b'),
        '0123456789abcdef0123456789abcdef');
    expect(watchTogetherRoomId('https://example.com/watch?v=1'), isNull);
  });

  test('parses a paired phone\'s join and refuses a bad one', () {
    final join = parseCommand(jsonEncode(
        {'type': 'watch_together_join', 'invite': invite, 'from': 'Pixel'}));
    expect(join, isA<WatchTogetherJoinCmd>());
    expect((join as WatchTogetherJoinCmd).from, 'Pixel');
    expect(parseCommand('{"type":"watch_together_join","from":"x"}'),
        isA<UnknownCmd>());
  });

  test(
      'video cleared closes player, returns party to preplay, and stopVideo checks permission',
      () async {
    final f = await fixture();
    await joinAndLoad(f);
    expect(f.player.inParty, isTrue);

    // Guest cannot stop by default; host or Anyone (unlocked) can.
    expect(f.together.stopVideo(), isFalse);
    deliver(f.client, f.room, {'event': 'policy', 'policy': 'anyone'});
    expect(f.together.stopVideo(), isTrue);
    expect(f.room.names.last, 'clear');

    final stops = f.engine.stops;
    deliver(f.client, f.room, {'event': 'video_cleared'});
    await waitFor(() => f.engine.stops > stops);

    expect(f.together.state, isA<TogetherInParty>());
    expect(f.together.party!.item, isNull);
    expect(f.together.party!.watching, isTrue);
    expect(f.player.inParty, isFalse);
  });

  test('shareVideo strips browser-only headers and preserves auth headers',
      () async {
    final f = await fixture();
    f.together.start(watchHere: false);
    deliver(f.client, f.room, room(host: true));

    expect(
      f.together.shareVideo(
        url: 'https://media.example/video.mp4',
        headers: {
          'Range': 'bytes=0-',
          'If-Range': '"abc"',
          'Sec-Fetch-Site': 'same-origin',
          'Host': 'media.example',
          'Referer': 'https://site.example/',
          'Cookie': 'sid=secret',
          'Authorization': 'Bearer tok',
          'User-Agent': 'Mozilla/5.0',
        },
      ),
      'Playing in the watch party',
    );
    final item = f.room.last('load')['item']! as Map;
    expect(item['headers'], {
      'Referer': 'https://site.example/',
      'Cookie': 'sid=secret',
      'Authorization': 'Bearer tok',
      'User-Agent': 'Mozilla/5.0',
    });
  });

  test(
      'party includes this computer in member count and updates its host badge from MemberInfo',
      () async {
    final f = await fixture();
    f.together.join(invite, watchHere: true);
    deliver(f.client, f.room, room(host: false));

    // Count includes this computer immediately on room entry.
    expect(f.together.party!.members.length, 1);
    expect(f.together.party!.members['me']?.name, 'Office Mac');
    expect(f.together.party!.members['me']?.host, isFalse);

    // Remote host joins -> 2 devices.
    deliver(f.client, f.room, {
      'event': 'member',
      'member': 'phone1',
      'name': 'Phone',
      'kind': 'phone',
      'host': true,
    });
    expect(f.together.party!.members.length, 2);

    // Engine promotes this computer as acting host when remote host leaves.
    deliver(f.client, f.room, {'event': 'member_left', 'member': 'phone1'});
    deliver(f.client, f.room, {
      'event': 'member',
      'member': 'me',
      'name': 'Office Mac',
      'kind': 'desktop',
      'host': true,
    });
    expect(f.together.party!.members.length, 1);
    expect(f.together.party!.members['me']?.host, isTrue);

    // Stray member_left for self cannot strip this computer from its own count.
    deliver(f.client, f.room, {'event': 'member_left', 'member': 'me'});
    expect(f.together.party!.members.length, 1);
    expect(f.together.party!.members['me'], isNotNull);
  });
}
