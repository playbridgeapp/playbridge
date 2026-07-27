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

  test('extractHttpChildUrls finds stream and audio URIs', () {
    const body = '''
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="https://cdn.example/audio.m3u8?session=s"
#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="a"
https://cdn.example/v0.m3u8?session=s
#EXT-X-STREAM-INF:BANDWIDTH=5000,AUDIO="a"
https://cdn.example/v1.m3u8?session=s
''';
    final urls = TvCastMediaPreparer.extractHttpChildUrls(body);
    expect(urls, hasLength(3));
    expect(urls, contains('https://cdn.example/audio.m3u8?session=s'));
    expect(urls, contains('https://cdn.example/v1.m3u8?session=s'));
  });

  test('rewritePlaylistUrls replaces longest matches first', () {
    const body = '''
#EXTM3U
URI="https://cdn.example/a.m3u8?session=s"
https://cdn.example/v.m3u8?session=s
''';
    final rewritten = TvCastMediaPreparer.rewritePlaylistUrls(body, {
      'https://cdn.example/a.m3u8?session=s': 'http://127.0.0.1/s/a/p.m3u8',
      'https://cdn.example/v.m3u8?session=s': 'http://127.0.0.1/s/v/p.m3u8',
    });
    expect(rewritten, contains('http://127.0.0.1/s/a/p.m3u8'));
    expect(rewritten, contains('http://127.0.0.1/s/v/p.m3u8'));
    expect(rewritten, isNot(contains('cdn.example')));
  });
}
