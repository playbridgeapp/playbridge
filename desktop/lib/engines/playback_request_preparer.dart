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
  static QueueItem prepare(QueueItem item, StreamProxyMode mode) {
    if (mode == StreamProxyMode.off) {
      return item;
    }

    final url = item.url;
    final uri = Uri.tryParse(url);
    if (uri == null || uri.host.isEmpty) {
      return item;
    }

    // Skip loopback or local server URLs already rewritten
    if (uri.host == '127.0.0.1' || uri.host == 'localhost') {
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
          StreamProxyServer.instance.registerSession(url, headers);

      stdout.writeln(
          '[request-preparer] Routing stream through proxy: ${uri.host} -> loopback');

      // Return a new QueueItem with the loopback URL and empty headers
      // so the media player doesn't forward browser headers to localhost.
      return QueueItem(
        url: loopbackUrl,
        title: item.title,
        headers: null, // Managed internally by the proxy server session
        subtitles: item.subtitles,
        startPositionMs: item.startPositionMs,
        originalUrl: item.originalUrl ?? url,
        originalHeaders: item.originalHeaders ?? headers,
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

    return item;
  }
}
