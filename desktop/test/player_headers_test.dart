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

  group('mediaHeadersForPlayer', () {
    test('collapses Accept-Language and weighted Accept like the phone', () {
      final out = mediaHeadersForPlayer({
        'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0)',
        'Accept-Language': 'en-US,en;q=0.9',
        'Accept': 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.8',
        'Referer': 'https://embed.st/',
        'Origin': 'https://embed.st',
        'Sec-Fetch-Site': 'cross-site',
      });

      expect(out?['User-Agent'], contains('Macintosh'));
      expect(out?['Accept-Language'], 'en-US');
      expect(out?['Accept'], '*/*');
      expect(out?['Referer'], 'https://embed.st/');
      expect(out?['Origin'], 'https://embed.st');
      expect(out?.keys.any((k) => k.toLowerCase().startsWith('sec-fetch')), isFalse);
    });

    test('supplies a fallback User-Agent when missing', () {
      final out = mediaHeadersForPlayer({
        'Referer': 'https://embed.st/',
      });
      expect(out?['User-Agent'], defaultPlayerUserAgent);
    });
  });
}
