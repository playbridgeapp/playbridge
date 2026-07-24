import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/tv_connection_store.dart';
import 'package:playbridge_desktop/tv_discovery.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  const preferenceKey = 'pb.sender.paired_tvs';

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  test('legacy records migrate to PlayBridge with their host as an address',
      () async {
    SharedPreferences.setMockInitialValues({
      preferenceKey: jsonEncode([
        {
          'uuid': 'legacy-tv',
          'name': 'Legacy TV',
          'host': '192.168.1.20',
          'port': 8765,
          'token': 'token',
          'lastConnected': 123,
        },
      ]),
    });

    final store = await TvConnectionStore.load();
    final record = store.byIdentity(TvProtocol.playBridge, 'legacy-tv');

    expect(record, isNotNull);
    expect(record!.protocol, TvProtocol.playBridge);
    expect(record.allAddresses, ['192.168.1.20']);

    final prefs = await SharedPreferences.getInstance();
    final saved = jsonDecode(prefs.getString(preferenceKey)!) as List;
    expect(saved.single['protocol'], 'playBridge');
    expect(saved.single['addresses'], ['192.168.1.20']);
  });

  test('same stable id is stored independently for each protocol', () async {
    final store = await TvConnectionStore.load();
    await store.upsert(_record(TvProtocol.playBridge));
    await store.upsert(_record(TvProtocol.dlna));

    expect(store.tvs, hasLength(2));
    expect(
        store.byIdentity(TvProtocol.playBridge, 'shared')?.name, 'PlayBridge');
    expect(store.byIdentity(TvProtocol.dlna, 'shared')?.name, 'DLNA');

    await store.forget('shared', protocol: TvProtocol.dlna);
    expect(store.tvs, hasLength(1));
    expect(store.byIdentity(TvProtocol.playBridge, 'shared'), isNotNull);
  });

  test('upsert replaces only the matching protocol identity', () async {
    final store = await TvConnectionStore.load();
    await store.upsert(_record(TvProtocol.playBridge));
    await store.upsert(_record(TvProtocol.dlna));
    await store.upsert(
      _record(TvProtocol.dlna, host: '192.168.1.99', name: 'Updated DLNA'),
    );

    expect(store.tvs, hasLength(2));
    expect(store.byIdentity(TvProtocol.dlna, 'shared')?.host, '192.168.1.99');
    expect(store.byIdentity(TvProtocol.playBridge, 'shared')?.host,
        '192.168.1.10');
  });
}

TvRecord _record(
  TvProtocol protocol, {
  String host = '192.168.1.10',
  String? name,
}) =>
    TvRecord(
      uuid: 'shared',
      protocol: protocol,
      name: name ?? protocol.label,
      host: host,
      addresses: [host, 'fe80::1%en0'],
      port: 8765,
      wssPort: protocol == TvProtocol.playBridge ? 8766 : null,
      location:
          protocol == TvProtocol.dlna ? 'http://$host:1400/device.xml' : null,
      token: protocol == TvProtocol.playBridge ? 'token' : '',
      certFingerprint: null,
      capabilities: const {'pause': true},
      lastConnected: DateTime.fromMillisecondsSinceEpoch(1),
    );
