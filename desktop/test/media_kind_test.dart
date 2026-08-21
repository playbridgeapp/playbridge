import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/media_kind.dart';

void main() {
  test('explicit media kind wins over weak transport hints', () {
    expect(
      resolveMediaKind(
        declared: 'image',
        url: 'https://example.test/resource',
        contentType: 'application/octet-stream',
      ),
      MediaKind.image,
    );
    expect(
      resolveMediaKind(
        declared: 'audio',
        url: 'https://example.test/live.m3u8',
        contentType: 'application/vnd.apple.mpegurl',
      ),
      MediaKind.audio,
    );
  });

  test('mime and extension inference is item scoped', () {
    expect(
      resolveMediaKind(
          url: 'https://example.test/movie', contentType: 'video/mp4'),
      MediaKind.video,
    );
    expect(
      resolveMediaKind(url: 'https://example.test/song.flac'),
      MediaKind.audio,
    );
    expect(
      resolveMediaKind(url: 'https://example.test/photo.webp?token=x'),
      MediaKind.image,
    );
    expect(
      resolveMediaKind(
          url: 'https://example.test/unknown', contentType: 'series'),
      MediaKind.video,
    );
  });
}
