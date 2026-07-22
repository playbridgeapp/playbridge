import 'dart:io';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:test/test.dart';

void main() {
  final library = File(
    '../../desktop/native/cast_core/macos/libplaybridge_cast_core_ffi.dylib',
  );

  test(
    'owns a real native scanner through completion',
    () async {
      final CastCoreLibrary core;
      try {
        core = CastCoreLibrary.open(libraryPath: library.absolute.path);
      } on UnsupportedError catch (error) {
        markTestSkipped('The packaged native library is stale: $error');
        return;
      }
      expect(core.abiVersion, 1);
      final scanner = core.discover(
        protocols: {ReceiverProtocol.playBridge},
        timeout: const Duration(milliseconds: 500),
      );
      final events = await scanner.events.toList().timeout(
            const Duration(seconds: 3),
          );

      expect(events.whereType<DiscoveryStarted>(), hasLength(1));
      expect(events.whereType<DiscoveryFinished>(), hasLength(1));
    },
    skip: !Platform.isMacOS || !library.existsSync()
        ? 'The packaged macOS library is not available on this host'
        : false,
  );
}
