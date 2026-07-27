import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// How to open an extension cast that may carry a synthetic multivariant body.
class PlaylistOpenPlan {
  const PlaylistOpenPlan({
    required this.openUrl,
    this.companionAudioUrl,
    this.isLocalPlaylist = false,
    this.strategy = 'direct',
  });

  /// URL passed to media_kit/mpv (https media playlist or file:// master).
  final String openUrl;

  /// Optional demuxed audio media playlist for `audio-add` after open.
  final String? companionAudioUrl;

  /// True when [openUrl] is a local temp `.m3u8` (skip remote master preselect).
  final bool isLocalPlaylist;

  /// Debug label for the chosen strategy.
  final String strategy;
}

/// Turns extension synthetic playlists into something mpv can open.
///
/// media_kit/mpv does **not** accept `data:application/vnd.apple.mpegurl,…`
/// URLs — it treats them as filesystem paths under TMPDIR and fails with
/// "No such file or directory".
///
/// For demuxed LL-HLS (`session=` video + audio), prefer opening the **https
/// video media playlist** with headers and `audio-add` for companion audio.
/// A local multivariant `file://` master does not reliably apply
/// [Media.httpHeaders] to absolute child CDN URLs, which shows as a black
/// "playing" picture.
class PlaylistMaterializer {
  PlaylistMaterializer._();

  static final _tempFiles = <String>[];

  /// Resolve the best open strategy for [url] / [playlistBody] / [audioUrl].
  static Future<PlaylistOpenPlan> resolveOpen({
    required String url,
    String? playlistBody,
    String? audioUrl,
  }) async {
    final body = playlistBody?.trim();
    final audio = audioUrl?.trim();
    final hasAudio = audio != null && audio.isNotEmpty;
    final networkOpen = _isHttpUrl(url);

    // Preferred path for demuxed exclusive-session casts from the extension:
    // open the live video media playlist (headers work) + companion audio.
    if (networkOpen && hasAudio) {
      debugPrint(
        '[playlist-materializer] demuxed network open + audio-add '
        '(skip local multivariant)',
      );
      return PlaylistOpenPlan(
        openUrl: url,
        companionAudioUrl: audio,
        strategy: 'network_video_audio_add',
      );
    }

    if (body != null && body.startsWith('#EXTM3U')) {
      final collapsed = collapseToSingleVariant(body) ?? body;
      final demuxed = extractDemuxedNetworkOpen(collapsed);
      if (demuxed != null) {
        debugPrint(
          '[playlist-materializer] demuxed from body → network video'
          '${demuxed.audioUrl != null ? " + audio-add" : ""}',
        );
        return PlaylistOpenPlan(
          openUrl: demuxed.videoUrl,
          companionAudioUrl: demuxed.audioUrl ?? audio,
          strategy: 'body_extract_network',
        );
      }

      final fileUrl = await _writeTempPlaylist(collapsed);
      return PlaylistOpenPlan(
        openUrl: fileUrl,
        companionAudioUrl: hasAudio ? audio : null,
        isLocalPlaylist: true,
        strategy: 'local_single_variant',
      );
    }

    if (_isHlsDataUrl(url)) {
      final decoded = _decodeDataUrl(url);
      if (decoded != null && decoded.trim().startsWith('#EXTM3U')) {
        final collapsed = collapseToSingleVariant(decoded) ?? decoded;
        final demuxed = extractDemuxedNetworkOpen(collapsed);
        if (demuxed != null) {
          return PlaylistOpenPlan(
            openUrl: demuxed.videoUrl,
            companionAudioUrl: demuxed.audioUrl ?? audio,
            strategy: 'data_extract_network',
          );
        }
        final fileUrl = await _writeTempPlaylist(collapsed);
        return PlaylistOpenPlan(
          openUrl: fileUrl,
          companionAudioUrl: hasAudio ? audio : null,
          isLocalPlaylist: true,
          strategy: 'data_local',
        );
      }
      debugPrint(
        '[playlist-materializer] unreadable data: HLS URL; leaving unchanged',
      );
    }

    return PlaylistOpenPlan(
      openUrl: url,
      companionAudioUrl: hasAudio ? audio : null,
      strategy: 'direct',
    );
  }

  /// Backward-compatible helper used by older call sites/tests.
  static Future<String> resolve({
    required String url,
    String? playlistBody,
  }) async {
    final plan = await resolveOpen(url: url, playlistBody: playlistBody);
    return plan.openUrl;
  }

  /// Collapse a multivariant master to a single STREAM-INF (highest bandwidth)
  /// plus its AUDIO group media lines. Returns null if not a master.
  @visibleForTesting
  static String? collapseToSingleVariant(String body) {
    final text = body.replaceAll('\r\n', '\n').trim();
    if (!text.startsWith('#EXTM3U')) return null;
    if (!text.contains('#EXT-X-STREAM-INF:')) return null;

    final lines = text.split('\n');
    final header = <String>[];
    final audioByGroup = <String, List<String>>{};
    final variants = <_VariantEntry>[];

    String? pendingInf;
    int pendingBw = 0;
    String? pendingAudioGroup;

    for (final raw in lines) {
      final line = raw.trimRight();
      final trimmed = line.trim();
      if (trimmed.isEmpty) continue;

      if (trimmed.startsWith('#EXT-X-MEDIA:TYPE=AUDIO')) {
        final group = RegExp(r'GROUP-ID="([^"]+)"', caseSensitive: false)
            .firstMatch(trimmed)
            ?.group(1);
        if (group != null) {
          audioByGroup.putIfAbsent(group, () => []).add(trimmed);
        }
        continue;
      }

      if (trimmed.startsWith('#EXT-X-STREAM-INF:')) {
        pendingInf = trimmed;
        pendingBw = int.tryParse(
              RegExp(r'BANDWIDTH=(\d+)', caseSensitive: false)
                      .firstMatch(trimmed)
                      ?.group(1) ??
                  '',
            ) ??
            0;
        pendingAudioGroup = RegExp(r'AUDIO="([^"]+)"', caseSensitive: false)
            .firstMatch(trimmed)
            ?.group(1);
        continue;
      }

      if (pendingInf != null && !trimmed.startsWith('#')) {
        variants.add(
          _VariantEntry(
            streamInf: pendingInf,
            uri: trimmed,
            bandwidth: pendingBw,
            audioGroupId: pendingAudioGroup,
          ),
        );
        pendingInf = null;
        pendingBw = 0;
        pendingAudioGroup = null;
        continue;
      }

      // Keep version / independent-segments / other global tags (not media/stream).
      if (trimmed.startsWith('#') &&
          !trimmed.startsWith('#EXT-X-STREAM-INF') &&
          !trimmed.startsWith('#EXT-X-MEDIA') &&
          !trimmed.startsWith('#EXT-X-I-FRAME')) {
        header.add(trimmed);
      }
    }

    if (variants.isEmpty) return null;
    variants.sort((a, b) => b.bandwidth.compareTo(a.bandwidth));
    final best = variants.first;

    final out = <String>[
      if (header.isEmpty) '#EXTM3U',
      ...header,
    ];
    if (!out.any((l) => l.startsWith('#EXTM3U'))) {
      out.insert(0, '#EXTM3U');
    }

    final group = best.audioGroupId;
    if (group != null && audioByGroup.containsKey(group)) {
      out.addAll(audioByGroup[group]!);
    } else if (audioByGroup.isNotEmpty) {
      // Keep first audio group if STREAM-INF didn't declare AUDIO=.
      out.addAll(audioByGroup.values.first);
    }
    out.add(best.streamInf);
    out.add(best.uri);
    return '${out.join('\n')}\n';
  }

  /// Pull absolute https video (+ optional audio) URLs out of a master body so
  /// we can open them as network resources with headers.
  @visibleForTesting
  static ({String videoUrl, String? audioUrl})? extractDemuxedNetworkOpen(
    String body,
  ) {
    final text = body.replaceAll('\r\n', '\n');
    if (!text.contains('#EXT-X-STREAM-INF:')) return null;

    String? bestVideo;
    var bestBw = -1;
    String? audioGroup;
    String? pendingInf;
    var pendingBw = 0;
    String? pendingAudioGroup;
    final audioUris = <String, String>{}; // groupId -> first URI
    String? defaultAudioUri;

    for (final raw in text.split('\n')) {
      final line = raw.trim();
      if (line.startsWith('#EXT-X-MEDIA:TYPE=AUDIO')) {
        final group = RegExp(r'GROUP-ID="([^"]+)"', caseSensitive: false)
            .firstMatch(line)
            ?.group(1);
        final uri = RegExp(r'URI="([^"]+)"', caseSensitive: false)
            .firstMatch(line)
            ?.group(1);
        final isDefault =
            RegExp(r'DEFAULT=YES', caseSensitive: false).hasMatch(line);
        if (group != null && uri != null && _isHttpUrl(uri)) {
          audioUris.putIfAbsent(group, () => uri);
          if (isDefault) defaultAudioUri = uri;
        }
        continue;
      }
      if (line.startsWith('#EXT-X-STREAM-INF:')) {
        pendingInf = line;
        pendingBw = int.tryParse(
              RegExp(r'BANDWIDTH=(\d+)', caseSensitive: false)
                      .firstMatch(line)
                      ?.group(1) ??
                  '',
            ) ??
            0;
        pendingAudioGroup = RegExp(r'AUDIO="([^"]+)"', caseSensitive: false)
            .firstMatch(line)
            ?.group(1);
        continue;
      }
      if (pendingInf != null && line.isNotEmpty && !line.startsWith('#')) {
        if (_isHttpUrl(line) && pendingBw >= bestBw) {
          bestBw = pendingBw;
          bestVideo = line;
          audioGroup = pendingAudioGroup;
        }
        pendingInf = null;
      }
    }

    if (bestVideo == null) return null;
    final audio = (audioGroup != null ? audioUris[audioGroup] : null) ??
        defaultAudioUri ??
        (audioUris.isNotEmpty ? audioUris.values.first : null);
    // Only treat as demuxed network open when we have separate audio (the
    // black-screen failure mode). Muxed single-variant can use local file.
    if (audio == null) return null;
    return (videoUrl: bestVideo, audioUrl: audio);
  }

  static bool _isHttpUrl(String url) =>
      url.startsWith('https://') || url.startsWith('http://');

  static bool _isHlsDataUrl(String url) {
    if (!url.startsWith('data:')) return false;
    final head = url.substring(0, url.length.clamp(0, 96)).toLowerCase();
    return head.contains('mpegurl') || head.contains('m3u8');
  }

  static String? _decodeDataUrl(String url) {
    final comma = url.indexOf(',');
    if (comma < 0) return null;
    final meta = url.substring(5, comma).toLowerCase(); // after "data:"
    final payload = url.substring(comma + 1);
    try {
      if (meta.contains(';base64')) {
        return utf8.decode(base64Decode(payload));
      }
      return Uri.decodeComponent(payload);
    } catch (e) {
      debugPrint('[playlist-materializer] data: decode failed: $e');
      return null;
    }
  }

  static Future<String> _writeTempPlaylist(String body) async {
    final dir = Directory.systemTemp;
    final file = File(
      '${dir.path}/playbridge_synth_${DateTime.now().microsecondsSinceEpoch}.m3u8',
    );
    await file.writeAsString(body, flush: true);
    _tempFiles.add(file.path);
    // Cap retained temp playlists.
    while (_tempFiles.length > 32) {
      final old = _tempFiles.removeAt(0);
      try {
        await File(old).delete();
      } catch (_) {}
    }
    final uri = file.uri.toString(); // file:///…
    debugPrint('[playlist-materializer] wrote synthetic playlist → $uri');
    return uri;
  }
}

class _VariantEntry {
  _VariantEntry({
    required this.streamInf,
    required this.uri,
    required this.bandwidth,
    this.audioGroupId,
  });

  final String streamInf;
  final String uri;
  final int bandwidth;
  final String? audioGroupId;
}
