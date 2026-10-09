import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/protocol.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_sender_controller.dart';
import 'package:playbridge_desktop/tv_transport.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const delay = Duration(milliseconds: 10);

  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<
      ({
        TvSenderController sender,
        _FakeTransport transport,
        TvRecord saved,
        TvConnectionStore store
      })> make({
    TvProtocol protocol = TvProtocol.playBridge,
    String token = 'saved-token',
    int giveUp = 30,
    Future<StreamProxyLease> Function(Iterable<String>)? retainProxyUrls,
  }) async {
    final transport = _FakeTransport(protocol);
    final store = await TvConnectionStore.load();
    final saved = TvRecord(
      uuid: 'apple-tv',
      protocol: protocol,
      name: 'Living Room',
      host: '192.0.2.20',
      port: 8765,
      wssPort: 8443,
      token: token,
      certFingerprint: 'sha256/pin',
      lastConnected: DateTime(2026),
    );
    await store.upsert(saved);
    final sender = TvSenderController(
      identity: await PairingStore.load(),
      store: store,
      transport: transport,
      reconnectDelay: delay,
      reconnectGiveUp: giveUp,
      retainProxyUrls: retainProxyUrls,
    );
    addTearDown(sender.dispose);
    sender.bindTransportForTest();
    return (sender: sender, transport: transport, saved: saved, store: store);
  }

  testWidgets('failed DLNA loads preserve renderer error and success clears it',
      (tester) async {
    final h = await make(
      protocol: TvProtocol.dlna,
      retainProxyUrls: (_) async => StreamProxyLease(() {}),
    );
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.lastError =
        'UPnP SetAVTransportURI error 714: Illegal MIME-type';
    expect(
        await h.sender.castPlaylist(PlaylistPayload(
            items: [PlayPayload(url: 'http://sender/movie.avi')])),
        isFalse);
    expect(h.sender.lastCastError, contains('714: Illegal MIME-type'));
    h.transport.acceptsLoads = true;
    expect(
        await h.sender.castPlaylist(PlaylistPayload(
            items: [PlayPayload(url: 'http://sender/movie.mp4')])),
        isTrue);
    expect(h.sender.lastCastError, isNull);
  });

  for (final playlist in [false, true]) {
    testWidgets(
        'Google Cast relaunch during ${playlist ? 'playlist' : 'video'} keeps incoming lease',
        (tester) async {
      final closed = <String>[];
      final h = await make(
          protocol: TvProtocol.googleCast,
          retainProxyUrls: (urls) async {
            final values = urls.toList();
            return StreamProxyLease(() {
              closed.addAll(values);
            });
          });
      await h.sender.reconnect(h.saved);
      await tester.pump();
      h.transport.acceptsLoads = true;
      await h.sender.castVideo(PlayPayload(url: 'http://phone/old'));
      h.transport.duringLoad = () async {
        h.transport.emit(SenderConnectionState.selected);
        await Future<void>.value();
        h.transport.emit(SenderConnectionState.connecting);
        h.transport.emit(SenderConnectionState.connected);
        await Future<void>.value();
      };
      final payload = PlayPayload(url: 'http://phone/new');
      final ok = playlist
          ? await h.sender.castPlaylist(PlaylistPayload(items: [payload]))
          : await h.sender.castVideo(payload);
      expect(ok, isTrue);
      expect(closed, ['http://phone/old']);
      await h.sender.disconnect();
      expect(closed, ['http://phone/old', 'http://phone/new']);
    });
  }

  testWidgets('retry recovery keeps ownership but final give-up releases it',
      (tester) async {
    var closed = 0;
    final h = await make(
        giveUp: 1,
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.acceptsLoads = true;
    await h.sender.castVideo(PlayPayload(url: 'http://phone/video'));
    h.transport.emit(SenderConnectionState.error);
    await tester.pump();
    expect(closed, 0);
    await tester.pump(delay);
    await tester.pump();
    expect(closed, 0);
    h.transport.failConnect = true;
    h.transport.emit(SenderConnectionState.error);
    await tester.pump();
    await tester.pump(delay);
    await tester.pump();
    expect(closed, 1);
    h.sender.dispose();
    expect(closed, 1);
  });

  testWidgets('auth pin and pairing failures release immediately',
      (tester) async {
    for (final state in [
      SenderConnectionState.authFailed,
      SenderConnectionState.pinMismatch,
      SenderConnectionState.pairingDenied
    ]) {
      var closed = 0;
      final h = await make(
          retainProxyUrls: (_) async => StreamProxyLease(() {
                closed++;
              }));
      await h.sender.reconnect(h.saved);
      await tester.pump();
      h.transport.acceptsLoads = true;
      await h.sender.castVideo(PlayPayload(url: 'http://phone/video'));
      h.transport.emit(state);
      await tester.pump();
      expect(closed, 1);
    }
  });

  testWidgets(
      'same receiver reconnect retains but switching without a cast releases',
      (tester) async {
    var closed = 0;
    final h = await make(
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.acceptsLoads = true;
    await h.sender.castVideo(PlayPayload(url: 'http://phone/video'));
    await h.sender.reconnect(h.saved);
    await tester.pump();
    expect(closed, 0);
    final other = TvRecord(
        uuid: 'another-tv',
        protocol: TvProtocol.playBridge,
        name: 'Other',
        host: '192.0.2.21',
        port: 8765,
        token: 'other-token',
        certFingerprint: 'other-pin',
        lastConnected: DateTime(2026));
    await h.sender.reconnect(other);
    await tester.pump();
    expect(closed, 1);
  });

  testWidgets('a transport without retry gets a bounded lost-connection grace',
      (tester) async {
    var closed = 0;
    final h = await make(
        protocol: TvProtocol.dlna,
        retainProxyUrls: (_) async => StreamProxyLease(() {
              closed++;
            }));
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.acceptsLoads = true;
    await h.sender.castVideo(PlayPayload(url: 'http://phone/video'));
    h.transport.emit(SenderConnectionState.error);
    await tester.pump();
    expect(closed, 0);
    await tester.pump(TvSenderController.proxyIdleGrace);
    expect(closed, 1);
  });

  testWidgets('an unexpected drop retries with the saved token and resyncs',
      (tester) async {
    final harness = await make();
    await harness.sender.reconnect(harness.saved);
    await tester.pump();

    expect(harness.transport.connects, 1);
    expect(harness.transport.lastToken, 'saved-token');
    expect(harness.transport.contextQueries, 1);
    expect(harness.sender.reconnectStatus, isNull);
    expect(harness.sender.isConnected, isTrue);

    harness.transport.emit(SenderConnectionState.error);
    harness.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();

    final status = harness.sender.reconnectStatus;
    expect(status?.attempt, 1);
    expect(status?.maxAttempts, 30);
    expect(status?.label, 'Reconnecting to Living Room (1/30)…');
    expect(harness.sender.activeTv?.name, 'Living Room');
    expect(harness.sender.isConnected, isFalse);

    await tester.pump(delay);
    await tester.pump();

    expect(harness.transport.connects, 2);
    expect(harness.transport.lastToken, 'saved-token');
    expect(harness.transport.contextQueries, 2);
    expect(harness.sender.reconnectStatus, isNull);
    expect(harness.sender.state, SenderConnectionState.connected);
  });

  testWidgets('disconnect, auth failure, and a missing token do not retry',
      (tester) async {
    final dropped = await make();
    await dropped.sender.reconnect(dropped.saved);
    await tester.pump();
    await dropped.sender.disconnect();
    await tester.pump();
    expect(dropped.sender.reconnectStatus, isNull);
    expect(dropped.sender.activeTv, isNull);

    final rejected = await make();
    await rejected.sender.reconnect(rejected.saved);
    await tester.pump();
    rejected.transport.emit(SenderConnectionState.authFailed);
    await tester.pump();
    expect(rejected.sender.reconnectStatus, isNull);
    expect(rejected.sender.activeTv, isNull);

    final unpaired = await make(token: '');
    await unpaired.sender.reconnect(unpaired.saved);
    await tester.pump();
    unpaired.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    expect(unpaired.sender.reconnectStatus, isNull);

    await tester.pump();
    expect(dropped.transport.connects, 1);
    expect(rejected.transport.connects, 1);
    expect(unpaired.transport.connects, 1);
  });

  testWidgets('a non-PlayBridge drop and a pre-session drop do not retry',
      (tester) async {
    final cast = await make(protocol: TvProtocol.googleCast);
    await cast.sender.reconnect(cast.saved);
    await tester.pump();
    cast.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();

    final idle = await make();
    idle.transport.emit(SenderConnectionState.error);
    idle.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();

    expect(cast.transport.connects, 1);
    expect(cast.sender.reconnectStatus, isNull);
    expect(idle.transport.connects, 0);
  });

  testWidgets('the retry budget is fixed and then stands down', (tester) async {
    final harness = await make();
    await harness.sender.reconnect(harness.saved);
    await tester.pump();
    harness.transport.failConnect = true;

    harness.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    expect(harness.sender.reconnectStatus?.attempt, 1);

    for (var i = 0; i < 32; i++) {
      await tester.pump(delay);
    }
    await tester.pump();
    expect(harness.transport.connects, 31);
    expect(harness.sender.reconnectStatus, isNull);
    expect(harness.sender.activeTv, isNull);
    expect(harness.sender.state, isNot(SenderConnectionState.connected));

    await tester.pump(delay);
    expect(harness.transport.connects, 31);
    harness.transport.failConnect = false;
    await harness.sender.reconnect(harness.saved);
    await tester.pump();
    expect(harness.sender.isConnected, isTrue); // manual reconnect still works
    harness.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    expect(harness.sender.reconnectStatus?.attempt, 1); // fresh session budget
    await harness.sender.disconnect();
    await tester.pump();
  });

  testWidgets('cancel during a retry does not connect again', (tester) async {
    final harness = await make();
    await harness.sender.reconnect(harness.saved);
    await tester.pump();

    harness.transport.failConnect = true;
    harness.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    expect(harness.sender.reconnectStatus, isNotNull);

    await harness.sender.disconnect();
    await tester.pump();
    expect(harness.sender.reconnectStatus, isNull);
    expect(harness.sender.activeTv, isNull);

    await tester.pump(delay);
    expect(harness.transport.connects, 1);
  });
  for (final terminal in [
    SenderConnectionState.authFailed,
    SenderConnectionState.pinMismatch,
    SenderConnectionState.pairingDenied
  ]) {
    testWidgets('retry stops on $terminal and ignores late connected events',
        (tester) async {
      final h = await make();
      await h.sender.reconnect(h.saved);
      await tester.pump();
      h.transport.failureState = terminal;
      h.transport.emit(SenderConnectionState.disconnected);
      await tester.pump();
      await tester.pump(delay);
      expect(h.sender.state, terminal);
      expect(h.sender.reconnectStatus, isNull);
      h.transport.emit(SenderConnectionState.connected);
      await tester.pump();
      expect(h.sender.state, terminal);
      await tester.pump(delay * 40);
      expect(h.transport.connects, 2);
      expect(h.sender.activeTv, isNull);
    });
  }

  testWidgets('cancel an in-flight retry ignores its late success',
      (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.connectGate = Completer<void>();
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await tester.pump(delay);
    expect(h.transport.connects, 2);
    await h.sender.disconnect();
    h.transport.connectGate!.complete();
    h.transport._sas.add('123456');
    h.transport._credentials.add(const TvCredentials('late-token', 'late-pin'));
    h.transport._messages
        .add('{"type":"status","state":"playing","title":"Late"}');
    await tester.pump();
    await tester.pump(delay * 40);
    expect(h.sender.currentSas, isNull);
    expect(h.sender.isCasting, isFalse);
    expect(h.store.byIdentity(h.saved.protocol, h.saved.uuid)?.token,
        h.saved.token);
    expect(h.sender.state, SenderConnectionState.disconnected);
    expect(h.sender.activeTv, isNull);
    expect(h.sender.reconnectStatus, isNull);
    expect(h.transport.contextQueries, 1);
    expect(h.transport.connects, 2);
  });

  testWidgets('forget cancels retries and never restores saved credentials',
      (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await h.sender.forget(h.saved.uuid);
    h.transport.emit(SenderConnectionState.connected);
    await tester.pump();
    await tester.pump(delay * 40);
    expect(h.store.byIdentity(h.saved.protocol, h.saved.uuid), isNull);
    expect(h.sender.isConnected, isFalse);
    expect(h.transport.connects, 1);
  });

  testWidgets('dispose during retry cannot schedule work or notify again',
      (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.connectGate = Completer<void>();
    h.transport.failConnect = true;
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await tester.pump(delay);
    h.sender.dispose();
    h.transport.connectGate!.complete();
    await tester.pump();
    await tester.pump(delay * 40);
    expect(h.transport.connects, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a cleared saved token stops a scheduled retry', (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await h.store.upsert(h.saved.copyWith(token: ''));
    await tester.pump(delay);
    expect(h.transport.connects, 1);
    expect(h.sender.reconnectStatus, isNull);
    expect(h.sender.activeTv, isNull);
  });

  testWidgets(
      'retry prefers a newly discovered endpoint without changing the pin',
      (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    h.sender.bindTransportForTest(discoveredDevices: [
      DiscoveredTv(
        uuid: h.saved.uuid,
        protocol: h.saved.protocol,
        name: h.saved.name,
        host: '192.0.2.99',
        addresses: ['192.0.2.99'],
        port: 9876,
        wssPort: 9443,
      )
    ]);
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await tester.pump(delay);
    expect(h.transport.lastTv?.host, '192.0.2.99');
    expect(h.transport.lastTv?.port, 9876);
    expect(h.transport.lastTv?.wssPort, 9443);
    expect(h.transport.lastToken, h.saved.token);
    expect(h.transport.lastPin, h.saved.certFingerprint);
  });

  testWidgets('retry is serialized and refreshes stored credentials',
      (tester) async {
    final h = await make();
    await h.sender.reconnect(h.saved);
    await tester.pump();
    await h.store.upsert(
        h.saved.copyWith(token: 'refreshed-token', certFingerprint: 'new-pin'));
    h.transport.connectGate = Completer<void>();
    h.transport.emit(SenderConnectionState.disconnected);
    await tester.pump();
    await tester.pump(delay);
    expect(h.transport.lastToken, 'refreshed-token');
    expect(h.transport.lastPin, 'new-pin');
    h.transport.emit(SenderConnectionState.error);
    await tester.pump();
    await tester.pump(delay * 4);
    expect(h.transport.connects, 2); // pending Future has not returned
    h.transport.connectGate!.complete();
    await tester.pump();
    expect(h.sender.isConnected, isTrue);
    expect(h.sender.reconnectStatus, isNull);
  });
}

class _FakeTransport implements TvTransport {
  @override
  String? lastError;

  _FakeTransport(this.protocol);

  @override
  final TvProtocol protocol;

  final _state = StreamController<SenderConnectionState>.broadcast();
  final _messages = StreamController<String>.broadcast();
  final _credentials = StreamController<TvCredentials>.broadcast();
  final _sas = StreamController<String>.broadcast();

  SenderConnectionState _current = SenderConnectionState.disconnected;
  bool failConnect = false;
  bool acceptsLoads = false;
  Future<void> Function()? duringLoad;
  Completer<void>? connectGate;
  SenderConnectionState? failureState;
  String? lastPin;
  DiscoveredTv? lastTv;
  int connects = 0;
  int contextQueries = 0;
  String? lastToken;

  void emit(SenderConnectionState state) {
    _current = state;
    if (!_state.isClosed) _state.add(state);
  }

  @override
  Stream<SenderConnectionState> get state => _state.stream;

  @override
  SenderConnectionState get currentState => _current;

  @override
  bool get isConnected => _current == SenderConnectionState.connected;

  @override
  Stream<String> get messages => _messages.stream;

  @override
  Stream<TvCredentials> get credentials => _credentials.stream;

  @override
  Stream<String> get sasCode => _sas.stream;

  @override
  Map<String, dynamic> get capabilities => const {};

  @override
  bool get supportsPairing => true;

  @override
  int get sasAttemptsLeft => 3;

  @override
  bool get lastSasWrong => false;

  @override
  bool submitSasCode(String code) => false;

  @override
  Future<void> connect({
    required DiscoveredTv tv,
    required String deviceName,
    required String deviceUUID,
    String? token,
    String? expectedPin,
  }) async {
    connects += 1;
    lastToken = token;
    lastTv = tv;
    lastPin = expectedPin;
    emit(SenderConnectionState.connecting);
    final gate = connectGate;
    if (gate != null) await gate.future;
    if (failConnect || failureState != null) {
      emit(failureState ?? SenderConnectionState.error);
      return;
    }
    emit(SenderConnectionState.connected);
  }

  @override
  Future<void> disconnect() async {
    emit(SenderConnectionState.disconnected);
  }

  @override
  Future<bool> sendContextQuery() async {
    contextQueries += 1;
    return true;
  }

  @override
  Future<bool> castVideo(PlayPayload video) async {
    await duringLoad?.call();
    return acceptsLoads;
  }

  @override
  Future<bool> castPlaylist(PlaylistPayload playlist) async {
    await duringLoad?.call();
    return acceptsLoads;
  }

  @override
  Future<bool> sendControl(String command) async => false;

  @override
  Future<bool> playlistJump(int index) async => false;

  @override
  Future<bool> queueAdd(PlayPayload item) async => false;

  @override
  Future<void> dispose() async {
    await _state.close();
    await _messages.close();
    await _credentials.close();
    await _sas.close();
  }
}
