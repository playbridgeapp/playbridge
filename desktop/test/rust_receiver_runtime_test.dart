import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/cert_manager.dart';

void main() {
  final library = File(
    'native/cast_core/macos/libplaybridge_cast_core_ffi.dylib',
  );

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
