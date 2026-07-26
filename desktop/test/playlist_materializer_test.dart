import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/playlist_materializer.dart';

void main() {
  test('demuxed cast prefers network video + audio-add over local file',
      () async {
    const video =
        'https://cdn.example/chunklist_0_video.m3u8?session=s1';
    const audio =
        'https://cdn.example/chunklist_5_audio.m3u8?session=s1';
    const body = '''
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="A",DEFAULT=YES,URI="$audio"
#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="a"
$video
''';

    final plan = await PlaylistMaterializer.resolveOpen(
      url: video,
      playlistBody: body,
      audioUrl: audio,
    );

    expect(plan.openUrl, video);
    expect(plan.companionAudioUrl, audio);
    expect(plan.isLocalPlaylist, isFalse);
    expect(plan.strategy, 'network_video_audio_add');
    expect(plan.openUrl.startsWith('file:'), isFalse);
  });

  test('body without audioUrl still extracts network demuxed open', () async {
    const video =
        'https://cdn.example/chunklist_3_video.m3u8?session=s1';
    const audio =
        'https://cdn.example/chunklist_5_audio.m3u8?session=s1';
    const body = '''
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="A",DEFAULT=YES,URI="$audio"
#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO="a"
https://cdn.example/chunklist_0_video.m3u8?session=s1
#EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO="a"
$video
''';

    final plan = await PlaylistMaterializer.resolveOpen(
      url: 'https://cdn.example/chunklist_0_video.m3u8?session=s1',
      playlistBody: body,
    );

    expect(plan.openUrl, video); // highest bandwidth
    expect(plan.companionAudioUrl, audio);
    expect(plan.isLocalPlaylist, isFalse);
  });

  test('collapseToSingleVariant keeps highest bandwidth + audio', () {
    const body = '''
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="A",DEFAULT=YES,URI="https://cdn.example/a.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO="a"
https://cdn.example/v0.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,AUDIO="a"
https://cdn.example/v1.m3u8
''';
    final collapsed = PlaylistMaterializer.collapseToSingleVariant(body);
    expect(collapsed, isNotNull);
    expect(collapsed!, contains('v1.m3u8'));
    expect(collapsed, isNot(contains('v0.m3u8')));
    expect(collapsed, contains('TYPE=AUDIO'));
    expect(
      '#EXT-X-STREAM-INF'.allMatches(collapsed).length,
      1,
    );
  });

  test('muxed body without separate audio materializes local file', () async {
    const body = '''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000
https://cdn.example/muxed.m3u8
''';
    final plan = await PlaylistMaterializer.resolveOpen(
      url: 'https://cdn.example/token-master.m3u8?token=x',
      playlistBody: body,
    );

    // No separate audio → may materialize single-variant local, or extract
    // fails so local write. Either way not multi-variant flood.
    expect(plan.openUrl, anyOf(startsWith('file:'), contains('muxed.m3u8')));
  });

  test('data: HLS URL is decoded and written to a temp file when muxed',
      () async {
    const playlist =
        '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://x/v.m3u8\n';
    final dataUrl =
        'data:application/vnd.apple.mpegurl;charset=utf-8,${Uri.encodeComponent(playlist)}';

    final url = await PlaylistMaterializer.resolve(
      url: dataUrl,
      playlistBody: null,
    );

    // Muxed single stream-inf without AUDIO media → local or network extract.
    // extractDemuxed requires audio, so local file.
    expect(url, startsWith('file:'));
    final text = await File.fromUri(Uri.parse(url)).readAsString();
    expect(text, contains('#EXTM3U'));
    expect(text, contains('https://x/v.m3u8'));
  });

  test('base64 data: HLS URL is supported', () async {
    const playlist = '#EXTM3U\n#EXTINF:1,\nseg.ts\n';
    final b64 = base64Encode(utf8.encode(playlist));
    final dataUrl = 'data:application/vnd.apple.mpegurl;base64,$b64';

    final url = await PlaylistMaterializer.resolve(
      url: dataUrl,
      playlistBody: null,
    );

    expect(url, startsWith('file:'));
    expect(
      await File.fromUri(Uri.parse(url)).readAsString(),
      contains('seg.ts'),
    );
  });

  test('plain https URL is returned unchanged without body', () async {
    const remote = 'https://cdn.example/master.m3u8?token=abc';
    final plan = await PlaylistMaterializer.resolveOpen(
      url: remote,
      playlistBody: null,
    );
    expect(plan.openUrl, remote);
    expect(plan.strategy, 'direct');
  });
}
