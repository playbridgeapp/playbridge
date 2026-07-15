import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/discovery.dart';

void main() {
  test('advertises the bound port in both SRV and wss_port', () {
    final publisher = DiscoveryPublisher(
      serviceName: 'Living Room',
      deviceId: 'receiver-uuid',
    );

    final service = publisher.serviceForPort(8766);

    expect(service.port, 8766);
    expect(service.attributes, {
      'uuid': 'receiver-uuid',
      'wss_port': '8766',
    });
  });

  test('rejects an invalid listener port before creating a broadcast', () {
    final publisher = DiscoveryPublisher(
      serviceName: 'Living Room',
      deviceId: 'receiver-uuid',
    );

    expect(() => publisher.serviceForPort(0), throwsArgumentError);
    expect(() => publisher.serviceForPort(65536), throwsArgumentError);
  });
}
