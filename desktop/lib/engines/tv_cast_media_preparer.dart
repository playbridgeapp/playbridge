import 'dart:io';

import 'package:flutter/foundation.dart';

import '../stream_proxy_server.dart';
import 'playlist_materializer.dart';

/// Result of preparing media for an external receiver (DLNA / Cast / browser).
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

/// Builds receiver-safe media URLs for TVs and the browser receiver.
///
/// Extension demuxed LL-HLS arrives as `https` session video (+ optional
/// `playlistBody` / `audioUrl`). Local Desktop plays that with headers +
/// `audio-add`. External receivers only accept a single HTTP URL, so we:
///
/// 1. Register video (and audio) on the stream proxy with capture headers.
/// 2. Host a small single-variant multivariant master that points at those
///    proxied media playlists.
/// 3. Return the LAN master URL — never `data:`, `file:`, or the exclusive
///    token bootstrap master.
///
/// **Always proxy synthetic/demuxed HLS** for external targets: children need
/// headers, browser receivers need CORS, and TVs need a LAN-reachable host.
class TvCastMediaPreparer {
  TvCastMediaPreparer._();

  static const hlsContentType = 'application/vnd.apple.mpegurl';

  /// Prepare [url] for casting to a linked TV / browser receiver on [lanHost].
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
    final plan = await PlaylistMaterializer.resolveOpen(
      url: url,
      playlistBody: playlistBody,
      audioUrl: audioUrl,
    );

    final companion = plan.companionAudioUrl?.trim();
    final isDemuxed = companion != null && companion.isNotEmpty;
    final hasSyntheticBody = playlistBody != null &&
        playlistBody.trim().startsWith('#EXTM3U');

    // Demuxed exclusive-session HLS: proxy A/V + host synthetic master.
    // resolveOpen already extracts video/audio from playlistBody when needed.
    if (isDemuxed && _isHttpUrl(plan.openUrl)) {
      return _prepareDemuxedMaster(
        proxy: p,
        lanHost: lanHost,
        videoUrl: plan.openUrl,
        audioUrl: companion,
        headers: hdrs,
      );
    }

    // Ordinary stream (or synthetic without separate audio): proxy so headers /
    // LAN reachability work. Multivariant CDN masters are rewritten by the
    // proxy on fetch.
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
