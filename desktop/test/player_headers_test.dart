import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/player_headers.dart';

void main() {
  group('sanitizePlayerHeaders', () {
    test('keeps media-relevant headers', () {
      final out = sanitizePlayerHeaders({
        'User-Agent': 'Mozilla/5.0',
        'Referer': 'https://embed.st/',
        'Origin': 'https://embed.st',
        'Accept': '*/*',
        'Accept-Language': 'en-US',
        'Cookie': 'sid=1',
      });

      expect(out, {
        'User-Agent': 'Mozilla/5.0',
        'Referer': 'https://embed.st/',
        'Origin': 'https://embed.st',
        'Accept': '*/*',
        'Accept-Language': 'en-US',
        'Cookie': 'sid=1',
      });
    });

    test('strips Sec-Fetch and other browser-only headers', () {
      final out = sanitizePlayerHeaders({
        'User-Agent': 'Mozilla/5.0',
        'Referer': 'https://embed.st/',
        'Origin': 'https://embed.st',
        'Accept': '*/*',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site',
        'Range': 'bytes=0-',
        'Accept-Encoding': 'gzip',
        'Host': 'lb10.strmd.st',
      });

      expect(out, {
        'User-Agent': 'Mozilla/5.0',
        'Referer': 'https://embed.st/',
        'Origin': 'https://embed.st',
        'Accept': '*/*',
      });
    });

    test('returns null for empty or fully-stripped maps', () {
      expect(sanitizePlayerHeaders(null), isNull);
      expect(sanitizePlayerHeaders({}), isEmpty);
      expect(
        sanitizePlayerHeaders({
          'Sec-Fetch-Site': 'cross-site',
          'Range': 'bytes=0-',
        }),
        isNull,
      );
    });
  });
}
