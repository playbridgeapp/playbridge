import 'dart:io';

import '../pairing_store.dart';
import '../player_engine.dart';
import '../stream_proxy_server.dart';

class PlaybackRequestPreparer {
  /// Hostnames that failed direct playback and require proxying in Auto mode.
  static final Set<String> _autoProxyHosts = {};

  static void markHostFailed(String url) {
    final uri = Uri.tryParse(url);
    if (uri != null && uri.host.isNotEmpty) {
      _autoProxyHosts.add(uri.host);
      stdout.writeln(
          '[request-preparer] Host ${uri.host} marked as requiring proxying in Auto mode');
    }
  }

  static void clearFailedHosts() {
    _autoProxyHosts.clear();
  }

  /// Prepares a queue item for playback, registering a proxy session if required.
  ///
  /// Modes:
  /// - **off**: Always direct, no proxy.
  /// - **always**: ALL requests go through the proxy (HLS, MP4, anything).
  /// - **auto**: Proxy only hosts that previously failed direct playback.
  static Future<QueueItem> prepare(QueueItem item, StreamProxyMode mode) async {
    final url = item.url;
    final uri = Uri.tryParse(url);
    if (uri == null || uri.host.isEmpty) {
      return item;
    }

    final isDash = _isDash(item);
    if (isDash) {
      final proxy = StreamProxyServer.instance;
      final headers = item.headers ?? const <String, String>{};
      final String manifestUrl;
      final path = uri.path.toLowerCase();
      if (proxy.ownsUrl(url) && path.startsWith('/s/')) {
        manifestUrl = url;
      } else {
        manifestUrl = await proxy.registerSession(url, headers);
      }
      final edlUrl = proxy.mpvDashUrl(manifestUrl);
      stdout.writeln(
          '[request-preparer] Routing DASH through mpv-compatible proxy EDL');
      return _proxiedCopy(item, edlUrl, url, headers);
    }

    if (mode == StreamProxyMode.off) {
      return item;
    }

    // Skip loopback or local server URLs already rewritten.
    if (proxyLocalHost(uri.host)) {
      return item;
    }

    bool shouldProxy = false;
    if (mode == StreamProxyMode.always) {
      shouldProxy = true;
    } else if (mode == StreamProxyMode.auto) {
      shouldProxy = _autoProxyHosts.contains(uri.host);
    }

    if (shouldProxy) {
      final headers = item.headers ?? {};
      final loopbackUrl =
          await StreamProxyServer.instance.registerSession(url, headers);

      stdout.writeln(
          '[request-preparer] Routing stream through proxy: ${uri.host} -> loopback');

      // Return a new QueueItem with the loopback URL and empty headers
      // so the media player doesn't forward browser headers to localhost.
      return _proxiedCopy(item, loopbackUrl, url, headers);
    }

    return item;
  }

  static bool _isDash(QueueItem item) {
    final type = item.contentType?.toLowerCase() ?? '';
    final uri = Uri.tryParse(item.url);
    final path = uri?.path.toLowerCase() ?? item.url.toLowerCase();
    return type.contains('dash') ||
        path.endsWith('.mpd') ||
        path.contains('/manifest/dash') ||
        path.endsWith('/manifest.edl');
  }

  static bool proxyLocalHost(String host) =>
      host == '127.0.0.1' || host == 'localhost';

  static QueueItem _proxiedCopy(
    QueueItem item,
    String proxyUrl,
    String originalUrl,
    Map<String, String> originalHeaders,
  ) =>
      QueueItem(
        url: proxyUrl,
        title: item.title,
        headers: null,
        subtitles: item.subtitles,
        startPositionMs: item.startPositionMs,
        originalUrl: item.originalUrl ?? originalUrl,
        originalHeaders: item.originalHeaders ?? originalHeaders,
        contentType: item.contentType,
        bingeGroup: item.bingeGroup,
        season: item.season,
        episode: item.episode,
        imdbId: item.imdbId,
        backdropUrl: item.backdropUrl,
        posterUrl: item.posterUrl,
        logoUrl: item.logoUrl,
        overview: item.overview,
        year: item.year,
        rating: item.rating,
        runtime: item.runtime,
        episodeTitle: item.episodeTitle,
      );
}
