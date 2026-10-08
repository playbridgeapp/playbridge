import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/subtitle_delay.dart';

void main() {
  test('clamps to ±120 seconds', () {
    expect(clampSubtitleDelayMs(0), 0);
    expect(clampSubtitleDelayMs(120000), 120000);
    expect(clampSubtitleDelayMs(-120000), -120000);
    expect(clampSubtitleDelayMs(120001), 120000);
    expect(clampSubtitleDelayMs(-999999), -120000);
  });

  test('fine and coarse steps', () {
    expect(adjustSubtitleDelayMs(0, subtitleDelayFineMs), 100);
    expect(adjustSubtitleDelayMs(0, -subtitleDelayFineMs), -100);
    expect(adjustSubtitleDelayMs(0, subtitleDelayCoarseMs), 1000);
    expect(adjustSubtitleDelayMs(119950, subtitleDelayFineMs), 120000);
  });

  test('labels and mpv seconds', () {
    expect(subtitleDelayLabel(0), 'Synced');
    expect(subtitleDelayLabel(100), '+100 ms');
    expect(subtitleDelayLabel(-1000), '-1000 ms');
    expect(subtitleDelayMpvSeconds(100), '0.1');
    expect(subtitleDelayMpvSeconds(-1000), '-1.0');
  });
}
