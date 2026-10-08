import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/playback_clock.dart';

void main() {
  test('remaining clock is signed like Android TV', () {
    expect(formatRemainingClock(0, 65000), '-01:05');
    expect(formatRemainingClock(5000, 65000), '-01:00');
    expect(formatRemainingClock(65000, 65000), '-00:00');
    expect(formatPlaybackClock(3661000), '1:01:01');
  });

  test('held arrow keys escalate from 10s to 50s', () {
    expect(seekStepMs(0), 10000);
    expect(seekStepMs(10), 10000);
    expect(seekStepMs(11), 50000);
  });
}
