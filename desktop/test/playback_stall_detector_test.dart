import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/playback_stall_detector.dart';

void main() {
  test('startup and mid-play budgets, single report', () {
    for (final firstFrame in [false, true]) {
      final d = PlaybackStallDetector();
      final timeout = firstFrame ? 45000 : 30000;
      bool sample(int now) => d.sample(
          nowMs: now,
          buffering: true,
          playbackRequested: true,
          hasFirstFrame: firstFrame,
          positionMs: 0,
          bufferedAheadMs: 0);
      expect(sample(0), false);
      expect(sample(timeout - 1), false);
      expect(sample(timeout), true);
      expect(sample(timeout + 1), false);
    }
  });
  test('progress, buffer growth, pause, seek and playing reset the timer', () {
    for (final reason in ['position', 'buffer', 'pause', 'seek', 'playing']) {
      final d = PlaybackStallDetector();
      d.sample(
          nowMs: 0,
          buffering: true,
          playbackRequested: true,
          hasFirstFrame: false,
          positionMs: 0,
          bufferedAheadMs: 0);
      final p = reason == 'position' ? 1 : 0;
      final b = reason == 'buffer' ? 1 : 0;
      expect(
          d.sample(
              nowMs: 29000,
              buffering: reason != 'playing',
              playbackRequested: reason != 'pause',
              hasFirstFrame: false,
              positionMs: p,
              bufferedAheadMs: b,
              seeking: reason == 'seek'),
          false);
      bool sample(int now) => d.sample(
          nowMs: now,
          buffering: true,
          playbackRequested: true,
          hasFirstFrame: false,
          positionMs: p,
          bufferedAheadMs: b);
      expect(sample(30000), false);
      expect(sample(58999), false);
      expect(sample(60000), true);
    }
  });
  test('a user pause never expires and replacement resets the budget', () {
    final d = PlaybackStallDetector();
    for (final time in [0, 100000]) {
      expect(
          d.sample(
              nowMs: time,
              buffering: true,
              playbackRequested: false,
              hasFirstFrame: false,
              positionMs: 0,
              bufferedAheadMs: 0),
          false);
    }
    d.reset();
    expect(
        d.sample(
            nowMs: 130000,
            buffering: true,
            playbackRequested: true,
            hasFirstFrame: false,
            positionMs: 0,
            bufferedAheadMs: 0),
        false);
  });
}
