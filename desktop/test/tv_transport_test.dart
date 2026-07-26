import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:playbridge_desktop/tv_transport.dart';

void main() {
  group('TvTransportFactory', () {
    test('creates correct transport type for each protocol', () {
      final pbTransport = TvTransportFactory.create(TvProtocol.playBridge);
      expect(pbTransport, isA<PlayBridgeTransport>());
      expect(pbTransport.protocol, equals(TvProtocol.playBridge));
      expect(pbTransport.supportsPairing, isTrue);

      final dlnaTransport = TvTransportFactory.create(TvProtocol.dlna);
      expect(dlnaTransport, isA<DlnaTransport>());
      expect(dlnaTransport.protocol, equals(TvProtocol.dlna));
      expect(dlnaTransport.supportsPairing, isFalse);

      final rokuTransport = TvTransportFactory.create(TvProtocol.roku);
      expect(rokuTransport, isA<RokuTransport>());
      expect(rokuTransport.protocol, equals(TvProtocol.roku));
      expect(rokuTransport.supportsPairing, isFalse);

      final googleCastTransport =
          TvTransportFactory.create(TvProtocol.googleCast);
      expect(googleCastTransport, isA<GoogleCastTransport>());
      expect(googleCastTransport.protocol, equals(TvProtocol.googleCast));
      expect(googleCastTransport.supportsPairing, isFalse);

      // BrowserTransport needs a live SenderServices worker; content-type
      // helpers below cover the pure browser URL logic without native services.
    });
  });

  group('browserContentTypeForUrl', () {
    test('detects HLS and DASH from path and query', () {
      expect(
        browserContentTypeForUrl('https://cdn.example/video.m3u8'),
        'application/vnd.apple.mpegurl',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/master.m3u8?token=x'),
        'application/vnd.apple.mpegurl',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/stream?format=m3u8'),
        'application/vnd.apple.mpegurl',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/manifest.mpd'),
        'application/dash+xml',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/v?type=mpd'),
        'application/dash+xml',
      );
    });

    test('detects progressive media extensions', () {
      expect(
        browserContentTypeForUrl('https://cdn.example/clip.mp4'),
        'video/mp4',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/clip.webm'),
        'video/webm',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/track.mp3'),
        'audio/mpeg',
      );
      expect(
        browserContentTypeForUrl('https://cdn.example/unknown.bin'),
        isNull,
      );
    });
  });
}
