import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/video_scaling.dart';

void main() {
  test('Fit / Zoom / Fill match the Android TV labels', () {
    expect(videoScalingId(VideoScalingMode.fit), 'Fit');
    expect(videoScalingLabel(VideoScalingMode.zoom), 'Crop to fill');
    expect(videoScalingLabel(VideoScalingMode.fill), 'Stretch');
  });

  test('Z cycles Fit → Zoom → Fill → Fit', () {
    expect(nextVideoScalingMode(VideoScalingMode.fit), VideoScalingMode.zoom);
    expect(nextVideoScalingMode(VideoScalingMode.zoom), VideoScalingMode.fill);
    expect(nextVideoScalingMode(VideoScalingMode.fill), VideoScalingMode.fit);
  });

  test('legacy Fixed Width/Height collapse to Fit', () {
    expect(parseVideoScalingMode('Fixed Width'), VideoScalingMode.fit);
    expect(parseVideoScalingMode('Fixed Height'), VideoScalingMode.fit);
    expect(parseVideoScalingMode('Zoom'), VideoScalingMode.zoom);
    expect(parseVideoScalingMode(null), VideoScalingMode.fit);
  });

  test('Video widget BoxFit: Fit contain, Zoom cover, Fill fill', () {
    expect(videoScalingBoxFit(VideoScalingMode.fit), BoxFit.contain);
    expect(videoScalingBoxFit(VideoScalingMode.zoom), BoxFit.cover);
    expect(videoScalingBoxFit(VideoScalingMode.fill), BoxFit.fill);
  });
}
