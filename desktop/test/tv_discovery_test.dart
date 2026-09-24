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

  test('rescan keeps old devices visible while fresh results arrive', () {
    final results = DiscoveryScanResults();
    final old = _device(uuid: 'old', name: 'Old TV');
    final updated = _device(
      uuid: 'old',
      name: 'Old TV',
      host: '192.168.1.30',
    );
    final fresh = _device(uuid: 'fresh', name: 'New TV');

    results.begin();
    results.update('old', old);
    results.complete(succeeded: true);
    results.begin();
    expect(results.visible.toList(), [old]);

    results.update('old', updated);
    results.update('fresh', fresh);
    expect(results.visible.toList(), [updated, fresh]);
    results.complete(succeeded: true);
    expect(results.visible.toList(), [updated, fresh]);
  });

  test('completed scan removes missing devices without an empty-list gap', () {
    final results = DiscoveryScanResults();
    final old = _device(uuid: 'old', name: 'Old TV');
    results.begin();
    results.update('old', old);
    results.complete(succeeded: true);

    results.begin();
    expect(results.visible.toList(), [old]);
    results.complete(succeeded: true);
    expect(results.visible, isEmpty);
  });

  test('failed scan retains known devices and merges any fresh results', () {
    final results = DiscoveryScanResults();
    final old = _device(uuid: 'old', name: 'Old TV');
    final fresh = _device(uuid: 'fresh', name: 'New TV');
    results.begin();
    results.update('old', old);
    results.complete(succeeded: true);

    results.begin();
    results.update('fresh', fresh);
    results.complete(succeeded: false);
    expect(results.visible.toList(), [old, fresh]);
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
