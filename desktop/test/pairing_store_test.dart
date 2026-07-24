import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('HLS quality preselection defaults off and persists changes', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await PairingStore.load();

    expect(store.preselectHlsQuality, isFalse);
    await store.setPreselectHlsQuality(true);

    final reloaded = await PairingStore.load();
    expect(reloaded.preselectHlsQuality, isTrue);
  });

  test('video output renderer defaults safely and persists changes', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await PairingStore.load();

    expect(store.hardwareVideoOutput, !Platform.isLinux);
    await store.setHardwareVideoOutput(true);
    expect((await PairingStore.load()).hardwareVideoOutput, isTrue);
    await store.setHardwareVideoOutput(false);
    expect((await PairingStore.load()).hardwareVideoOutput, isFalse);
  });

  test('receiver port defaults to 8765 and persists successful values',
      () async {
    SharedPreferences.setMockInitialValues({});
    final store = await PairingStore.load();

    expect(store.receiverPort, PairingStore.defaultReceiverPort);
    await store.setReceiverPort(8768);

    final reloaded = await PairingStore.load();
    expect(reloaded.receiverPort, 8768);
  });

  test('invalid persisted receiver ports fall back to 8765', () async {
    for (final invalidPort in [0, -1, 65536]) {
      SharedPreferences.setMockInitialValues({
        'pb.receiver_port': invalidPort,
      });
      final store = await PairingStore.load();
      expect(store.receiverPort, PairingStore.defaultReceiverPort);
    }
  });

  test('invalid receiver ports are not persisted', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await PairingStore.load();

    expect(() => store.setReceiverPort(0), throwsArgumentError);
    expect(() => store.setReceiverPort(65536), throwsArgumentError);
    expect(store.receiverPort, PairingStore.defaultReceiverPort);
  });

  test('Rust token digests update the matching paired device', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await PairingStore.load();
    await store.addPairedDevice(PairedDeviceRecord(
      deviceUUID: 'phone-id',
      deviceName: 'Phone',
      token: 'secret',
      lastConnected: DateTime.fromMillisecondsSinceEpoch(1),
    ));
    final digest = store.pairedDevices.single.token;

    await store.updateLastConnectedDigest(digest);

    final updated = store.pairedDevices.single;
    expect(updated.token, digest);
    expect(updated.lastConnected.millisecondsSinceEpoch, greaterThan(1));
  });
}
