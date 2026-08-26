import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/hls_master_resolver.dart';

void main() {
  late HttpServer server;
  late String base;
  final routes = <String, String>{};

  setUp(() async {
    routes.clear();
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    base = 'http://127.0.0.1:${server.port}';
    unawaited(server.forEach((request) async {
      final body = routes[request.uri.path];
      if (body == null) {
        request.response.statusCode = HttpStatus.notFound;
      } else {
        request.response.headers.contentType =
            ContentType('application', 'vnd.apple.mpegurl');
        request.response.write(body);
      }
      await request.response.close();
    }));
  });

  tearDown(() => server.close(force: true));

  test('keeps a demuxed master so mpv can expose every audio rendition',
      () async {
    routes['/demuxed.m3u8'] = '#EXTM3U\n'
        '#EXT-X-MEDIA:GROUP-ID="audio",TYPE=AUDIO,NAME="English",DEFAULT=YES,'
        'URI="audio_en.m3u8"\n'
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",DEFAULT=NO,'
        'URI="audio_es.m3u8"\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=3014542,RESOLUTION=1920x1080,'
        'CODECS="avc1.4D4032,mp4a.40.2",AUDIO="audio"\n'
        'video_1080p.m3u8\n';

    expect(
      await resolveHlsMaster('$base/demuxed.m3u8'),
      '$base/demuxed.m3u8',
    );
  });

  test('still selects the best variant from a video-only master', () async {
    routes['/master.m3u8'] = '#EXTM3U\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,'
        'CODECS="avc1.64002A"\n'
        'v1080.m3u8\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,'
        'CODECS="avc1.4D4020"\n'
        'v720.m3u8\n';

    expect(
      await resolveHlsMaster('$base/master.m3u8'),
      '$base/v1080.m3u8',
    );
  });

  test('leaves a media playlist unchanged', () async {
    routes['/media.m3u8'] = '#EXTM3U\n'
        '#EXT-X-TARGETDURATION:10\n'
        '#EXTINF:10.0,\n'
        'segment.ts\n';

    expect(
      await resolveHlsMaster('$base/media.m3u8'),
      '$base/media.m3u8',
    );
  });
}
