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

  test('legacy UPnP records and selections match refreshed identities',
      () async {
    for (final protocol in [TvProtocol.dlna, TvProtocol.roku]) {
      final legacyId = '${protocol.name}:uuid:Device-X::upnp:rootdevice';
      SharedPreferences.setMockInitialValues({
        preferenceKey: jsonEncode([
          {..._record(protocol).toJson(), 'uuid': legacyId},
        ]),
        'pb.sender.playback_selection': jsonEncode({
          'kind': 'tv',
          'protocol': protocol.name,
          'uuid': legacyId,
        }),
      });
      final store = await TvConnectionStore.load();
      final currentKey = receiverIdentityKey(protocol, 'uuid:Device-X');
      expect(store.selection.identityKey, currentKey);
      expect(store.byIdentity(protocol, 'uuid:Device-X'), isNotNull);
      await store.markConnected('uuid:Device-X',
          protocol: protocol, host: '192.0.2.2');
      expect(store.byIdentity(protocol, 'uuid:Device-X')!.host, '192.0.2.2');
      expect(store.byIdentity(protocol, 'uuid:Other'), isNull);
      await store.forget('uuid:Device-X', protocol: protocol);
      expect(store.tvs, isEmpty);
      expect(store.selection.isLocal, isTrue);
    }
  });

  test('normalized UPnP upsert replaces an older full-USN record', () async {
    final store = await TvConnectionStore.load();
    final base = _record(TvProtocol.dlna).toJson();
    await store.upsert(
        TvRecord.fromJson({...base, 'uuid': 'uuid:Device-X::upnp:rootdevice'}));
    await store.upsert(TvRecord.fromJson({...base, 'uuid': 'uuid:Device-X'}));
    expect(store.tvs, hasLength(1));
    expect(store.tvs.single.uuid, 'uuid:Device-X');
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
