import 'dart:convert';
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
      final contentType = _guessContentType(plan.openUrl, hasSyntheticBody);
      final evidence = _isHlsContentType(contentType)
          ? await _probeHlsEvidence(
              plan.openUrl,
              hdrs,
              suppliedBody: hasSyntheticBody ? body : null,
            )
          : null;
      final reg = await p.registerRemote(plan.openUrl, hdrs, host: lanHost);
      debugPrint(
        '[tv-cast] proxied single media → ${reg.url} '
        '(source=${plan.strategy})',
      );
      return PreparedTvMedia(
        url: withHlsHints(
          reg.url,
          contentType,
          format: evidence?.format,
          streamType: evidence?.streamType ?? hlsStreamTypeForBody(body),
        ),
        contentType: contentType,
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

    final evidence = await _probeHlsEvidence(
      children.first,
      headers,
      suppliedBody: body,
    );
    return PreparedTvMedia(
      url: withHlsHints(
        masterReg.url,
        hlsContentType,
        format: evidence?.format,
        streamType: evidence?.streamType ?? hlsStreamTypeForBody(body),
      ),
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
    final evidence = await Future.wait([
      _probeHlsEvidence(videoUrl, headers),
      _probeHlsEvidence(audioUrl, headers),
    ]);
    final videoEvidence = evidence[0];
    final audioEvidence = evidence[1];
    final commonFormat = videoEvidence?.format == audioEvidence?.format
        ? videoEvidence?.format
        : null;
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
      url: withHlsHints(
        masterReg.url,
        hlsContentType,
        format: commonFormat,
        streamType: videoEvidence?.streamType ?? 'buffered',
      ),
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

  static bool _isHlsContentType(String? contentType) {
    final lower = contentType?.toLowerCase() ?? '';
    return lower.contains('mpegurl') || lower.contains('m3u8');
  }

  /// Carries the HLS container hint through [PlayPayload] without exposing
  /// origin headers to the receiver. The Rust proxy ignores this query value;
  /// GoogleCastTransport consumes it when building Cast LOAD metadata.
  @visibleForTesting
  static String withHlsHints(
    String url,
    String? contentType, {
    required String? format,
    required String streamType,
  }) {
    if (!_isHlsContentType(contentType)) return url;
    final uri = Uri.tryParse(url);
    if (uri == null) return url;
    return uri.replace(
      queryParameters: {
        ...uri.queryParameters,
        if (format != null) 'pb_hls_format': format,
        'pb_hls_stream': streamType,
      },
    ).toString();
  }

  /// Infers the segment container only from media-playlist URI evidence. A
  /// missing EXT-X-MAP does not prove MPEG-TS: packed AAC/MP3 and WebVTT media
  /// playlists are also map-less. Image extensions are treated as TS because
  /// some supported anime CDNs intentionally disguise transport-stream bytes.
  @visibleForTesting
  static String? hlsSegmentFormatForBody(String? body) {
    final source = body?.replaceAll('\r\n', '\n') ?? '';
    final upper = source.toUpperCase();
    if (!upper.trimLeft().startsWith('#EXTM3U')) return null;
    if (upper.contains('#EXT-X-MAP:')) return 'fmp4';
    final isMediaPlaylist = upper.contains('#EXTINF:') ||
        upper.contains('#EXT-X-TARGETDURATION:') ||
        upper.contains('#EXT-X-MEDIA-SEQUENCE:');
    if (!isMediaPlaylist) return null;

    final references = <String>[];
    final uriAttribute = RegExp(r'(?:^|,)URI="([^"]+)"', caseSensitive: false);
    for (final rawLine in source.split('\n')) {
      final line = rawLine.trim();
      if (line.isEmpty) continue;
      if (!line.startsWith('#')) {
        references.add(line);
        continue;
      }
      final tag = line.toUpperCase();
      if (tag.startsWith('#EXT-X-PART:') ||
          (tag.startsWith('#EXT-X-PRELOAD-HINT:') &&
              tag.contains('TYPE=PART'))) {
        final reference = uriAttribute.firstMatch(line)?.group(1);
        if (reference != null) references.add(reference);
      }
    }
    if (references.isEmpty) return null;

    final formats = <String>{};
    for (final reference in references) {
      final format = _hlsSegmentFormatForReference(reference);
      if (format == null) return null;
      formats.add(format);
    }
    return formats.length == 1 ? formats.single : null;
  }

  static String? _hlsSegmentFormatForReference(String reference) {
    final path = Uri.tryParse(reference)?.path.toLowerCase() ??
        reference.split('?').first.toLowerCase();
    final dot = path.lastIndexOf('.');
    final extension = dot < 0 ? '' : path.substring(dot + 1);
    return switch (extension) {
      'm4s' || 'mp4' || 'cmfv' || 'cmfa' => 'fmp4',
      'ts' || 'm2ts' || 'mts' => 'ts',
      // Known CDN deception: the response body is MPEG-TS despite the suffix.
      'jpg' || 'jpeg' => 'ts',
      _ => null,
    };
  }

  /// Returns every distinct master-playlist variant in descending bandwidth
  /// order. Format metadata is safe only after every returned rendition has
  /// been inspected.
  @visibleForTesting
  static List<String> hlsVariantUrlsForBody(String? body, String baseUrl) {
    final source = body?.replaceAll('\r\n', '\n') ?? '';
    final base = Uri.tryParse(baseUrl);
    if (base == null) return const [];
    String? pendingUri;
    final variants = <MapEntry<int, String>>[];
    var malformedVariant = false;
    void addVariant(String value, int bandwidth) {
      try {
        variants.add(MapEntry(bandwidth, base.resolve(value).toString()));
      } on FormatException {
        malformedVariant = true;
      }
    }

    final lines = source.split('\n');
    for (var index = 0; index < lines.length; index++) {
      final line = lines[index].trim();
      if (line.startsWith('#EXT-X-MEDIA:') ||
          line.startsWith('#EXT-X-I-FRAME-STREAM-INF:')) {
        final attributeUri = RegExp(
          r'(?:^|,)URI="([^"]+)"',
          caseSensitive: false,
        ).firstMatch(line)?.group(1);
        if (attributeUri != null) addVariant(attributeUri, 0);
        continue;
      }
      if (!line.startsWith('#EXT-X-STREAM-INF:')) continue;
      final bandwidth = int.tryParse(
            RegExp(r'(?:^|,)BANDWIDTH=(\d+)', caseSensitive: false)
                    .firstMatch(line.substring('#EXT-X-STREAM-INF:'.length))
                    ?.group(1) ??
                '',
          ) ??
          0;
      pendingUri = null;
      for (var child = index + 1; child < lines.length; child++) {
        final candidate = lines[child].trim();
        if (candidate.isEmpty) continue;
        if (candidate.startsWith('#')) break;
        pendingUri = candidate;
        break;
      }
      if (pendingUri != null) {
        addVariant(pendingUri, bandwidth);
      }
    }
    if (malformedVariant) return const [];
    variants.sort((left, right) => right.key.compareTo(left.key));
    final unique = <String>{};
    for (final variant in variants) {
      unique.add(variant.value);
    }
    return unique.toList(growable: false);
  }

  /// Returns a container only when all supplied media playlists are readable
  /// and agree. Mixed TS/fMP4 masters must not receive master-wide metadata.
  @visibleForTesting
  static String? commonHlsSegmentFormatForBodies(Iterable<String?> bodies) {
    final formats = <String>{};
    var count = 0;
    for (final body in bodies) {
      count++;
      final format = hlsSegmentFormatForBody(body);
      if (format == null) return null;
      formats.add(format);
    }
    return count > 0 && formats.length == 1 ? formats.single : null;
  }

  static Future<_HlsEvidence?> _probeHlsEvidence(
    String url,
    Map<String, String> headers, {
    String? suppliedBody,
  }) async {
    final currentUrl = url;
    var body = suppliedBody;
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 4)
      ..userAgent = null;
    try {
      body ??= await _fetchPlaylist(client, currentUrl, headers);
      if (body == null || !body.trimLeft().startsWith('#EXTM3U')) return null;
      final variants = hlsVariantUrlsForBody(body, currentUrl);
      if (variants.isNotEmpty) {
        // Bound remote work for untrusted manifests. More variants means the
        // format cannot be proven safely, so omit the hint.
        if (variants.length > 16) {
          return const _HlsEvidence(
            format: null,
            streamType: 'buffered',
          );
        }
        final variantBodies = await Future.wait(
          variants.map((variant) => _fetchPlaylist(client, variant, headers)),
        );
        final commonFormat = commonHlsSegmentFormatForBodies(variantBodies);
        final streamTypes = <String>{};
        var allReadable = true;
        for (final variantBody in variantBodies) {
          if (variantBody == null) {
            allReadable = false;
            continue;
          }
          streamTypes.add(hlsStreamTypeForBody(variantBody));
        }
        return _HlsEvidence(
          format: commonFormat,
          streamType: allReadable && streamTypes.length == 1
              ? streamTypes.single
              : 'buffered',
        );
      }
      final format = hlsSegmentFormatForBody(body);
      if (format == null) return null;
      return _HlsEvidence(
        format: format,
        streamType: hlsStreamTypeForBody(body),
      );
    } catch (error) {
      debugPrint(
          '[tv-cast] HLS metadata probe failed; omitting format hint: $error');
      return null;
    } finally {
      client.close(force: true);
    }
  }

  static Future<String?> _fetchPlaylist(
    HttpClient client,
    String url,
    Map<String, String> headers,
  ) async {
    final uri = Uri.tryParse(url);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return null;
    }
    final request =
        await client.getUrl(uri).timeout(const Duration(seconds: 4));
    headers.forEach(request.headers.set);
    final response = await request.close().timeout(const Duration(seconds: 4));
    if (response.statusCode < 200 || response.statusCode >= 300) return null;
    const maximumCharacters = 1024 * 1024;
    final contents = StringBuffer();
    await for (final chunk in response
        .transform(utf8.decoder)
        .timeout(const Duration(seconds: 4))) {
      contents.write(chunk);
      if (contents.length > maximumCharacters) return null;
    }
    return contents.toString();
  }

  /// Treat ambiguous web-video HLS as stored media. Live is used only when the
  /// supplied media playlist has explicit live/event markers and no terminal
  /// ENDLIST; a master playlist alone does not prove that its media is live.
  @visibleForTesting
  static String hlsStreamTypeForBody(String? body) {
    final upper = body?.toUpperCase() ?? '';
    if (upper.contains('#EXT-X-ENDLIST') ||
        upper.contains('#EXT-X-PLAYLIST-TYPE:VOD')) {
      return 'buffered';
    }
    if (upper.contains('#EXT-X-PLAYLIST-TYPE:EVENT') ||
        upper.contains('#EXT-X-SERVER-CONTROL:') ||
        upper.contains('#EXT-X-PART:')) {
      return 'live';
    }
    return 'buffered';
  }
}

final class _HlsEvidence {
  const _HlsEvidence({required this.format, required this.streamType});

  final String? format;
  final String streamType;
}
