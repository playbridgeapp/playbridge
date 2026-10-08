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

  test('mpv properties: Fit letterbox, Zoom panscan, Fill stretch', () {
    final fit = videoScalingProperties(VideoScalingMode.fit);
    expect(fit.keepaspect, 'yes');
    expect(fit.panscan, '0');

    final zoom = videoScalingProperties(VideoScalingMode.zoom);
    expect(zoom.keepaspect, 'yes');
    expect(zoom.panscan, '1');

    final fill = videoScalingProperties(VideoScalingMode.fill);
    expect(fill.keepaspect, 'no');
    expect(fill.panscan, '0');
  });
}
