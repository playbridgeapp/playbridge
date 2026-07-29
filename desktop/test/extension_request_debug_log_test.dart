import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/extension_request_debug_log.dart';

void main() {
  test('full request logging follows debug mode', () {
    expect(extensionRequestDebugLoggingEnabled, kDebugMode);
  });

  group('formatExtensionCastRequestForDebug', () {
    test('shows useful browser headers in a deterministic order', () {
      final lines = formatExtensionCastRequestForDebug(
        url: 'https://media.example/video/master.m3u8',
        headers: {
          'User-Agent': 'Mozilla/5.0',
          'Accept': '*/*',
          'Origin': 'https://www.example.com',
          'Accept-Language': 'en-CA',
        },
      );

      expect(lines, [
        '[ext-bridge] extension cast request:',
        '[ext-bridge]   URL: https://media.example/video/master.m3u8',
        '[ext-bridge]   Headers (4):',
        '[ext-bridge]     Accept: */*',
        '[ext-bridge]     Accept-Language: en-CA',
        '[ext-bridge]     Origin: https://www.example.com',
        '[ext-bridge]     User-Agent: Mozilla/5.0',
      ]);
    });

    test('shows complete signed URLs and credential-bearing header values', () {
      final lines = formatExtensionCastRequestForDebug(
        url: 'https://user:password@media.example/master.m3u8'
            '?token=secret#fragment',
        headers: {
          'Authorization': 'Bearer secret-token',
          'Cookie': 'session=secret-cookie',
          'Referer': 'https://www.example.com/watch?id=private#player',
          'X-Api-Key': 'secret-api-key',
          'X-Custom': 'possibly-sensitive-value',
        },
      );
      final output = lines.join('\n');

      expect(
        output,
        contains(
          'URL: https://user:password@media.example/master.m3u8'
          '?token=secret#fragment',
        ),
      );
      expect(
        output,
        contains('Referer: https://www.example.com/watch?id=private#player'),
      );
      expect(output, contains('Authorization: Bearer secret-token'));
      expect(output, contains('Cookie: session=secret-cookie'));
      expect(output, contains('X-Api-Key: secret-api-key'));
      expect(output, contains('X-Custom: possibly-sensitive-value'));
    });

    test('keeps log output on one line per header', () {
      final lines = formatExtensionCastRequestForDebug(
        url: 'https://media.example/video.mp4',
        headers: {'User-Agent': 'first\r\nsecond\tthird'},
      );

      expect(
        lines.last,
        r'[ext-bridge]     User-Agent: first\r\nsecond\tthird',
      );
    });

    test('reports when no headers were provided', () {
      final lines = formatExtensionCastRequestForDebug(
        url: 'https://media.example/video.mp4',
      );

      expect(lines, contains('[ext-bridge]   Headers (0):'));
      expect(lines, contains('[ext-bridge]     (none)'));
    });
  });
}
