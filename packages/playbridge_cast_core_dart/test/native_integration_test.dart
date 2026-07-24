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

  test(
    'owns proxy and browser receiver services through the native ABI',
    () async {
      final services = SenderServices.start(libraryPath: library.absolute.path);
      final started = services.events.firstWhere(
        (event) => event['event'] == 'started',
      );
      final temporary = await Directory.systemTemp.createTemp(
        'playbridge-sender-services-',
      );
      try {
        final startEvent = await started.timeout(const Duration(seconds: 3));
        expect(startEvent['proxyPort'], isA<int>());

        final file = File('${temporary.path}/video.mp4');
        await file.writeAsBytes([0, 1, 2, 3, 4, 5]);
        final media = await services.registerFile(
          host: '127.0.0.1',
          path: file.path,
        );
        final client = HttpClient();
        try {
          final request = await client.getUrl(Uri.parse(media.url));
          request.headers.set(HttpHeaders.rangeHeader, 'bytes=1-3');
          final response = await request.close();
          expect(response.statusCode, HttpStatus.partialContent);
          expect(
              await response.fold<List<int>>([], (all, bytes) {
                all.addAll(bytes);
                return all;
              }),
              [1, 2, 3]);
        } finally {
          client.close(force: true);
        }

        final browser = await services.startBrowser(preferredPort: 0);
        expect(browser.port, greaterThan(0));
        final health = 'http://127.0.0.1:${browser.port}/health';
        final healthClient = HttpClient();
        try {
          final response =
              await (await healthClient.getUrl(Uri.parse(health))).close();
          expect(response.statusCode, HttpStatus.ok);
        } finally {
          healthClient.close(force: true);
        }
        await services.stopBrowser();
      } finally {
        services.dispose();
        await temporary.delete(recursive: true);
      }
    },
    skip: !Platform.isMacOS || !library.existsSync()
        ? 'The packaged macOS library is not available on this host'
        : false,
  );
}
