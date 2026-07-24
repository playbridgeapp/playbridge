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
    });
  });
}
