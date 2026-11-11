import 'dart:convert';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:test/test.dart';

final class FakeNative implements TogetherNative {
  final submitted = <Map<String, Object?>>[];
  final pending = <String>[];
  final calls = <String>[];
  int abi = togetherAbiVersion;

  @override
  int abiVersion() => abi;

  @override
  int start() => 7;

  @override
  bool submit(int handle, String commandJson) {
    submitted.add((jsonDecode(commandJson) as Map).cast<String, Object?>());
    return handle == 7;
  }

  @override
  String? next(int handle) => pending.isEmpty ? null : pending.removeAt(0);

  @override
  void cancel(int handle) => calls.add('cancel $handle');

  @override
  void free(int handle) => calls.add('free $handle');
}

void main() {
  late FakeNative native;
  late TogetherClient client;

  setUp(() {
    native = FakeNative();
    client =
        TogetherClient(native: native, pollInterval: const Duration(hours: 1));
  });

  tearDown(() => client.close());

  test('sends commands in the engine format', () {
    const device =
        TogetherDevice('Mac', TogetherDeviceKind.desktop, screen: true);
    client.host('https://relay.test', device);
    client.snapshot(1500, playing: true, buffering: false);
    client.loaded('item1', null);
    client.loaded('item2', 9000);
    client.load(const TogetherItem(
      title: 'Clip',
      site: 'example.com',
      source: TogetherUrl('https://example.com/a.m3u8'),
      headers: {'Referer': 'https://example.com/'},
    ));
    client.reply('r1', allowed: true);
    client.setScreen(false);

    expect(native.submitted, [
      {
        'command': 'host',
        'relay': 'https://relay.test',
        'name': 'Mac',
        'kind': 'desktop',
        'screen': true,
      },
      {
        'command': 'snapshot',
        'position_ms': 1500,
        'playing': true,
        'buffering': false,
      },
      {'command': 'loaded', 'item': 'item1'},
      {'command': 'loaded', 'item': 'item2', 'duration_ms': 9000},
      {
        'command': 'load',
        'item': {
          'title': 'Clip',
          'site': 'example.com',
          'source': {'type': 'url', 'url': 'https://example.com/a.m3u8'},
          'headers': {'Referer': 'https://example.com/'},
          'subtitles': <Object?>[],
          'start_ms': 0,
        },
      },
      {'command': 'reply', 'request': 'r1', 'allowed': true, 'always': false},
      {'command': 'screen', 'screen': false},
    ]);
  });

  test('parses events and skips unknown or malformed ones', () {
    final events = <TogetherEvent>[];
    client.events.listen(events.add);
    native.pending.addAll([
      jsonEncode({
        'event': 'room',
        'room': 'r',
        'invite': 'https://relay.test/j/r#k=x',
        'relay': 'relay.test',
        'member': 'm',
        'box_pk': 'b',
        'sign_pk': 's',
        'host': true,
      }),
      '{"event":"something_new"}',
      'not json',
      jsonEncode({
        'event': 'player',
        'command': {
          'action': 'load',
          'item': {
            'id': 'i1',
            'title': 'Clip',
            'source': {'type': 'url', 'url': 'https://example.com/v.mp4'},
            'shared_by': 'm2',
          },
          'headers': {'Cookie': 'c'},
          'start_ms': 4000,
        },
      }),
      '{"event":"player","command":{"action":"rate","rate":1.02}}',
      '{"event":"timeline","item":"i1","playing":true,"position_ms":1200.7,"rev":3}',
      '{"event":"stopped","room":"r","reason":"room_full"}',
    ]);
    client.poll();

    expect(events, hasLength(5));
    final room = events[0] as TogetherRoom;
    expect(room.host, isTrue);
    expect(room.toString(), isNot(contains('k=x')));
    final load = (events[1] as TogetherPlayer).command as TogetherLoad;
    expect(load.item.id, 'i1');
    expect((load.item.source as TogetherUrl).url, 'https://example.com/v.mp4');
    expect(load.item.sharedBy, 'm2');
    expect(load.headers, {'Cookie': 'c'});
    expect(load.startMs, 4000);
    expect(load.toString(), isNot(contains('example.com')));
    expect(((events[2] as TogetherPlayer).command as TogetherRate).rate, 1.02);
    expect((events[3] as TogetherTimeline).positionMs, 1200);
    expect((events[4] as TogetherStopped).reason, TogetherStop.roomFull);
  });

  test('parses live streams', () {
    final events = <TogetherEvent>[];
    client.events.listen(events.add);
    native.pending.addAll([
      '{"event":"live","live":true}',
      '{"event":"player","command":{"action":"go_live"}}',
    ]);
    client.poll();
    expect((events[0] as TogetherLive).live, isTrue);
    expect((events[1] as TogetherPlayer).command, isA<TogetherGoLive>());
  });

  test('close delivers what is left, then cancels and frees once', () {
    final events = <TogetherEvent>[];
    client.events.listen(events.add);
    native.pending.add('{"event":"disconnected"}');
    client.close();
    client.close();

    expect(events.single, isA<TogetherDisconnected>());
    expect(native.calls, ['cancel 7', 'free 7']);
    expect(client.play(), isFalse);
    expect(client.isClosed, isTrue);
  });

  test('refuses a library with another ABI', () {
    expect(
      () => TogetherClient(native: FakeNative()..abi = 99),
      throwsUnsupportedError,
    );
  });
}
