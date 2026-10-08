import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/playback_speed.dart';

void main() {
  test('offers the Android TV speed list and labels', () {
    expect(playbackSpeeds, [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]);
    expect(
      playbackSpeeds.map(playbackSpeedLabel).toList(),
      [
        '0.25x',
        '0.5x',
        '0.75x',
        '1.0x (Normal)',
        '1.25x',
        '1.5x',
        '1.75x',
        '2.0x',
      ],
    );
    expect(playbackSpeedChip(1.0), '1.0x');
    expect(playbackSpeedChip(1.5), '1.5x');
  });

  test('steps one notch and clamps at the ends', () {
    expect(stepPlaybackSpeed(1.0, 1), 1.25);
    expect(stepPlaybackSpeed(1.0, -1), 0.75);
    expect(stepPlaybackSpeed(0.25, -1), 0.25);
    expect(stepPlaybackSpeed(2.0, 1), 2.0);
    expect(stepPlaybackSpeed(1.0, 0), 1.0);
  });

  test('snaps off-ladder and non-finite rates', () {
    expect(nearestPlaybackSpeed(1.3), 1.25);
    expect(nearestPlaybackSpeed(1.9), 2.0);
    expect(nearestPlaybackSpeed(double.nan), 1.0);
    expect(nearestPlaybackSpeed(double.infinity), 1.0);
  });
}
