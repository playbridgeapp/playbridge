import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/cert_manager.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_controller.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/server.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _FakeEngine extends PlayerEngine {
  @override
  String get state => 'idle';

  @override
  int get positionMs => 0;

  @override
  int get durationMs => 0;

  @override
  dynamic get tracks => null;

  @override
  dynamic get track => null;

  @override
  Future<void> setAudioTrack(dynamic track) async {}

  @override
  Future<void> setSubtitleTrack(dynamic track) async {}

  @override
  Future<void> open(QueueItem item) async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> pause() async {}

  @override
  Future<void> seek(Duration position) async {}

  @override
  Future<void> stop() async {}

  @override
  Future<void> dispose() async {
    super.dispose();
  }
}

void main() {
  test('candidate ports are consecutive, bounded, and normalize invalid input',
      () {
    expect(
      receiverPortCandidates(8765),
      List<int>.generate(32, (index) => 8765 + index),
    );
    expect(receiverPortCandidates(65534), [65534, 65535]);
    expect(receiverPortCandidates(0, maxAttempts: 2), [8765, 8766]);
    expect(receiverPortCandidates(8765, maxAttempts: 0), isEmpty);
  });

  test('recognizes supported address-in-use errors only', () {
    for (final code in [48, 98, 10048]) {
      expect(
        isAddressInUseError(
          SocketException('bind failed', osError: OSError('busy', code)),
        ),
        isTrue,
      );
    }
    expect(
      isAddressInUseError(const SocketException('Address already in use')),
      isTrue,
    );
    expect(
      isAddressInUseError(const SocketException(
        'The shared flag to bind() needs to be `true` if binding multiple times',
      )),
      isTrue,
    );
    expect(
      isAddressInUseError(
        const SocketException('Permission denied',
            osError: OSError('denied', 13)),
      ),
      isFalse,
    );
    expect(isAddressInUseError(StateError('Address already in use')), isFalse);
  });

  test('occupied preferred port falls forward and persists only the bound port',
      () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = PairingStore.forTest(prefs);
    final tlsDir = Directory.systemTemp.createTempSync('pb_server_tls_');
    final cert = await CertManager.loadOrCreate(dir: tlsDir);
    final blocker = await ServerSocket.bind(InternetAddress.anyIPv4, 0);
    final preferredPort = blocker.port;
    final player = PlayerController(engineForTest: _FakeEngine());
    var certificateLoads = 0;
    final server = ReceiverServer(
      player: player,
      store: store,
      certificateLoader: (_) async {
        certificateLoads++;
        return cert;
      },
    );

    try {
      final boundPort = await server.start(port: preferredPort);

      expect(boundPort, greaterThan(preferredPort));
      expect(boundPort, lessThanOrEqualTo(preferredPort + 31));
      expect(server.wssPort, boundPort);
      expect(store.receiverPort, boundPort);
      expect(certificateLoads, 1);
    } finally {
      await server.stop();
      await blocker.close();
      await player.dispose();
      if (tlsDir.existsSync()) tlsDir.deleteSync(recursive: true);
    }
  });

  test('non-collision bind failure is surfaced without trying another port',
      () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = PairingStore.forTest(prefs);
    final tlsDir = Directory.systemTemp.createTempSync('pb_server_tls_');
    final cert = await CertManager.loadOrCreate(dir: tlsDir);
    final player = PlayerController(engineForTest: _FakeEngine());
    var bindAttempts = 0;
    final server = ReceiverServer(
      player: player,
      store: store,
      certificateLoader: (_) async => cert,
      secureServerBinder: (_, __, ___, ____) async {
        bindAttempts++;
        throw const SocketException(
          'Permission denied',
          osError: OSError('Permission denied', 13),
        );
      },
    );

    try {
      await expectLater(server.start(), throwsA(isA<SocketException>()));
      expect(bindAttempts, 1);
      expect(server.wssPort, isNull);
      expect(store.receiverPort, PairingStore.defaultReceiverPort);
      expect(server.tlsError, isNotNull);
    } finally {
      await server.stop();
      await player.dispose();
      if (tlsDir.existsSync()) tlsDir.deleteSync(recursive: true);
    }
  });
}
