import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart';
import 'package:playbridge_desktop/tv_discovery.dart';

void main() {
  test('keeps a location-only DLNA receiver connectable', () {
    final device = discoveredTvFromRust(const ReceiverInfo(
      id: 'dlna:receiver-1',
      protocol: ReceiverProtocol.dlna,
      name: 'Living Room TV',
      addresses: [],
      location: 'http://192.0.2.12:1400/device.xml',
    ));

    expect(device, isNotNull);
    expect(device!.host, isEmpty);
    expect(device.location, 'http://192.0.2.12:1400/device.xml');
  });

  test('Rust PlayBridge result wins over matching Bonsoir fallback', () {
    final rust = _device(
      uuid: 'receiver-1',
      name: 'Rust receiver',
      host: '192.168.1.20',
      port: 8765,
      wssPort: null,
    );
    final bonjour = _device(
      uuid: 'receiver-1',
      name: 'Bonjour receiver',
      host: '192.168.1.21',
      port: 8765,
      wssPort: 8766,
    );

    final merged = mergeDiscoveredDevices(
      rust: [rust],
      bonjourFallback: [bonjour],
    );

    expect(merged, hasLength(1));
    expect(merged.single.name, 'Rust receiver');
    expect(merged.single.host, '192.168.1.20');
    expect(merged.single.wssPort, 8766);
  });

  test('protocol identity prevents cross-protocol receiver collisions', () {
    final playBridge = _device(uuid: 'shared', name: 'Native');
    final dlna = _device(
      uuid: 'shared',
      name: 'DLNA',
      protocol: TvProtocol.dlna,
      port: null,
      wssPort: null,
    );

    final merged = mergeDiscoveredDevices(
      rust: [dlna, playBridge],
      bonjourFallback: const [],
    );

    expect(merged.map((device) => device.protocol), [
      TvProtocol.playBridge,
      TvProtocol.dlna,
    ]);
  });

  test('device identity key includes protocol', () {
    final playBridge = _device(uuid: 'shared', name: 'Native');
    final dlna = _device(
      uuid: 'shared',
      name: 'DLNA',
      protocol: TvProtocol.dlna,
    );

    expect(playBridge.identityKey, 'playBridge:shared');
    expect(dlna.identityKey, 'dlna:shared');
    expect(playBridge.identityKey, isNot(dlna.identityKey));
  });
}

DiscoveredTv _device({
  required String uuid,
  required String name,
  String host = '192.168.1.10',
  TvProtocol protocol = TvProtocol.playBridge,
  int? port = 8765,
  int? wssPort = 8765,
}) =>
    DiscoveredTv(
      uuid: uuid,
      protocol: protocol,
      name: name,
      host: host,
      port: port,
      wssPort: wssPort,
    );
