import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart' as rust;
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

  test('reads explicit HLS container hints from proxied Cast URLs', () {
    expect(
      googleCastHlsFormatForUrl(
        'http://192.0.2.1:9000/s/id/playlist.m3u8?pb_hls_format=ts',
      ),
      'ts',
    );
    expect(
      googleCastHlsFormatForUrl(
        'http://192.0.2.1:9000/media/id/master.m3u8?pb_hls_format=fmp4',
      ),
      'fmp4',
    );
    expect(
      googleCastHlsFormatForUrl('https://example.test/master.m3u8'),
      isNull,
    );
    expect(googleCastHlsAudioFormat('ts'), 'ts_aac');
    expect(googleCastHlsVideoFormat('ts'), 'mpeg2_ts');
    expect(googleCastHlsAudioFormat('fmp4'), 'fmp4');
    expect(googleCastHlsVideoFormat('fmp4'), 'fmp4');
  });

  test('treats ambiguous HLS as VOD unless an explicit live hint exists', () {
    expect(
      googleCastStreamTypeForUrl(
        'http://192.0.2.1:9000/s/id/playlist.m3u8?pb_hls_stream=buffered',
      ),
      'BUFFERED',
    );
    expect(
      googleCastStreamTypeForUrl(
        'http://192.0.2.1:9000/s/id/playlist.m3u8?pb_hls_stream=live',
      ),
      'LIVE',
    );
    expect(
      googleCastStreamTypeForUrl('https://example.test/master.m3u8'),
      'BUFFERED',
    );
  });

  test('recognizes receiver-ended errors as requiring a fresh session', () {
    const ended = rust.CastSessionError(
      operation: 'load',
      message: 'receiver exited',
      reason: 'receiver_ended',
    );
    const network = rust.CastSessionError(
      operation: 'status',
      message: 'network timeout',
    );

    expect(isGoogleCastRestartableSessionError(ended), isTrue);
    expect(isGoogleCastRestartableSessionError(network), isFalse);

    const unresponsive = rust.CastSessionError(
      operation: 'load',
      message: 'Google Cast receiver application stopped responding',
      reason: 'session_unresponsive',
    );
    expect(isGoogleCastRestartableSessionError(unresponsive), isTrue);

    const connectionLost = rust.CastSessionError(
      operation: 'load',
      message: 'receiver transport failed: socket closed',
      reason: 'connection_lost',
    );
    expect(isGoogleCastRestartableSessionError(connectionLost), isTrue);
  });

  test('publishes only terminal session errors as global errors', () {
    const statusTimeout = rust.CastSessionError(
      requestId: 'status-1',
      operation: 'status',
      message: 'status timed out',
    );
    const maintenanceWarning = rust.CastSessionError(
      operation: 'maintenance',
      message: 'heartbeat delayed',
    );
    const connectionLost = rust.CastSessionError(
      requestId: 'load-1',
      operation: 'load',
      message: 'socket closed',
      reason: 'connection_lost',
    );

    expect(shouldPublishGlobalCastSessionError(statusTimeout), isFalse);
    expect(shouldPublishGlobalCastSessionError(maintenanceWarning), isFalse);
    expect(shouldPublishGlobalCastSessionError(connectionLost), isTrue);
  });

  test('delays initial Cast status and backs off after timeouts', () {
    expect(
      googleCastStatusPollDelay(0, initial: true),
      const Duration(seconds: 3),
    );
    expect(googleCastStatusPollDelay(0), const Duration(seconds: 5));
    expect(googleCastStatusPollDelay(1), const Duration(seconds: 10));
    expect(googleCastStatusPollDelay(2), const Duration(seconds: 20));
    expect(googleCastStatusPollDelay(3), const Duration(seconds: 30));
    expect(googleCastStatusPollDelay(99), const Duration(seconds: 30));
    expect(googleCastStatusFailuresRequireFreshSession(2), isFalse);
    expect(googleCastStatusFailuresRequireFreshSession(3), isTrue);
  });
}
