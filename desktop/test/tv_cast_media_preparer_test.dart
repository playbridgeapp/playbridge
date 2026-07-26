import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/tv_cast_media_preparer.dart';

void main() {
  test('buildProxiedDemuxedMaster points A/V at proxy URLs', () {
    const video = 'http://192.168.1.10:9/s/vid/playlist.m3u8';
    const audio = 'http://192.168.1.10:9/s/aud/playlist.m3u8';
    final body = TvCastMediaPreparer.buildProxiedDemuxedMaster(
      videoProxyUrl: video,
      audioProxyUrl: audio,
    );

    expect(body, contains('#EXTM3U'));
    expect(body, contains('TYPE=AUDIO'));
    expect(body, contains('URI="$audio"'));
    expect(body, contains(video));
    expect(body, contains('AUDIO="audio"'));
    // One stream-inf only.
    expect('#EXT-X-STREAM-INF'.allMatches(body).length, 1);
  });
}
