import 'dart:io';

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
    });
  });

  group('DlnaTransport lifecycle', () {
    test('connects and disconnects', () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) {
        req.response.headers.contentType = ContentType('text', 'xml');
        req.response.write('''<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <friendlyName>Living Room TV</friendlyName>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/avt</controlURL>
      </service>
    </serviceList>
  </device>
</root>''');
        req.response.close();
      });

      final transport = DlnaTransport();
      final states = <SenderConnectionState>[];
      final sub = transport.state.listen(states.add);

      final tv = DiscoveredTv(
        uuid: 'dlna-tv-1',
        protocol: TvProtocol.dlna,
        name: 'Living Room TV',
        host: server.address.host,
        port: server.port,
        wssPort: null,
        location: 'http://${server.address.host}:${server.port}/desc.xml',
      );

      await transport.connect(
        tv: tv,
        deviceName: 'Desktop Sender',
        deviceUUID: 'desktop-123',
      );
      await Future<void>.delayed(Duration.zero);

      expect(transport.isConnected, isTrue);
      expect(states, contains(SenderConnectionState.connected));

      await transport.disconnect();
      await Future<void>.delayed(Duration.zero);
      expect(transport.isConnected, isFalse);

      await sub.cancel();
      await transport.dispose();
      await server.close(force: true);
    });
  });

  group('RokuTransport lifecycle', () {
    test('connects and disconnects', () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) {
        req.response.headers.contentType = ContentType('text', 'xml');
        req.response.write('''<device-info><user-device-name>Bedroom Roku</user-device-name></device-info>''');
        req.response.close();
      });

      final transport = RokuTransport();
      final states = <SenderConnectionState>[];
      final sub = transport.state.listen(states.add);

      final tv = DiscoveredTv(
        uuid: 'roku-tv-1',
        protocol: TvProtocol.roku,
        name: 'Bedroom Roku',
        host: server.address.host,
        port: server.port,
        wssPort: null,
        location: null,
      );

      await transport.connect(
        tv: tv,
        deviceName: 'Desktop Sender',
        deviceUUID: 'desktop-123',
      );
      await Future<void>.delayed(Duration.zero);

      expect(transport.isConnected, isTrue);
      expect(states, contains(SenderConnectionState.connected));

      await transport.disconnect();
      await Future<void>.delayed(Duration.zero);
      expect(transport.isConnected, isFalse);

      await sub.cancel();
      await transport.dispose();
      await server.close(force: true);
    });
  });
}
