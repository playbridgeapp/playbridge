import 'dart:io';
import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/cert_manager.dart';

void main() {
  final library = File(
    'native/cast_core/macos/libplaybridge_cast_core_ffi.dylib',
  );

  for (final descriptor in <Map<String, Object?>?>[
    null,
    {
      'version': 1,
      'surfaces': {
        'future_surface': {
          'engines': {
            'future_engine': {
              'keys': ['future_key']
            },
          },
        },
      },
    },
    {'version': 1, 'surfaces': <String, Object?>{}},
  ]) {
    final replace = descriptor?['surfaces'] is Map &&
        (descriptor!['surfaces'] as Map).isEmpty;
    test(
      'native ABI 1 loopback auth ${replace ? 'replaces' : descriptor == null ? 'omits' : 'carries'} descriptor',
      () async {
        final directory =
            await Directory.systemTemp.createTemp('receiver-descriptor-test-');
        final certificate = await CertManager.loadOrCreate(
          commonName: 'Loopback Receiver Test',
          dir: directory,
        );
        final runtime = ReceiverRuntime.start(
          ReceiverRuntimeConfig(
            name: 'Loopback Receiver Test',
            uuid: 'loopback-receiver',
            certificateDer: certificate.certificateDer,
            privateKeyDer: certificate.privateKeyDer,
            privateKeyKind: certificate.privateKeyKind,
            preferredPort: 0,
            authorizedTokens: const ['TEST_ONLY_NOT_A_CREDENTIAL'],
            capabilities: descriptor,
          ),
          libraryPath: library.absolute.path,
        );
        final authenticated = replace
            ? runtime.events
                .firstWhere((event) => event['event'] == 'authenticated')
            : null;
        final http = HttpClient()
          ..badCertificateCallback = (cert, host, port) =>
              host == '127.0.0.1' &&
              base64Encode(cert.der) == certificate.certificateDer;
        WebSocket? socket;
        try {
          final port =
              await runtime.started.timeout(const Duration(seconds: 5));
          socket = await WebSocket.connect('wss://127.0.0.1:$port/',
              customClient: http);
          final frames = StreamIterator(socket);
          socket.add(jsonEncode({
            'type': 'auth',
            'token': 'TEST_ONLY_NOT_A_CREDENTIAL',
          }));
          expect(await frames.moveNext().timeout(const Duration(seconds: 5)),
              isTrue);
          final response =
              jsonDecode(frames.current as String) as Map<String, dynamic>;
          expect(response['success'], isTrue);
          expect(response.containsKey('capabilities'), descriptor != null);
          expect(response['capabilities'], descriptor);
          if (replace) {
            final changed = <String, Object?>{
              'version': 1,
              'surfaces': <String, Object?>{},
              'volume': {
                'scope': 'system',
                'operations': ['relative_step'],
                'fallbackScope': 'app'
              }
            };
            runtime.replaceCapabilities(changed);
            final id = (await authenticated!)['connection_id']! as int;
            // FIFO host command barrier: no timing assumption about the runtime worker.
            runtime.sendTo(id, {'type': 'context', 'active': 'idle'});
            expect(await frames.moveNext().timeout(const Duration(seconds: 5)),
                isTrue);
            final next = await WebSocket.connect('wss://127.0.0.1:$port/',
                customClient: http);
            try {
              next.add(jsonEncode(
                  {'type': 'auth', 'token': 'TEST_ONLY_NOT_A_CREDENTIAL'}));
              final updated = jsonDecode(await next.first
                  .timeout(const Duration(seconds: 5)) as String) as Map;
              expect(updated['capabilities'], changed);
            } finally {
              await next.close();
            }
          }
          await frames.cancel();
        } finally {
          await socket?.close();
          http.close(force: true);
          runtime.dispose();
          await directory.delete(recursive: true);
        }
      },
      skip: !Platform.isMacOS || !library.existsSync()
          ? 'The packaged macOS native library is unavailable'
          : false,
    );
  }

  test(
    'shared Rust receiver starts with the Desktop TLS identity',
    () async {
      final directory =
          await Directory.systemTemp.createTemp('playbridge-rust-receiver-');
      final certificate = await CertManager.loadOrCreate(
        commonName: 'PlayBridge Receiver Test',
        dir: directory,
      );
      final runtime = ReceiverRuntime.start(
        ReceiverRuntimeConfig(
          name: 'Receiver Test',
          uuid: 'receiver-test-id',
          certificateDer: certificate.certificateDer,
          privateKeyDer: certificate.privateKeyDer,
          privateKeyKind: certificate.privateKeyKind,
          preferredPort: 0,
          fallbackAttempts: 1,
          players: const ['internal_mpv'],
          screenMirrorWebRtc: true,
        ),
        libraryPath: library.absolute.path,
      );
      try {
        final port = await runtime.started.timeout(const Duration(seconds: 5));
        expect(port, greaterThan(0));
      } finally {
        runtime.dispose();
        await directory.delete(recursive: true);
      }
    },
    skip: !Platform.isMacOS || !library.existsSync()
        ? 'The packaged macOS native library is unavailable'
        : false,
  );
}
