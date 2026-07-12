import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/logging/log_store.dart';

void main() {
  group('redactSensitiveLogData', () {
    test('redacts complete bearer and basic authorization values', () {
      expect(
        redactSensitiveLogData('Authorization: Bearer secret-token'),
        'Authorization: <redacted>',
      );
      expect(
        redactSensitiveLogData('Proxy-Authorization=Basic dXNlcjpwYXNz'),
        'Proxy-Authorization=<redacted>',
      );
    });

    test('redacts quoted JSON authorization without consuming other fields',
        () {
      expect(
        redactSensitiveLogData(
          '{"authorization":"Bearer secret-token","status":"ok"}',
        ),
        '{"authorization":"<redacted>","status":"ok"}',
      );
    });

    test('continues to redact tokens, cookies, and query credentials', () {
      expect(
        redactSensitiveLogData(
          'token=secret cookie=session-id url=https://example.test/v?api_key=key&quality=hd',
        ),
        'token=<redacted> cookie=<redacted> url=https://example.test/v?api_key=<redacted>&quality=hd',
      );
    });
  });
}
