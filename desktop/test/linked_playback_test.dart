import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/linked_playback.dart';
import 'package:playbridge_desktop/linked_playback_banner.dart';
import 'package:playbridge_desktop/protocol.dart';

class Backend extends LinkedPlaybackBackend {
  @override
  String destination = 'local';
  @override
  bool connected = true;
  @override
  bool external = false;
  @override
  int selectionRevision = 0;
  @override
  Map<String, dynamic> status = {'state': 'playing', 'currentIndex': 0};
  bool waiting = false;
  bool fail = false;
  Completer<void>? gate;
  List<LinkedItem> queue = [];
  VisualMetadata? metadata;
  bool? skip;
  int changes = 0;
  int stops = 0;
  @override
  void waitForItems(bool enabled) => waiting = enabled;
  Future<bool> commit(bool Function() canCommit, void Function() action) async {
    final pending = gate;
    if (pending != null) await pending.future;
    if (!canCommit() || fail) return false;
    action();
    changes++;
    notifyListeners();
    return true;
  }

  @override
  Future<bool> replace(
          List<LinkedItem> items,
          int index,
          VisualMetadata? metadata,
          bool skipPreplay,
          bool Function() canCommit) =>
      commit(canCommit, () {
        queue = items.toList();
        this.metadata = metadata;
        skip = skipPreplay;
        status = {'state': 'playing', 'currentIndex': index};
      });
  @override
  Future<bool> append(List<LinkedItem> items, bool Function() canCommit) =>
      commit(canCommit, () => queue.addAll(items));
  @override
  Future<bool> jump(int index, bool Function() canCommit) =>
      commit(canCommit, () => status['currentIndex'] = index);
  void select(String value) {
    destination = value;
    selectionRevision++;
    notifyListeners();
  }

  void progress(String state, int index, {int position = 0}) {
    status = {
      'state': state,
      'currentIndex': index,
      'positionMs': position,
      'durationMs': 9000
    };
    notifyListeners();
  }
}

Map<String, dynamic> item(String id, {String? url}) => {
      'id': id,
      'url': url ?? 'https://media.example/$id.mp4',
      'title': 'Item $id'
    };

void main() {
  late Backend backend;
  late LinkedPlaybackCoordinator coordinator;
  late DateTime now;
  late Object owner;
  late List<Map<String, dynamic>> events;
  var requests = 0;
  setUp(() {
    now = DateTime(2026);
    backend = Backend();
    owner = Object();
    events = [];
    coordinator = LinkedPlaybackCoordinator(backend,
        now: () => now, tickInterval: const Duration(days: 1));
  });
  tearDown(() => coordinator.dispose());
  Future<Map<String, dynamic>> send(String type,
          {Map? payload,
          String? sessionId,
          Map<String, dynamic>? extra,
          Object? asOwner}) =>
      coordinator.handle({
        'type': type,
        'bridgeRequestId': 'r${++requests}',
        if (sessionId != null || coordinator.sessionId != null)
          'sessionId': sessionId ?? coordinator.sessionId,
        if (payload != null) 'payload': payload,
        ...?extra,
      }, asOwner ?? owner, events.add);
  Future<Map<String, dynamic>> open(
          {List<Map>? items, Map<String, dynamic>? extra}) =>
      send('linked_open', payload: {
        'items': items ?? [item('a')],
        'startIndex': 0
      }, extra: {
        'origin': 'https://streams.example',
        ...?extra
      });
  List<Map<String, dynamic>> named(String name) =>
      events.where((event) => event['event'] == name).toList();

  test(
      'play, ping, replace, append, jump, supply, idempotent supply and unlink',
      () async {
    final result = await open();
    expect(result, containsPair('ok', true));
    expect(result['type'], 'linked_result');
    expect(result['bridgeRequestId'], startsWith('r'));
    expect(result['sessionId'], coordinator.sessionId);
    expect(events, isEmpty);
    await send('linked_ping');
    expect(named('needitems'), hasLength(1));
    final demand = named('needitems').single['detail'] as Map;
    expect(demand, containsPair('afterItemId', 'a'));
    expect(demand['afterIndex'], 0);
    expect(demand['count'], inInclusiveRange(1, 3));
    final need = demand['requestId'];
    expect(
        (await send('linked_supply', payload: {
          'requestId': need,
          'items': [item('b')],
          'endOfList': false
        }))['ok'],
        true);
    expect(
        (await send('linked_supply', payload: {
          'requestId': need,
          'items': [item('b')],
          'endOfList': false
        }))['ok'],
        true);
    expect(backend.queue.map((item) => item.id), ['a', 'b']);
    expect(
        (await send('linked_append', payload: {
          'items': [item('c')]
        }))['ok'],
        true);
    expect((await send('linked_jump', payload: {'index': 2}))['ok'], true);
    expect(named('needitems'), hasLength(2));
    final lastNeed = (named('needitems').last['detail'] as Map)['requestId'];
    await send('linked_supply',
        payload: {'requestId': lastNeed, 'items': [], 'endOfList': true});
    expect(backend.waiting, false);
    expect(
        (await send('linked_replace', payload: {
          'items': [item('z')],
          'startIndex': 0,
          'metadata': {'title': 'Series'},
          'skipPreplay': true
        }))['ok'],
        true);
    expect(backend.queue.single.id, 'z');
    expect(backend.metadata?.title, 'Series');
    expect(backend.skip, true);
    expect((await send('linked_unlink'))['ok'], true);
    expect(coordinator.sessionId, isNull);
    expect(named('ended').last['detail'], {'reason': 'unlinked'});
    expect(backend.stops, 0);
    expect(backend.queue.single.id, 'z');
    expect(
        (await send('linked_ping',
            sessionId: result['sessionId'] as String))['error'],
        'session_ended');
  });

  test(
      'state event shape, position throttling, structural changes and one demand',
      () async {
    await open();
    await send('linked_ping');
    final state = named('statechange').single['detail'] as Map;
    expect(state, {
      'state': 'playing',
      'positionMs': 0,
      'durationMs': 0,
      'title': 'Item a',
      'currentIndex': 0,
      'totalCount': 1,
      'items': [
        {'index': 0, 'id': 'a', 'title': 'Item a'}
      ]
    });
    backend.progress('playing', 0, position: 100);
    final count = named('statechange').length;
    for (var i = 0; i < 20; i++) {
      backend.progress('playing', 0, position: 200 + i);
      backend.status['durationMs'] = 9000 + i;
      backend.notifyListeners();
    }
    expect(named('statechange'), hasLength(count));
    expect(named('needitems'), hasLength(1));
    now = now.add(const Duration(seconds: 1));
    backend.progress('playing', 0, position: 1200);
    expect(named('statechange'), hasLength(count + 1));
    backend.progress('paused', 0, position: 1200);
    expect((named('statechange').last['detail'] as Map)['state'], 'paused');
    now = now.add(const Duration(seconds: 5));
    coordinator.poll();
    expect(named('needitems'), hasLength(2));
    expect(
        named('needitems').first['detail'], named('needitems').last['detail']);
  });

  test(
      'supersede, navigation, permission reset, selection change and idle expiry',
      () async {
    final first = await open();
    final second = await open();
    expect(second['sessionId'], isNot(first['sessionId']));
    expect(named('ended').last['detail'], {'reason': 'superseded'});
    expect((await send('linked_ping', asOwner: Object()))['error'],
        'session_ended');
    expect(coordinator.sessionId, second['sessionId']);
    await send('linked_unlink', extra: {'reason': 'permission_reset'});
    expect(named('ended').last['detail'], {'reason': 'permission_reset'});
    await open();
    coordinator.disconnectOwner(owner);
    expect(named('ended').last['detail'], {'reason': 'navigation'});
    expect((await open())['error'], 'session_ended');
    owner = Object();
    await open();
    backend.select('playBridge:tv');
    expect(named('ended').last['detail'], {'reason': 'receiver_changed'});
    await open();
    now = now.add(const Duration(minutes: 29));
    await send('linked_ping');
    now = now.add(const Duration(minutes: 29));
    coordinator.poll();
    expect(coordinator.sessionId, isNotNull);
    now = now.add(const Duration(minutes: 2));
    coordinator.poll();
    expect(named('ended').last['detail'], {'reason': 'session_expired'});
    expect(backend.stops, 0);
  });

  test('external targets allow one item and reject queues and subtitles',
      () async {
    backend.destination = 'dlna:tv';
    backend.external = true;
    expect((await open(items: [item('a'), item('b')]))['error'],
        'unsupported_queue');
    expect(
        (await open(items: [
          {
            ...item('a'),
            'subtitles': ['https://media.example/sub.vtt']
          }
        ]))['error'],
        'unsupported_subtitles');
    expect((await open())['ok'], true);
    await send('linked_ping');
    expect(named('needitems'), isEmpty);
    expect((await send('linked_jump', payload: {'index': 0}))['error'],
        'unsupported_operation');
    expect(
        (await send('linked_append', payload: {
          'items': [item('b')]
        }))['error'],
        'unsupported_queue');
    expect(
        (await send('linked_supply', payload: {
          'items': [item('b')],
          'requestId': 'x'
        }))['error'],
        'unsupported_queue');
    backend.progress('ended', 0);
    expect(named('ended').last['detail'], {'reason': 'receiver_stopped'});
  });

  test(
      'destination mismatch, offline and async destination re-check never commit',
      () async {
    expect(
        (await send('linked_play', extra: {
          'origin': 'https://streams.example',
          'destination': 'playBridge:tv'
        }, payload: {
          'items': [item('a')]
        }))['error'],
        'destination_changed');
    backend.connected = false;
    expect((await open())['error'], 'destination_offline');
    backend.connected = true;
    backend.gate = Completer<void>();
    final pending = open();
    await Future<void>.delayed(Duration.zero);
    backend.select('playBridge:tv');
    backend.gate!.complete();
    expect((await pending)['error'], 'destination_changed');
    expect(backend.changes, 0);
    expect(named('ended').last['detail'], {'reason': 'receiver_changed'});
    backend.gate = Completer<void>();
    final offline = open();
    await Future<void>.delayed(Duration.zero);
    backend.connected = false;
    backend.gate!.complete();
    expect((await offline)['error'], 'destination_offline');
    expect(backend.changes, 0);
  });

  test(
      'failed queue update ends authority; ready ended state waits for late supply',
      () async {
    await open();
    await send('linked_ping');
    backend.progress('ended', 0);
    expect(coordinator.sessionId, isNotNull);
    final requestId = (named('needitems').single['detail'] as Map)['requestId'];
    await send('linked_supply',
        payload: {'requestId': requestId, 'items': [], 'endOfList': true});
    expect(named('ended').last['detail'], {'reason': 'receiver_stopped'});
    await open();
    backend.fail = true;
    expect(
        (await send('linked_append', payload: {
          'items': [item('b')]
        }))['ok'],
        false);
    expect(named('ended').last['detail'], {'reason': 'queue_update_failed'});
  });

  test('request limits and sender-only fields are rejected before playback',
      () async {
    for (final bad in [
      {...item('a'), 'url': 'file:///tmp/movie'},
      {
        ...item('a'),
        'headers': {'Host': 'private'}
      },
      {
        ...item('a'),
        'headers': {'Cookie': 'bad\r\nheader'}
      },
      {...item('a'), 'startPositionMs': -1},
      {...item('a'), 'progressWebhook': null},
      {
        ...item('a'),
        'subtitleResources': [
          {
            'url': 'http://media.example/sub',
            'headers': {'Range': 'bytes=0-'}
          }
        ]
      },
    ]) {
      expect((await open(items: [bad]))['error'], 'invalid_request');
    }
    expect((await open(extra: {'progressWebhook': false}))['error'],
        'invalid_request');
    expect(
        (await send('linked_open', extra: {
          'origin': 'https://streams.example'
        }, payload: {
          'items': [item('a')],
          'progressWebhook': null
        }))['error'],
        'invalid_request');
    expect((await open(items: List.generate(51, (i) => item('$i'))))['error'],
        'resource_limit');
    expect(
        (await open(extra: {
          'allowedPrivateOrigins': List.filled(17, 'http://192.168.1.1')
        }))['error'],
        'resource_limit');
    expect(
        (await open(items: [
          {...item('a'), 'title': 'x' * 66000}
        ]))['error'],
        'resource_limit');
    expect(
        (await open(extra: {'origin': 'https://streams.example/private/path'}))[
            'error'],
        'invalid_request');
    expect(backend.changes, 0);
    final result = await open(items: [
      {
        ...item('a'),
        'startPositionMs': 12000,
        'headers': {'Authorization': 'fixture-token'},
        'metadata': {'season': 2},
        'subtitleResources': [
          {
            'url': 'http://192.168.1.3/sub.vtt',
            'headers': {'Cookie': 'fixture'},
            'language': 'en'
          }
        ]
      }
    ], extra: {
      'allowedPrivateOrigins': ['http://192.168.1.3']
    });
    expect(result['ok'], true);
    final media = backend.queue.single.media;
    expect(media.detectedBy, 'linked_page');
    expect(media.startPositionMs.toInt(), 12000);
    expect(media.allowedPrivateOrigins, ['http://192.168.1.3']);
    expect(media.subtitleResources.single.headers, {'Cookie': 'fixture'});
    expect(media.visualMetadata.season, 2);
    expect((media.toProto3Json() as Map).containsKey('progressWebhook'), false);
  });

  test(
      'eight in-flight requests are bounded and a disconnected opener cannot commit',
      () async {
    backend.gate = Completer<void>();
    final calls = List.generate(8, (_) => open());
    expect((await open())['error'], 'resource_limit');
    await Future<void>.delayed(Duration.zero);
    coordinator.disconnectOwner(owner);
    backend.gate!.complete();
    final results = await Future.wait(calls);
    expect(results.every((result) => result['ok'] == false), true);
    expect(backend.changes, 0);
    expect(results.map((result) => result['bridgeRequestId']).toSet(),
        hasLength(8));
  });

  testWidgets('player banner displays origin and unlink releases control only',
      (tester) async {
    await tester.runAsync(() => open());
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: LinkedPlaybackBanner(coordinator: coordinator))));
    expect(find.text('Controlled by https://streams.example'), findsOneWidget);
    await tester.tap(find.text('Unlink'));
    await tester.pump();
    expect(find.text('Unlink'), findsNothing);
    expect(coordinator.sessionId, isNull);
    expect(backend.queue, hasLength(1));
    expect(backend.stops, 0);
  });

  test('timed-out preparation releases authority and cannot commit late',
      () async {
    final timedBackend = Backend()..gate = Completer<void>();
    final timed = LinkedPlaybackCoordinator(timedBackend,
        operationTimeout: const Duration(milliseconds: 20));
    addTearDown(timed.dispose);
    final output = <Map>[];
    final result = await timed.handle({
      'type': 'linked_open',
      'bridgeRequestId': 'slow',
      'origin': 'https://streams.example',
      'payload': {
        'items': [item('a')]
      }
    }, Object(), output.add);
    expect(result['error'], 'timeout');
    expect(output.single['detail'], {'reason': 'queue_update_failed'});
    timedBackend.gate!.complete();
    await Future<void>.delayed(Duration.zero);
    expect(timedBackend.changes, 0);
    expect(timed.sessionId, isNull);
  });
}
