import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// Resolves an HLS **master** playlist down to a single **variant**
/// media-playlist URL before it reaches mpv.
///
/// Why this exists: FFmpeg/libmpv's generic HLS demuxer, handed a master that
/// advertises many renditions (e.g. beeg's avc + av1 + hevc variants, all
/// flagged DEFAULT and mixing TS + fMP4), opens and demuxes *every* rendition
/// simultaneously. When the CDN caps concurrent connections — beeg's segment
/// URLs carry `limit=3` — that flood of parallel fetches triggers constant
/// "keepalive request failed … retrying" resets, which both stalls startup and
/// truncates segments into "PES packet size mismatch" / "Error decoding audio".
/// ExoPlayer never hits this because it selects one variant and fetches one
/// segment stream at a time. This helper replicates that selection so desktop
/// mpv only ever plays a single rendition.
///
/// Preference order: the highest-resolution **H.264 (avc1)** variant. H.264 is
/// picked over AV1/HEVC deliberately — it decodes on hardware everywhere (so no
/// slow software AV1 path) and avoids the fragile fMP4 renditions. Falls back to
/// the highest-bandwidth variant if no avc1 rendition is advertised.
///
/// Returns the resolved variant URL, or the original [url] unchanged whenever it
/// isn't an HLS master (already a media playlist, not m3u8, fetch/parse failed).
/// The contract is "never make things worse": any failure yields [url] so mpv
/// gets exactly what it would have gotten before.
Future<String> resolveHlsMaster(
  String url, {
  Map<String, String>? headers,
  Duration timeout = const Duration(seconds: 8),
}) async {
  // Cheap gate: only bother for things that look like HLS.
  final lower = url.toLowerCase();
  if (!lower.contains('.m3u8') && !lower.contains('mpegurl')) return url;

  final Uri uri;
  try {
    uri = Uri.parse(url);
  } catch (_) {
    return url;
  }

  final client = HttpClient()
    ..connectionTimeout = timeout
    ..userAgent = null;
  try {
    final body = await _fetch(client, uri, headers, timeout);
    if (body == null) return url;
    if (!body.contains('#EXTM3U')) return url; // not an HLS playlist at all

    final variants = _parseMaster(body, uri);
    if (variants.isEmpty)
      return url; // media playlist, or no variants → leave it

    final chosen = _pick(variants);
    debugPrint(
      '[hls] master → variant: ${chosen.codecsShort} ${chosen.height}p '
      '(${(chosen.bandwidth / 1000).round()} kbps)',
    );
    return chosen.url.toString();
  } catch (e) {
    debugPrint('[hls] master resolution failed, using original url: $e');
    return url;
  } finally {
    client.close(force: true);
  }
}

Future<String?> _fetch(
  HttpClient client,
  Uri uri,
  Map<String, String>? headers,
  Duration timeout,
) async {
  final req = await client.getUrl(uri);
  headers?.forEach((k, v) => req.headers.set(k, v));
  final resp = await req.close().timeout(timeout);
  if (resp.statusCode < 200 || resp.statusCode >= 300) return null;
  return resp.transform(utf8.decoder).join().timeout(timeout);
}

class _Variant {
  _Variant({
    required this.url,
    required this.bandwidth,
    required this.height,
    required this.codecs,
  });

  final Uri url;
  final int bandwidth;
  final int height; // 0 if RESOLUTION absent
  final String codecs; // raw CODECS attribute, may be empty

  bool get isAvc => codecs.toLowerCase().contains('avc1');
  String get codecsShort => isAvc
      ? 'h264'
      : codecs.toLowerCase().contains('av01')
          ? 'av1'
          : codecs.toLowerCase().contains('hev1') ||
                  codecs.toLowerCase().contains('hvc1')
              ? 'hevc'
              : 'other';
}

/// Parses `#EXT-X-STREAM-INF` variant entries out of a master playlist.
/// Returns empty if there are none (i.e. it's a media playlist).
List<_Variant> _parseMaster(String body, Uri base) {
  final lines = const LineSplitter().convert(body);
  final out = <_Variant>[];
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i].trim();
    if (!line.startsWith('#EXT-X-STREAM-INF:')) continue;
    // The URI is the next non-blank, non-comment line.
    String? uriLine;
    for (var j = i + 1; j < lines.length; j++) {
      final l = lines[j].trim();
      if (l.isEmpty || l.startsWith('#')) continue;
      uriLine = l;
      break;
    }
    if (uriLine == null) continue;

    final attrs = line.substring('#EXT-X-STREAM-INF:'.length);
    final bandwidth = int.tryParse(_attr(attrs, 'BANDWIDTH') ?? '') ?? 0;
    final resolution = _attr(attrs, 'RESOLUTION') ?? '';
    final height = int.tryParse(resolution.split('x').last) ?? 0;
    final codecs = _attr(attrs, 'CODECS')?.replaceAll('"', '') ?? '';

    Uri resolved;
    try {
      resolved = base.resolve(uriLine);
    } catch (_) {
      continue;
    }
    out.add(_Variant(
      url: resolved,
      bandwidth: bandwidth,
      height: height,
      codecs: codecs,
    ));
  }
  return out;
}

/// Extracts a comma-separated attribute value, honouring quoted values that may
/// themselves contain commas (e.g. `CODECS="avc1.64002A,mp4a.40.2"`).
String? _attr(String attrs, String key) {
  final idx = attrs.indexOf('$key=');
  if (idx < 0) return null;
  var i = idx + key.length + 1;
  if (i >= attrs.length) return null;
  if (attrs[i] == '"') {
    final end = attrs.indexOf('"', i + 1);
    if (end < 0) return null;
    return attrs.substring(i + 1, end);
  }
  final end = attrs.indexOf(',', i);
  return attrs.substring(i, end < 0 ? attrs.length : end);
}

_Variant _pick(List<_Variant> variants) {
  final avc = variants.where((v) => v.isAvc).toList();
  final pool = avc.isNotEmpty ? avc : variants;
  pool.sort((a, b) {
    // Prefer higher resolution, then higher bandwidth.
    final byHeight = b.height.compareTo(a.height);
    return byHeight != 0 ? byHeight : b.bandwidth.compareTo(a.bandwidth);
  });
  return pool.first;
}
