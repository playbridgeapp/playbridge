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

  test('classifies finite HLS as buffered and explicit live HLS as live', () {
    expect(
      TvCastMediaPreparer.hlsStreamTypeForBody('''
#EXTM3U
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:6,
000.jpg
#EXT-X-ENDLIST
'''),
      'buffered',
    );
    expect(
      TvCastMediaPreparer.hlsStreamTypeForBody('''
#EXTM3U
#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES
#EXT-X-PART:DURATION=1,URI="part.m4s"
'''),
      'live',
    );
    expect(TvCastMediaPreparer.hlsStreamTypeForBody(null), 'buffered');
  });

  test('infers HLS segment format only from media-playlist evidence', () {
    expect(
      TvCastMediaPreparer.hlsSegmentFormatForBody('''
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6,
segment.jpg
'''),
      'ts',
    );
    expect(
      TvCastMediaPreparer.hlsSegmentFormatForBody('''
#EXTM3U
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6,
segment.m4s
'''),
      'fmp4',
    );
    expect(
      TvCastMediaPreparer.hlsSegmentFormatForBody('''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000
media.m3u8
'''),
      isNull,
    );
    expect(
      TvCastMediaPreparer.hlsSegmentFormatForBody('''
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6,
audio.aac
'''),
      isNull,
    );
    expect(
      TvCastMediaPreparer.hlsSegmentFormatForBody('''
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6,
captions.vtt
'''),
      isNull,
    );
  });

  test('returns every HLS variant in descending bandwidth order', () {
    expect(
      TvCastMediaPreparer.hlsVariantUrlsForBody('''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000
low/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000
high/playlist.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio/playlist.m3u8"
''', 'https://cdn.example/master.m3u8'),
      [
        'https://cdn.example/high/playlist.m3u8',
        'https://cdn.example/low/playlist.m3u8',
        'https://cdn.example/audio/playlist.m3u8',
      ],
    );
  });

  test('omits a master-wide HLS format for mixed rendition containers', () {
    expect(
      TvCastMediaPreparer.commonHlsSegmentFormatForBodies([
        '#EXTM3U\n#EXTINF:6,\nsegment.ts',
        '#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:6,\nsegment.m4s',
      ]),
      isNull,
    );
    expect(
      TvCastMediaPreparer.commonHlsSegmentFormatForBodies([
        '#EXTM3U\n#EXTINF:6,\nsegment-a.ts',
        '#EXTM3U\n#EXTINF:6,\nsegment-b.jpg',
      ]),
      'ts',
    );
    expect(
      TvCastMediaPreparer.commonHlsSegmentFormatForBodies([
        '#EXTM3U\n#EXTINF:6,\nvideo.ts',
        '#EXTM3U\n#EXTINF:6,\naudio.aac',
      ]),
      isNull,
    );
  });

  test('omits an HLS format query hint when the container is unknown', () {
    final hinted = TvCastMediaPreparer.withHlsHints(
      'http://192.0.2.1/s/id/playlist.m3u8?token=x',
      TvCastMediaPreparer.hlsContentType,
      format: null,
      streamType: 'buffered',
    );
    final uri = Uri.parse(hinted);
    expect(uri.queryParameters['token'], 'x');
    expect(uri.queryParameters['pb_hls_stream'], 'buffered');
    expect(uri.queryParameters.containsKey('pb_hls_format'), isFalse);
  });
}
