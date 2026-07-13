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
}
