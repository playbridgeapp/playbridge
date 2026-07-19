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
}
