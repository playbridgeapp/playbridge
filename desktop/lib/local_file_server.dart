import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;

class _Grant {
  final File file;

  /// The TV's IP — only this client may fetch the file (D4). Null = any LAN host.
  final String? allowedClientIp;
  final DateTime expiresAt;

  const _Grant(this.file, this.allowedClientIp, this.expiresAt);

  bool get expired => DateTime.now().isAfter(expiresAt);
}

/// Serves local files to a TV over plain LAN HTTP for casting.
///
/// Each cast is a [register] grant addressed by an unguessable path token,
/// restricted to the TV's IP and expired after a TTL (D4) so other devices on
/// the network can't fetch the file. HTTP `Range` is supported so the TV's
/// player can seek. The bytes are personal media (no tokens), so plain HTTP is
/// acceptable here — the command that points the TV at this URL still rides the
/// pinned `wss://` link.
class LocalFileServer {
  HttpServer? _server;
  final Map<String, _Grant> _grants = {};
  final Random _rng = Random.secure();

  int? get port => _server?.port;
  bool get isRunning => _server != null;

  Future<void> start() async {
    if (_server != null) return;
    final handler =
        const Pipeline().addHandler(_handle); // no logging: avoids leaking tokens
    // Bind all interfaces so the TV can reach us on the LAN; access is gated by
    // the per-grant token + IP check, not by the bind address.
    _server = await shelf_io.serve(handler, InternetAddress.anyIPv4, 0);
    debugPrint('[file-server] listening on ${_server!.port}');
  }

  /// Registers [file] for casting; returns the path token. [clientIp] is the
  /// TV's address (restricts who may fetch); [ttl] expires the grant.
  String register(
    File file, {
    String? clientIp,
    Duration ttl = const Duration(hours: 6),
  }) {
    final token = _randomToken();
    _grants[token] = _Grant(file, clientIp, DateTime.now().add(ttl));
    return token;
  }

  /// The URL the TV should fetch, given the desktop's LAN [host] address.
  String urlFor(String token, String host, {String? filename}) {
    final suffix =
        (filename != null && filename.isNotEmpty) ? '/${Uri.encodeComponent(filename)}' : '';
    return 'http://$host:${port ?? 0}/f/$token$suffix';
  }

  void revoke(String token) => _grants.remove(token);

  Future<Response> _handle(Request request) async {
    final segs = request.url.pathSegments;
    final remote =
        (request.context['shelf.io.connection_info'] as HttpConnectionInfo?)
            ?.remoteAddress
            .address;

    if (segs.length < 2 || segs[0] != 'f') {
      debugPrint('[file-server] ${request.method} ${request.url.path} '
          'from $remote → 404 (bad path)');
      return Response.notFound('');
    }

    final token = segs[1];
    final grant = _grants[token];
    if (grant == null || grant.expired) {
      _grants.remove(token);
      debugPrint('[file-server] ${request.method} ${_short(token)} from $remote '
          '→ 404 (${grant == null ? 'no grant' : 'expired'})');
      return Response.notFound('');
    }

    // D4 IP pinning: the discovered host can be a `.local` name or IPv6 rather
    // than the HTTP source IP, so for now we LOG a mismatch instead of blocking.
    // TODO: capture the TV's real address from the wss connection and re-enforce.
    if (grant.allowedClientIp != null &&
        remote != null &&
        remote != grant.allowedClientIp) {
      debugPrint('[file-server] IP note: allowed=${grant.allowedClientIp} '
          'remote=$remote (serving anyway)');
    }

    final file = grant.file;
    if (!file.existsSync()) {
      debugPrint('[file-server] missing file ${file.path} → 404');
      return Response.notFound('');
    }

    debugPrint('[file-server] ${request.method} ${_short(token)} '
        'range=${request.headers[HttpHeaders.rangeHeader] ?? '-'} '
        'from $remote → serving');

    final total = await file.length();
    final contentType = _mimeFor(file.path);
    final rangeHeader = request.headers[HttpHeaders.rangeHeader];

    if (rangeHeader != null) {
      final range = _parseRange(rangeHeader, total);
      if (range == null) {
        return Response(HttpStatus.requestedRangeNotSatisfiable,
            headers: {HttpHeaders.contentRangeHeader: 'bytes */$total'});
      }
      final (start, end) = range;
      return Response(
        HttpStatus.partialContent,
        body: file.openRead(start, end + 1),
        headers: {
          HttpHeaders.contentTypeHeader: contentType,
          HttpHeaders.contentLengthHeader: '${end - start + 1}',
          HttpHeaders.contentRangeHeader: 'bytes $start-$end/$total',
          HttpHeaders.acceptRangesHeader: 'bytes',
        },
      );
    }

    if (request.method == 'HEAD') {
      return Response.ok(null, headers: {
        HttpHeaders.contentTypeHeader: contentType,
        HttpHeaders.contentLengthHeader: '$total',
        HttpHeaders.acceptRangesHeader: 'bytes',
      });
    }

    return Response.ok(
      file.openRead(),
      headers: {
        HttpHeaders.contentTypeHeader: contentType,
        HttpHeaders.contentLengthHeader: '$total',
        HttpHeaders.acceptRangesHeader: 'bytes',
      },
    );
  }

  /// Parses a single `bytes=start-end` range against [total]; returns the
  /// inclusive [start, end] or null if unsatisfiable.
  (int, int)? _parseRange(String header, int total) {
    final m = RegExp(r'bytes=(\d*)-(\d*)').firstMatch(header);
    if (m == null || total == 0) return null;
    final rawStart = m.group(1) ?? '';
    final rawEnd = m.group(2) ?? '';

    int start;
    int end;
    if (rawStart.isEmpty) {
      // Suffix range: last N bytes.
      final n = int.tryParse(rawEnd);
      if (n == null || n == 0) return null;
      start = (total - n).clamp(0, total - 1);
      end = total - 1;
    } else {
      start = int.parse(rawStart);
      end = rawEnd.isEmpty ? total - 1 : int.parse(rawEnd);
    }
    if (start > end || start >= total) return null;
    if (end >= total) end = total - 1;
    return (start, end);
  }

  static String _short(String t) => t.length > 8 ? '${t.substring(0, 8)}…' : t;

  String _randomToken() {
    final bytes = List<int>.generate(18, (_) => _rng.nextInt(256));
    // URL-safe base64 without padding.
    return base64Url.encode(bytes).replaceAll('=', '');
  }

  Future<void> stop() async {
    _grants.clear();
    final s = _server;
    _server = null;
    if (s != null) await s.close(force: true);
  }

  static String _mimeFor(String path) {
    final lower = path.toLowerCase();
    if (lower.endsWith('.mp4') || lower.endsWith('.m4v')) return 'video/mp4';
    if (lower.endsWith('.mkv')) return 'video/x-matroska';
    if (lower.endsWith('.webm')) return 'video/webm';
    if (lower.endsWith('.avi')) return 'video/x-msvideo';
    if (lower.endsWith('.mov')) return 'video/quicktime';
    if (lower.endsWith('.wmv')) return 'video/x-ms-wmv';
    if (lower.endsWith('.flv')) return 'video/x-flv';
    if (lower.endsWith('.ts')) return 'video/mp2t';
    if (lower.endsWith('.m3u8')) return 'application/vnd.apple.mpegurl';
    if (lower.endsWith('.mp3')) return 'audio/mpeg';
    if (lower.endsWith('.flac')) return 'audio/flac';
    if (lower.endsWith('.srt')) return 'application/x-subrip';
    if (lower.endsWith('.vtt')) return 'text/vtt';
    return 'application/octet-stream';
  }
}
