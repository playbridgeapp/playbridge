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

    test(
        'redacts proxy path session token and subresource capability parameter',
        () {
      expect(
        redactSensitiveLogData(
          '[tv-cast] proxied single media → http://192.168.1.30:61084/s/SAA4j85zgLaBL7x1L91L6A/media.mp4 (source=direct)',
        ),
        '[tv-cast] proxied single media → http://192.168.1.30:61084/s/<redacted>/media.mp4 (source=direct)',
      );
      expect(
        redactSensitiveLogData(
          'rewritten: /s/sess-987/manifest.edl',
        ),
        'rewritten: /s/<redacted>/manifest.edl',
      );
      expect(
        redactSensitiveLogData(
          'GET http://192.168.1.30:61084/s/sess-987/chunk.ts?uri=https%3A%2F%2Fcdn.example%2Fseg1.ts&cap=secret-cap',
        ),
        'GET http://192.168.1.30:61084/s/<redacted>/chunk.ts?uri=https%3A%2F%2Fcdn.example%2Fseg1.ts&cap=<redacted>',
      );
      expect(
        redactSensitiveLogData(
          '{"proxyUrl":"http://127.0.0.1:8888/s/secret-session/master.m3u8"}',
        ),
        '{"proxyUrl":"http://127.0.0.1:8888/s/<redacted>/master.m3u8"}',
      );
    });

    test(
        'redacts file grant proxy path tokens and encrypted proxy query tokens',
        () {
      expect(
        redactSensitiveLogData(
          'http://192.168.1.30:61084/media/grant-uuid-1234/local.mp4',
        ),
        'http://192.168.1.30:61084/media/<redacted>/local.mp4',
      );
      expect(
        redactSensitiveLogData(
          'file route: /media/grant-uuid-1234/audio.m4a',
        ),
        'file route: /media/<redacted>/audio.m4a',
      );
      expect(
        redactSensitiveLogData(
          'http://192.168.1.30:61084/proxy/hls/manifest.m3u8?token=encryptedTokenPayload',
        ),
        'http://192.168.1.30:61084/proxy/hls/manifest.m3u8?token=<redacted>',
      );
    });

    test('preserves ordinary URLs unchanged', () {
      expect(
        redactSensitiveLogData('https://example.com/videos/media.mp4'),
        'https://example.com/videos/media.mp4',
      );
      expect(
        redactSensitiveLogData('https://example.com/content/media/show.mp4'),
        'https://example.com/content/media/show.mp4',
      );
      expect(
        redactSensitiveLogData('https://example.com/media/movie.mp4'),
        'https://example.com/media/movie.mp4',
      );
      expect(
        redactSensitiveLogData('https://cdn.example.org/hls/master.m3u8'),
        'https://cdn.example.org/hls/master.m3u8',
      );
      expect(
        redactSensitiveLogData('https://example.com/watch?v=123'),
        'https://example.com/watch?v=123',
      );
    });
  });
}
