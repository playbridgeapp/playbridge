import 'package:flutter_test/flutter_test.dart';
import 'package:playbridge_desktop/engines/playback_request_preparer.dart';
import 'package:playbridge_desktop/pairing_store.dart';
import 'package:playbridge_desktop/player_engine.dart';
import 'package:playbridge_desktop/stream_proxy_server.dart';

void main() {
  setUp(() async {
    PlaybackRequestPreparer.clearFailedHosts();
    // Ensure the proxy server's port is mocked or simulated if needed.
    // If not running a real server during unit tests, we can just ensure we stop/clear it.
    await StreamProxyServer.instance.stop();
  });

  tearDown(() async {
    await StreamProxyServer.instance.stop();
  });

  test('Off mode returns original item unchanged', () async {
    final item = QueueItem(
      url: 'https://example.com/stream.m3u8',
      title: 'Test stream',
      headers: {'User-Agent': 'Mozilla'},
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.off);

    expect(prepared.url, equals(item.url));
    expect(prepared.headers, equals(item.headers));
  });

  test('webpage media remains policy-proxied when proxy mode is off', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;
    final item = QueueItem(
      url: 'https://cdn.example/stream.m3u8',
      title: 'Page stream',
      headers: {'Authorization': 'Bearer token'},
      subtitles: ['https://cdn.example/captions.vtt'],
      enforcePageNetworkPolicy: true,
      allowPrivateNetwork: false,
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.off);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.subtitles?.single, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.headers, isNull);
    expect(prepared.enforcePageNetworkPolicy, isTrue);
    expect(prepared.allowPrivateNetwork, isFalse);
  });

  test('credentialed cross-origin subtitles are registered independently', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;
    final item = QueueItem(
      url: 'https://video.provider.example/stream.m3u8',
      title: 'Page stream',
      headers: {'Authorization': 'Bearer video-token'},
      subtitles: ['https://legacy.other.example/captions.vtt'],
      subtitleResources: const [
        SubtitleRequest(
          url: 'https://subs.provider.example/captions.vtt',
          headers: {'Authorization': 'Bearer subtitle-token'},
          language: 'en',
        ),
      ],
      enforcePageNetworkPolicy: true,
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.off);

    expect(prepared.subtitles, hasLength(2));
    expect(
      prepared.subtitles,
      everyElement(startsWith('http://127.0.0.1:$port/s/')),
    );
    expect(prepared.subtitleResources, isNull);
  });

  test('DASH uses mpv-compatible proxy EDL when proxy mode is off', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;
    final item = QueueItem(
      url: 'https://example.com/companion/api/manifest/dash/id/video',
      title: 'DASH stream',
      headers: {'Origin': 'https://example.com'},
      contentType: 'application/dash+xml',
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.off);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.url, endsWith('/manifest.edl'));
    expect(prepared.headers, isNull);
    expect(prepared.originalUrl, item.url);
  });

  test('Always mode proxies MP4 streams', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;

    final item = QueueItem(
      url: 'https://example.com/movie.mp4',
      title: 'Test movie',
      headers: {'User-Agent': 'Mozilla'},
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.always);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.url, contains('/movie.mp4'));
    expect(prepared.headers, isNull);
  });

  test('Always mode proxies HLS stream', () async {
    // Start proxy to have a bound port
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;

    final item = QueueItem(
      url: 'https://example.com/stream.m3u8',
      title: 'Test stream',
      headers: {'User-Agent': 'Mozilla'},
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.always);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.url, contains('/playlist.m3u8'));
    expect(prepared.headers,
        isNull); // Player headers must be null so they aren't re-forwarded
  });

  test('Auto mode does not proxy if host has not failed', () async {
    final item = QueueItem(
      url: 'https://example.com/stream.m3u8',
      title: 'Test stream',
      headers: {'User-Agent': 'Mozilla'},
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.auto);

    expect(prepared.url, equals(item.url));
  });

  test('Auto mode proxies if host has failed', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;

    final url = 'https://example.com/stream.m3u8';
    final item = QueueItem(
      url: url,
      title: 'Test stream',
      headers: {'User-Agent': 'Mozilla'},
    );

    // Mark host as failed
    PlaybackRequestPreparer.markHostFailed(url);

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.auto);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.headers, isNull);
  });

  test('Auto mode proxies MP4 if host has failed', () async {
    await StreamProxyServer.instance.start();
    final port = StreamProxyServer.instance.port;

    final url = 'https://example.com/movie.mp4';
    PlaybackRequestPreparer.markHostFailed(url);

    final item = QueueItem(
      url: url,
      title: 'Test movie',
      headers: {'User-Agent': 'Mozilla'},
    );

    final prepared =
        await PlaybackRequestPreparer.prepare(item, StreamProxyMode.auto);

    expect(prepared.url, startsWith('http://127.0.0.1:$port/s/'));
    expect(prepared.url, contains('/movie.mp4'));
    expect(prepared.headers, isNull);
  });
}
