import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('still-watching preferences default and persist', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final store = PairingStore.forTest(prefs);

    expect(store.stillWatchingEnabled, isFalse);
    expect(store.stillWatchingThresholdMinutes, 90);
    expect(store.stillWatchingResponseSeconds, 300);

    await store.setStillWatchingEnabled(true);
    await store.setStillWatchingThresholdMinutes(180);
    await store.setStillWatchingResponseSeconds(120);
    expect(store.stillWatchingEnabled, isTrue);
    expect(store.stillWatchingThresholdMinutes, 180);
    expect(store.stillWatchingResponseSeconds, 120);
  });

  test('invalid duration falls back to 90 minutes', () async {
    SharedPreferences.setMockInitialValues({
      'pb.still_watching_threshold_min': 17,
    });
    final prefs = await SharedPreferences.getInstance();
    final store = PairingStore.forTest(prefs);
    expect(store.stillWatchingThresholdMinutes, 90);

    await store.setStillWatchingThresholdMinutes(17);
    expect(store.stillWatchingThresholdMinutes, 90);
  });

  test('temporary testing presets fall back to 90 minutes', () async {
    SharedPreferences.setMockInitialValues({
      'pb.still_watching_threshold_min': 5,
    });
    final prefs = await SharedPreferences.getInstance();
    final store = PairingStore.forTest(prefs);

    expect(store.stillWatchingThresholdMinutes, 90);
    await store.setStillWatchingThresholdMinutes(1);
    expect(store.stillWatchingThresholdMinutes, 90);
  });
}
