import 'dart:io';

import 'package:flutter/foundation.dart';

import '../stream_proxy_server.dart';
import 'playlist_materializer.dart';

/// Result of preparing media for an external receiver (DLNA / Cast / browser /
/// local external player).
class PreparedTvMedia {
  const PreparedTvMedia({
    required this.url,
    this.contentType,
    this.strategy = 'direct',
  });

  /// URL the receiver should load (often a LAN stream-proxy URL).
  final String url;

  /// Hint for browser/native players.
  final String? contentType;

  /// Debug label.
  final String strategy;
}

/// Builds receiver-safe media URLs for TVs, the browser receiver, and
/// "Open in external player" (mpv / VLC / QuickTime).
///
/// Extension demuxed LL-HLS arrives as `https` session video (+ optional
/// `playlistBody` / `audioUrl`). Local Desktop plays that with headers +
/// `audio-add`. External players only accept a single HTTP URL, so we:
///
/// 1. Prefer rewriting the full synthetic multivariant (all quality rungs +
///    demuxed audio) so external players keep audio and can pick high bitrate.
/// 2. Else register video + audio and host a single-variant demuxed master.
/// 3. Else proxy a single media URL.
///
/// Never hand out the exclusive token bootstrap master, `data:`, or bare
/// video-only session playlists when companion audio is known.
class TvCastMediaPreparer {
  TvCastMediaPreparer._();

  static const hlsContentType = 'application/vnd.apple.mpegurl';

  /// Prepare [url] for an external consumer on [lanHost] (use `127.0.0.1` for
  /// local external players, or the desktop LAN IP for TVs).
  static Future<PreparedTvMedia> prepare({
    required String url,
    required String lanHost,
    Map<String, String>? headers,
    String? playlistBody,
    String? audioUrl,
    StreamProxyServer? proxy,
  }) async {
    final p = proxy ?? StreamProxyServer.instance;
    if (!p.isRunning) {
      await p.start();
    }

    final hdrs = headers ?? const <String, String>{};
    final body = playlistBody?.trim();
    final hasSyntheticBody = body != null && body.startsWith('#EXTM3U');

    // Best path for demuxed LL-HLS: rewrite full synthetic master so external
    // players get every quality + separate audio (not video-only / low rung).
    if (body != null &&
        body.startsWith('#EXTM3U') &&
        body.contains('#EXT-X-STREAM-INF') &&
        body.contains('#EXT-X-MEDIA:TYPE=AUDIO')) {
      final rewritten = await _prepareFromSyntheticBody(
        proxy: p,
        lanHost: lanHost,
        body: body,
        headers: hdrs,
      );
      if (rewritten != null) return rewritten;
    }

    final plan = await PlaylistMaterializer.resolveOpen(
      url: url,
      playlistBody: playlistBody,
      audioUrl: audioUrl,
    );

    final companion = plan.companionAudioUrl?.trim();
    final isDemuxed = companion != null && companion.isNotEmpty;

    if (isDemuxed && _isHttpUrl(plan.openUrl)) {
      return _prepareDemuxedMaster(
        proxy: p,
        lanHost: lanHost,
        videoUrl: plan.openUrl,
        audioUrl: companion,
        headers: hdrs,
      );
    }

    if (_isHttpUrl(plan.openUrl)) {
      final reg = await p.registerRemote(plan.openUrl, hdrs, host: lanHost);
      debugPrint(
        '[tv-cast] proxied single media → ${reg.url} (${plan.strategy})',
      );
      return PreparedTvMedia(
        url: reg.url,
        contentType: _guessContentType(plan.openUrl, hasSyntheticBody),
        strategy: 'proxy_single',
      );
    }

    return PreparedTvMedia(
      url: url,
      contentType: _guessContentType(url, hasSyntheticBody),
      strategy: 'direct',
    );
  }

  /// Rewrite absolute CDN child URLs in a synthetic master through the proxy.
  static Future<PreparedTvMedia?> _prepareFromSyntheticBody({
    required StreamProxyServer proxy,
    required String lanHost,
    required String body,
    required Map<String, String> headers,
  }) async {
    final children = extractHttpChildUrls(body);
    if (children.isEmpty) return null;

    final map = <String, String>{};
    for (final child in children) {
      final reg = await proxy.registerRemote(child, headers, host: lanHost);
      map[child] = reg.url;
    }

    final rewritten = rewritePlaylistUrls(body, map);
    final file = await _writeTempMaster(rewritten);
    final masterReg = await proxy.registerFile(
      file.path,
      host: lanHost,
      contentType: hlsContentType,
    );

    debugPrint(
      '[tv-cast] synthetic multivariant via proxy '
      '(${children.length} children, master=${masterReg.id})',
    );

    return PreparedTvMedia(
      url: masterReg.url,
      contentType: hlsContentType,
      strategy: 'proxy_synthetic_multivariant',
    );
  }

  static Future<PreparedTvMedia> _prepareDemuxedMaster({
    required StreamProxyServer proxy,
    required String lanHost,
    required String videoUrl,
    required String audioUrl,
    required Map<String, String> headers,
  }) async {
    final videoReg =
        await proxy.registerRemote(videoUrl, headers, host: lanHost);
    final audioReg =
        await proxy.registerRemote(audioUrl, headers, host: lanHost);

    final masterBody = buildProxiedDemuxedMaster(
      videoProxyUrl: videoReg.url,
      audioProxyUrl: audioReg.url,
    );
    final file = await _writeTempMaster(masterBody);
    final masterReg = await proxy.registerFile(
      file.path,
      host: lanHost,
      contentType: hlsContentType,
    );

    debugPrint(
      '[tv-cast] demuxed master via proxy '
      '(video=${videoReg.id}, audio=${audioReg.id}, master=${masterReg.id})',
    );

    return PreparedTvMedia(
      url: masterReg.url,
      contentType: hlsContentType,
      strategy: 'proxy_demuxed_master',
    );
  }

  /// Single-variant multivariant pointing at already-proxied media playlists.
  @visibleForTesting
  static String buildProxiedDemuxedMaster({
    required String videoProxyUrl,
    required String audioProxyUrl,
  }) {
    return '''
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="$audioProxyUrl"
#EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.42E01E,mp4a.40.2",AUDIO="audio"
$videoProxyUrl
''';
  }

  /// Collect absolute http(s) URIs from STREAM-INF lines and URI="..." attrs.
  @visibleForTesting
  static List<String> extractHttpChildUrls(String body) {
    final found = <String>{};
    final uriAttr = RegExp(r'URI\s*=\s*"([^"]+)"', caseSensitive: false);
    for (final raw in body.replaceAll('\r\n', '\n').split('\n')) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      if (line.startsWith('#')) {
        for (final m in uriAttr.allMatches(line)) {
          final u = m.group(1);
          if (u != null && _isHttpUrl(u)) found.add(u);
        }
        continue;
      }
      if (_isHttpUrl(line)) found.add(line);
    }
    return found.toList();
  }

  /// Replace original CDN URLs with proxy URLs (longest first).
  @visibleForTesting
  static String rewritePlaylistUrls(
    String body,
    Map<String, String> originalToProxy,
  ) {
    var out = body;
    final keys = originalToProxy.keys.toList()
      ..sort((a, b) => b.length.compareTo(a.length));
    for (final original in keys) {
      final proxy = originalToProxy[original]!;
      out = out.replaceAll(original, proxy);
    }
    return out;
  }

  static Future<File> _writeTempMaster(String body) async {
    final file = File(
      '${Directory.systemTemp.path}/playbridge_tv_master_'
      '${DateTime.now().microsecondsSinceEpoch}.m3u8',
    );
    await file.writeAsString('${body.trim()}\n', flush: true);
    return file;
  }

  static bool _isHttpUrl(String url) =>
      url.startsWith('https://') || url.startsWith('http://');

  static String? _guessContentType(String url, bool hlsHint) {
    final lower = url.toLowerCase();
    if (hlsHint || lower.contains('.m3u8') || lower.contains('mpegurl')) {
      return hlsContentType;
    }
    if (lower.contains('.mpd')) return 'application/dash+xml';
    return null;
  }
}
