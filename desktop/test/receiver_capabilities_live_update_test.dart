import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_client.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

File _fixtureFile(String name) {
  final candidates = [
    File('../protocol/tests/fixtures/receiver-capabilities/$name'),
    File('protocol/tests/fixtures/receiver-capabilities/$name'),
    File('../../protocol/tests/fixtures/receiver-capabilities/$name'),
  ];
  for (final file in candidates) {
    if (file.existsSync()) return file;
  }
  throw StateError('Fixture file not found: $name');
}

Map<String, dynamic> _fixtureJson(String name) =>
    jsonDecode(_fixtureFile(name).readAsStringSync()) as Map<String, dynamic>;

class _FakeChannel implements IOWebSocketChannel {
  final open = Completer<void>();
  final input = StreamController<dynamic>.broadcast();
  final output = _FakeSink();

  @override
  Future<void> get ready => open.future;
  @override
  Stream<dynamic> get stream => input.stream;
  @override
  WebSocketSink get sink => output;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeSink implements WebSocketSink {
  final messages = <dynamic>[];
  bool closed = false;

  @override
  void add(dynamic data) {
    if (!closed) messages.add(data);
  }

  @override
  Future<void> close([int? code, String? reason]) {
    closed = true;
    return Future<void>.value();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _LiveUpdateTestTransport extends TvTransport {
  @override
  TvProtocol get protocol => TvProtocol.playBridge;

  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();

  SenderConnectionState _current = SenderConnectionState.disconnected;
  Map<String, dynamic> _caps = {};
  final List<String> sentControls = [];
  final List<String> sentRemoteKeys = [];
  final List<({String scope, double level})> sentVolumes = [];
  final List<String?> sentBrowserLaunches = [];
  final List<String> sentBrowserControls = [];

  final ReceiverProtocolSession session = ReceiverProtocolSession();

  @override
  ReceiverProtocolSession get protocolSession => session;

  @override
  ReceiverProtocolState get protocolState => session.state;

  void setCapabilities(Map<String, dynamic> caps) {
    _caps = caps;
  }

  void emitState(SenderConnectionState state) {
    _current = state;
    if (!_state.isClosed) _state.add(state);
  }

  void emitMessage(String msg) {
    if (!_messages.isClosed) _messages.add(msg);
  }

  void emitCredentials(TvCredentials creds) {
    if (!_credentials.isClosed) _credentials.add(creds);
  }

  @override
  Stream<SenderConnectionState> get state => _state.stream;

  @override
  SenderConnectionState get currentState => _current;

  @override
  Stream<String> get messages => _messages.stream;

  @override
  Stream<TvCredentials> get credentials => _credentials.stream;

  @override
  Map<String, dynamic> get capabilities => _caps;

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    session.begin();
    emitState(SenderConnectionState.connected);
  }

  @override
  Future<void> disconnect() async {
    session.clear();
    emitState(SenderConnectionState.disconnected);
  }

  @override
  Future<bool> castVideo(PlayPayload video) async => true;

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async => true;

  @override
  Future<bool> queueAdd(PlayPayload item) async => true;

  @override
  Future<bool> sendControl(String command) async {
    sentControls.add(command);
    return true;
  }

  @override
  Future<bool> sendRemoteKey(String key) async {
    sentRemoteKeys.add(key);
    return true;
  }

  @override
  Future<bool> sendVolume(String scope, double level) async {
    sentVolumes.add((scope: scope, level: level));
    return true;
  }

  @override
  Future<bool> sendBrowserLaunch({String? engine}) async {
    sentBrowserLaunches.add(engine);
    return true;
  }

  @override
  Future<bool> sendBrowserControl(String action) async {
    sentBrowserControls.add(action);
    return true;
  }

  @override
  Future<bool> playlistJump(int index) async => true;

  @override
  Future<void> dispose() async {
    await _state.close();
    await _messages.close();
    await _credentials.close();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  const fullDescriptor = {
    'version': 1,
    'surfaces': {
      'player': {
        'engines': {
          'mpv': {
            'transport': ['play', 'pause', 'stop', 'seek_absolute'],
            'settings': ['loop', 'engine'],
          },
          'exo': {
            'transport': ['play', 'pause', 'stop', 'seek_absolute'],
            'settings': ['loop', 'engine'],
          },
        },
      },
      'browser': {
        'engines': {
          'gecko': {
            'text': ['replace', 'enter'],
            'browser': ['forward', 'reload'],
          },
        },
      },
    },
    'volume': {
      'scope': 'app',
      'step': 0.05,
      'operations': ['relative_step', 'absolute', 'mute_toggle'],
    },
  };

  const emptyDescriptor = {
    'version': 1,
    'surfaces': <String, dynamic>{},
  };

  Map<String, dynamic> credentialsPayload({
    Map<String, dynamic> descriptor = fullDescriptor,
    bool feature = true,
  }) =>
      {
        'capabilities': descriptor,
        'features': [if (feature) receiverCapabilitiesV1Feature],
      };

  Map<String, dynamic> updatePayload(
    dynamic revision, {
    Map<String, dynamic> descriptor = emptyDescriptor,
  }) =>
      {
        'type': 'receiver_capabilities',
        'revision': revision,
        'capabilities': descriptor,
      };

  ({ReceiverProtocolSession owner, int session}) subscribedSession() {
    final owner = ReceiverProtocolSession();
    final session = owner.begin();
    expect(owner.acceptCredentials(session, credentialsPayload()), isTrue);
    expect(owner.subscription(session), isNotNull);
    return (owner: owner, session: session);
  }

  group('Fixtures matching', () {
    test('live-subscribe.json matches senderReceiverCapabilitiesSubscribeJson',
        () {
      final fixture = _fixtureJson('live-subscribe.json');
      final generated =
          jsonDecode(senderReceiverCapabilitiesSubscribeJson()) as Map;
      expect(generated, equals(fixture));
      expect(generated['type'], 'command');
      expect(generated['action'], 'receiver_capabilities_subscribe');
      expect(generated['payload'], equals({}));
    });

    test('invalid-live-subscribe-payload.json is rejected as non-empty payload',
        () {
      final invalidFixture =
          _fixtureJson('invalid-live-subscribe-payload.json');
      final generated =
          jsonDecode(senderReceiverCapabilitiesSubscribeJson()) as Map;
      expect(generated, isNot(equals(invalidFixture)));
    });

    test('fixtures are loadable and valid JSON', () {
      expect(
          _fixtureJson('live-update-v1.json')['type'], 'receiver_capabilities');
      expect(_fixtureJson('live-update-future.json')['type'],
          'receiver_capabilities');
      expect(_fixtureJson('invalid-live-zero-revision.json')['revision'], 0);
      expect(_fixtureJson('invalid-live-fractional-revision.json')['revision'],
          1.5);
      expect(
          _fixtureJson('invalid-live-string-revision.json')['revision'], '1');
      expect(_fixtureJson('invalid-live-large-revision.json')['revision'],
          greaterThan(maxReceiverCapabilitiesRevision));
      expect(
          _fixtureJson('invalid-live-malformed-v1.json')['capabilities']
              ['surfaces'],
          isA<List>());
    });
  });

  group('ReceiverProtocolSession rules', () {
    test(
        'optIn requires current authenticated advertised support and is once per session',
        () {
      final owner = ReceiverProtocolSession();
      final session = owner.begin();

      // Pre-auth: subscription is null, live frames dropped
      expect(owner.subscription(session), isNull);
      expect(owner.observe(session, updatePayload(1)), isFalse);
      expect(owner.state.descriptor, isNull);

      // Auth without feature
      expect(
          owner.acceptCredentials(session, credentialsPayload(feature: false)),
          isTrue);
      expect(owner.subscription(session), isNull);
      final before = owner.state;
      expect(owner.observe(session, updatePayload(1)), isFalse);
      expect(owner.state, equals(before));

      // Fresh session with feature
      final next = owner.begin();
      expect(owner.acceptCredentials(next, credentialsPayload()), isTrue);
      expect(owner.subscription(session), isNull); // Stale session ignored
      final sub = owner.subscription(next);
      expect(sub, isNotNull);
      expect(jsonDecode(sub!), equals(_fixtureJson('live-subscribe.json')));
      // Second subscription call on same socket returns null
      expect(owner.subscription(next), isNull);
    });

    test(
        'replacement removes operations and rejects duplicate and out-of-order frames',
        () {
      final s = subscribedSession();
      final liveV1 = _fixtureJson('live-update-v1.json');

      // Valid update (revision 1) replaces descriptor
      expect(s.owner.observe(s.session, liveV1), isTrue);
      final replaced = s.owner.state;
      expect(replaced.descriptor, isNotNull);
      expect(replaced.descriptor!.surfaces, isEmpty);

      // Duplicate revision 1 is rejected
      expect(s.owner.observe(s.session, liveV1), isFalse);
      expect(s.owner.state, equals(replaced));

      // Out-of-order / lower revision 0 is rejected
      expect(
          s.owner
              .observe(s.session, updatePayload(0, descriptor: fullDescriptor)),
          isFalse);
      expect(s.owner.state, equals(replaced));

      // Strictly higher revision 2 is accepted
      expect(
          s.owner
              .observe(s.session, updatePayload(2, descriptor: fullDescriptor)),
          isTrue);
      expect(s.owner.state.descriptor!.surfaces.containsKey('player'), isTrue);
      expect(s.owner.state.descriptor!.surfaces.containsKey('browser'), isTrue);
    });

    test('future versions keep current descriptor but advance ordering', () {
      final s = subscribedSession();
      final before = s.owner.state;
      final futureFixture = _fixtureJson('live-update-future.json');

      // Future version (revision 2, version 2) advances watermark to 2 but keeps descriptor
      expect(s.owner.observe(s.session, futureFixture), isTrue);
      expect(s.owner.state.descriptor, equals(before.descriptor));
      expect(s.owner.revision, 2);

      // Delayed older v1 (revision 1) is rejected because watermark is at 2
      expect(s.owner.observe(s.session, updatePayload(1)), isFalse);
      expect(s.owner.state.descriptor, equals(before.descriptor));

      // Newer valid v1 (revision 3) is accepted
      expect(s.owner.observe(s.session, updatePayload(3)), isTrue);
      expect(s.owner.state.descriptor!.surfaces, isEmpty);
      expect(s.owner.revision, 3);
    });

    test(
        'malformed v1 fails closed and only a newer valid snapshot restores it',
        () {
      final s = subscribedSession();
      final malformedFixture = _fixtureJson('invalid-live-malformed-v1.json');

      // Revision 1 is malformed v1 -> fails closed
      expect(s.owner.observe(s.session, malformedFixture), isTrue);
      expect(s.owner.state.descriptor, isNull);
      expect(s.owner.state.malformedV1, isTrue);
      expect(s.owner.revision, 1);

      // Stale or lower revision is rejected
      expect(
          s.owner
              .observe(s.session, updatePayload(0, descriptor: fullDescriptor)),
          isFalse);
      expect(s.owner.state.malformedV1, isTrue);

      // Newer valid v1 snapshot (revision 2) restores it
      expect(
          s.owner
              .observe(s.session, updatePayload(2, descriptor: fullDescriptor)),
          isTrue);
      expect(s.owner.state.descriptor, isNotNull);
      expect(s.owner.state.malformedV1, isFalse);
      expect(s.owner.revision, 2);
    });

    test('invalid revisions and envelopes do not consume the next revision',
        () {
      final s = subscribedSession();
      final before = s.owner.state;

      // Fixtures with invalid revisions
      expect(
          s.owner.observe(
              s.session, _fixtureJson('invalid-live-zero-revision.json')),
          isFalse);
      expect(s.owner.state, equals(before));

      expect(
          s.owner.observe(
              s.session, _fixtureJson('invalid-live-fractional-revision.json')),
          isFalse);
      expect(s.owner.state, equals(before));

      expect(
          s.owner.observe(
              s.session, _fixtureJson('invalid-live-string-revision.json')),
          isFalse);
      expect(s.owner.state, equals(before));

      expect(
          s.owner.observe(
              s.session, _fixtureJson('invalid-live-large-revision.json')),
          isFalse);
      expect(s.owner.state, equals(before));

      // Additional invalid revision formats
      for (final invalidRev in [-1, true, false, null]) {
        expect(s.owner.observe(s.session, updatePayload(invalidRev)), isFalse);
        expect(s.owner.state, equals(before));
      }

      // Invalid envelope (extra or missing keys)
      expect(
          s.owner.observe(s.session, {
            'type': 'receiver_capabilities',
            'revision': 1,
            // missing capabilities
          }),
          isFalse);
      expect(
          s.owner.observe(s.session, {
            'type': 'receiver_capabilities',
            'revision': 1,
            'capabilities': emptyDescriptor,
            'extra_key': 'forbidden',
          }),
          isFalse);
      expect(s.owner.state, equals(before));

      // Valid revision 1 is still accepted because the watermark was not consumed
      expect(s.owner.observe(s.session, _fixtureJson('live-update-v1.json')),
          isTrue);
      expect(s.owner.state.descriptor!.surfaces, isEmpty);
      expect(s.owner.revision, 1);
    });

    test('reconnect and receiver switch fence old sockets and reset revision',
        () {
      final s = subscribedSession();
      expect(s.owner.observe(s.session, updatePayload(100)), isTrue);
      expect(s.owner.revision, 100);

      // Reconnect / receiver switch begins new session
      final next = s.owner.begin();
      expect(s.owner.revision, 0);

      // Old session frames are rejected
      expect(
          s.owner.observe(
              s.session, updatePayload(101, descriptor: fullDescriptor)),
          isFalse);

      // New socket cannot receive frames before auth
      expect(
          s.owner.observe(next, updatePayload(101, descriptor: fullDescriptor)),
          isFalse);
      expect(s.owner.state, equals(const ReceiverProtocolState()));

      // Authenticate new socket
      expect(s.owner.acceptCredentials(next, credentialsPayload()), isTrue);
      expect(s.owner.subscription(next), isNotNull);

      // Stale session frame still rejected
      expect(s.owner.observe(s.session, updatePayload(999)), isFalse);

      // New socket revision starts from 1
      expect(s.owner.observe(next, updatePayload(1)), isTrue);
      expect(s.owner.state.descriptor!.surfaces, isEmpty);
      expect(s.owner.revision, 1);

      // Clear closes session completely
      s.owner.clear();
      expect(
          s.owner.observe(next, updatePayload(2, descriptor: fullDescriptor)),
          isFalse);
      expect(s.owner.state, equals(const ReceiverProtocolState()));
      expect(s.owner.revision, 0);
    });

    test(
        'descriptor push retains transient state and does not merge operations',
        () {
      final s = subscribedSession();

      // Establish transient context and volume state
      s.owner.observe(s.session, {
        'type': 'context',
        'active': 'browser',
        'engine': 'gecko',
      });
      s.owner.observe(s.session, {
        'type': 'volume_state',
        'scope': 'app',
        'level': 0.4,
        'muted': false,
      });

      final before = s.owner.state;
      expect(before.contextEngine, 'gecko');
      expect(before.volume?.level, 0.4);
      expect(before.volume?.scope, 'app');

      // Push empty descriptor update
      expect(s.owner.observe(s.session, updatePayload(1)), isTrue);

      // Transient state is preserved
      expect(s.owner.state.contextEngine, equals(before.contextEngine));
      expect(s.owner.state.volume, equals(before.volume));

      // Operations from old descriptor are completely replaced, not merged
      expect(s.owner.state.descriptor!.surfaces, isEmpty);
    });

    test(
        'duplicate auth cannot rewind the same socket revision or subscription',
        () {
      final s = subscribedSession();
      expect(s.owner.observe(s.session, updatePayload(5)), isTrue);
      expect(s.owner.revision, 5);

      // Repeated auth on same socket
      expect(
          s.owner.acceptCredentials(s.session, credentialsPayload()), isTrue);
      expect(s.owner.subscription(s.session), isNull); // Cannot subscribe again

      final before = s.owner.state;
      // Stale revision 4 is rejected, watermark was not rewound
      expect(s.owner.observe(s.session, updatePayload(4)), isFalse);
      expect(s.owner.state, equals(before));
      expect(s.owner.revision, 5);
    });
  });

  group('TvSenderClient wire integration', () {
    const timeout = Duration(milliseconds: 50);

    test(
        'client subscribes to live updates on auth_response with feature offer',
        () async {
      final channel = _FakeChannel()..open.complete();
      final client = TvSenderClient(
        connectTimeout: timeout,
        authTimeout: timeout,
        connectChannel: (_, __) => channel,
      );
      addTearDown(client.dispose);

      await client.connect(
        host: '192.0.2.1',
        port: 8443,
        deviceName: 'Test TV',
        deviceUUID: 'tv-uuid-1',
        token: 'token-123',
        expectedPin: 'sha256/pin',
      );

      // Send auth_response with receiver_capabilities_v1
      channel.input.add(jsonEncode({
        'type': 'auth_response',
        'success': true,
        'features': [receiverCapabilitiesV1Feature],
        'capabilities': fullDescriptor,
      }));
      await Future<void>.delayed(Duration.zero);

      // Verify subscribe message was sent on the wire sink
      expect(channel.output.messages, hasLength(2)); // auth, then subscribe
      final subMessage =
          jsonDecode(channel.output.messages[1] as String) as Map;
      expect(subMessage, equals(_fixtureJson('live-subscribe.json')));

      // Now receive live update on the wire
      channel.input.add(jsonEncode(_fixtureJson('live-update-v1.json')));
      await Future<void>.delayed(Duration.zero);

      // Client protocolState reflects live replacement
      expect(client.protocolState.descriptor, isNotNull);
      expect(client.protocolState.descriptor!.surfaces, isEmpty);
      expect(client.protocolSession.revision, 1);
    });

    test('client does NOT subscribe when receiver does not offer feature',
        () async {
      final channel = _FakeChannel()..open.complete();
      final client = TvSenderClient(
        connectTimeout: timeout,
        authTimeout: timeout,
        connectChannel: (_, __) => channel,
      );
      addTearDown(client.dispose);

      await client.connect(
        host: '192.0.2.1',
        port: 8443,
        deviceName: 'Test TV',
        deviceUUID: 'tv-uuid-2',
        token: 'token-123',
        expectedPin: 'sha256/pin',
      );

      // Send auth_response without receiver_capabilities_v1
      channel.input.add(jsonEncode({
        'type': 'auth_response',
        'success': true,
        'features': <String>[],
        'capabilities': fullDescriptor,
      }));
      await Future<void>.delayed(Duration.zero);

      // Only auth command was sent; no subscribe command
      expect(channel.output.messages, hasLength(1));
      expect(channel.output.messages[0], contains('token-123'));
    });

    test('reconnect resets protocol session and revision watermark', () async {
      final channel1 = _FakeChannel()..open.complete();
      final channel2 = _FakeChannel()..open.complete();
      var count = 0;
      final client = TvSenderClient(
        connectTimeout: timeout,
        authTimeout: timeout,
        connectChannel: (_, __) => count++ == 0 ? channel1 : channel2,
      );
      addTearDown(client.dispose);

      // First connection
      await client.connect(
        host: '192.0.2.1',
        port: 8443,
        deviceName: 'Test TV',
        deviceUUID: 'tv-uuid-3',
        token: 'token-123',
        expectedPin: 'sha256/pin',
      );
      channel1.input.add(jsonEncode({
        'type': 'auth_response',
        'success': true,
        'features': [receiverCapabilitiesV1Feature],
        'capabilities': fullDescriptor,
      }));
      await Future<void>.delayed(Duration.zero);

      channel1.input.add(jsonEncode(_fixtureJson('live-update-v1.json')));
      await Future<void>.delayed(Duration.zero);
      expect(client.protocolSession.revision, 1);

      // Disconnect and reconnect
      await client.disconnect();
      expect(client.protocolSession.revision, 0);
      expect(client.protocolState.descriptor, isNull);

      await client.connect(
        host: '192.0.2.1',
        port: 8443,
        deviceName: 'Test TV',
        deviceUUID: 'tv-uuid-3',
        token: 'token-123',
        expectedPin: 'sha256/pin',
      );
      channel2.input.add(jsonEncode({
        'type': 'auth_response',
        'success': true,
        'features': [receiverCapabilitiesV1Feature],
        'capabilities': fullDescriptor,
      }));
      await Future<void>.delayed(Duration.zero);

      // New socket accepts revision 1 again
      channel2.input.add(jsonEncode(_fixtureJson('live-update-v1.json')));
      await Future<void>.delayed(Duration.zero);
      expect(client.protocolSession.revision, 1);
    });
  });

  group('TvSenderController dynamic policy & execution gating', () {
    Future<
        ({
          TvSenderController sender,
          _LiveUpdateTestTransport transport,
          TvRecord record,
          TvConnectionStore store
        })> createFixture() async {
      final transport = _LiveUpdateTestTransport();
      transport.setCapabilities(fullDescriptor);

      final store = await TvConnectionStore.load();
      final record = TvRecord(
        uuid: 'test-receiver-live-uuid',
        protocol: TvProtocol.playBridge,
        name: 'Live TV',
        host: '192.0.2.80',
        port: 8765,
        wssPort: 8443,
        token: 'secret-token-live',
        capabilities: fullDescriptor,
        lastConnected: DateTime(2026, 1, 1),
      );
      await store.upsert(record);

      final sender = TvSenderController(
        identity: await PairingStore.load(),
        store: store,
        transport: transport,
      );
      addTearDown(sender.dispose);
      sender.bindTransportForTest();
      return (
        sender: sender,
        transport: transport,
        record: record,
        store: store
      );
    }

    testWidgets(
        'live update replaces descriptor and gates execution of removed operations',
        (tester) async {
      final f = await createFixture();
      await f.sender.reconnect(f.record);
      await tester.pump();

      // Authenticate session on transport
      final sess = f.transport.session;
      sess.acceptCredentials(sess.generation, credentialsPayload());
      sess.subscription(sess.generation);
      await tester.pump();

      // Initially permitted
      expect(f.sender.canPlayPause, isTrue);
      expect(f.sender.canAdjustVolume, isTrue);
      expect(f.sender.hasVolume, isTrue);
      expect(f.sender.canSwitchEngine, isTrue);

      expect(await f.sender.sendControl('play'), isTrue);
      expect(f.transport.sentControls, contains('play'));
      expect(await f.sender.adjustVolume(1), isTrue);
      expect(f.transport.sentRemoteKeys, contains('volume_up'));

      var notified = 0;
      f.sender.addListener(() => notified++);

      // Live update with empty surfaces arrives
      f.transport.emitMessage(jsonEncode(_fixtureJson('live-update-v1.json')));
      await tester.pump();

      // notifyListeners was called
      expect(notified, greaterThan(0));

      // Dynamic policy now reflects empty descriptor
      expect(f.sender.canPlayPause, isFalse);
      expect(f.sender.canAdjustVolume, isFalse);
      expect(f.sender.hasVolume, isFalse);
      expect(f.sender.canSwitchEngine, isFalse);

      // Recheck at execution gates out removed operations
      expect(await f.sender.sendControl('play'), isFalse);
      expect(await f.sender.sendControl('pause'), isFalse);
      expect(await f.sender.adjustVolume(1), isFalse);
      expect(await f.sender.setVolume(0.5), isFalse);
      expect(await f.sender.toggleMute(), isFalse);
      expect(await f.sender.switchPlayer('exo'), isFalse);

      // Verify credentials in store were NOT overwritten with live update
      final savedRecord =
          f.store.byIdentity(TvProtocol.playBridge, f.record.uuid);
      expect(savedRecord, isNotNull);
      expect(savedRecord!.capabilities['surfaces'],
          equals(fullDescriptor['surfaces']),
          reason: 'Live updates must never be persisted as credentials');
    });

    testWidgets(
        'malformed v1 live update fails closed without fallback to legacy',
        (tester) async {
      final f = await createFixture();
      await f.sender.reconnect(f.record);
      await tester.pump();

      final sess = f.transport.session;
      sess.acceptCredentials(sess.generation, credentialsPayload());
      sess.subscription(sess.generation);
      await tester.pump();

      // Emit malformed v1 frame
      f.transport.emitMessage(
          jsonEncode(_fixtureJson('invalid-live-malformed-v1.json')));
      await tester.pump();

      expect(f.sender.policy.isMalformedV1, isTrue);
      expect(f.sender.policy.isLegacy, isFalse);
      expect(f.sender.canPlayPause, isFalse);
      expect(f.sender.hasVolume, isFalse);

      // All controls fail closed
      expect(await f.sender.sendControl('play'), isFalse);
      expect(await f.sender.adjustVolume(1), isFalse);
    });
  });

  group('Privacy regression', () {
    testWidgets('descriptors, tokens, pins, and text are never logged',
        (tester) async {
      final logs = <String>[];
      final originalDebugPrint = debugPrint;
      debugPrint = (String? message, {int? wrapWidth}) {
        if (message != null) logs.add(message);
      };

      try {
        final transport = _LiveUpdateTestTransport();
        final store = await TvConnectionStore.load();
        final record = TvRecord(
          uuid: 'privacy-test-uuid',
          protocol: TvProtocol.playBridge,
          name: 'Privacy TV',
          host: '192.0.2.99',
          port: 8765,
          wssPort: 8443,
          token: 'SUPER_SECRET_TOKEN_XYZ',
          capabilities: fullDescriptor,
          lastConnected: DateTime(2026, 1, 1),
        );
        await store.upsert(record);

        final sender = TvSenderController(
          identity: await PairingStore.load(),
          store: store,
          transport: transport,
        );
        addTearDown(sender.dispose);
        sender.bindTransportForTest();

        await sender.reconnect(record);
        await tester.pump();

        transport.session.acceptCredentials(
            transport.session.generation, credentialsPayload());
        transport.session.subscription(transport.session.generation);

        // Process live update with unique sentinel string inside descriptor
        const privateSentinel = 'PRIVATE_SENTINEL_SURFACE_DESCRIPTOR';
        final privatePayload = {
          'type': 'receiver_capabilities',
          'revision': 1,
          'capabilities': {
            'version': 1,
            'surfaces': {
              privateSentinel: {
                'engines': <String, dynamic>{},
              },
            },
          },
        };
        transport.emitMessage(jsonEncode(privatePayload));
        await tester.pump();

        // Check that none of the private items leaked into logs
        for (final log in logs) {
          expect(log.contains(privateSentinel), isFalse,
              reason: 'Descriptor contents must never appear in logs');
          expect(log.contains('SUPER_SECRET_TOKEN_XYZ'), isFalse,
              reason: 'Tokens must never appear in logs');
        }
      } finally {
        debugPrint = originalDebugPrint;
      }
    });
  });
}
