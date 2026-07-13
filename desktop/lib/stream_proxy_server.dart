import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;

class _RemoteEntry {
  final String url;
  final Map<String, String> headers;
  final String? mime;

  const _RemoteEntry(this.url, this.headers, this.mime);
}

/// LAN reverse proxy for remote (browser-captured) streams cast to a TV.
///
/// Same role as the phone's [LocalProxyServer] remote path: the TV only talks to
/// this desktop over the LAN; we re-request the CDN with the captured headers
/// and rewrite HLS playlists so every variant/segment also goes through us.
///
/// Needed when CDNs return 403 to the TV even with the right Referer/UA —
/// tokens are often bound to the machine that opened the stream (or its TLS
/// client fingerprint), not replayable from the TV's HTTP stack.
class StreamProxyServer {
  HttpServer? _server;
  final Map<String, _RemoteEntry> _entries = {};
  final Random _rng = Random.secure();

  int? get port => _server?.port;
  bool get isRunning => _server != null;

  Future<void> start() async {
    if (_server != null) return;
    _server = await shelf_io.serve(_handle, InternetAddress.anyIPv4, 0);
    debugPrint('[stream-proxy] listening on ${_server!.port}');
  }

  Future<void> stop() async {
    await _server?.close(force: true);
    _server = null;
    _entries.clear();
  }

  /// Register [url] (with replay [headers]) and return the LAN URL for [host].
  String publish({
    required String url,
    required Map<String, String> headers,
    required String host,
    String? mime,
  }) {
    final ext = _guessExt(url, mime);
    return _register(_RemoteEntry(url, Map.unmodifiable(headers), mime), host, ext);
  }

  String _register(_RemoteEntry entry, String host, String ext) {
    final token = _randomToken();
    _entries[token] = entry;
    // Cap growth during long live sessions (each rewrite registers children).
    if (_entries.length > 2000) {
      final keys = _entries.keys.take(_entries.length - 1500).toList();
      for (final k in keys) {
        _entries.remove(k);
      }
    }
    return 'http://$host:${port ?? 0}/p/$token$ext';
  }

  Future<Response> _handle(Request request) async {
    final segs = request.url.pathSegments;
    if (segs.length < 2 || segs[0] != 'p') {
      return Response.notFound('');
    }
    final token = segs[1].split('.').first;
    final entry = _entries[token];
    if (entry == null) return Response.notFound('');

    final method = request.method.toUpperCase();
    final range = request.headers[HttpHeaders.rangeHeader];

    HttpClient? client;
    try {
      client = HttpClient()
        ..connectionTimeout = const Duration(seconds: 20)
        ..idleTimeout = const Duration(seconds: 30)
        ..autoUncompress = true;
      final uri = Uri.parse(entry.url);
      final req = await client.openUrl('GET', uri);
      req.followRedirects = true;
      req.maxRedirects = 5;
      entry.headers.forEach((k, v) {
        if (k.toLowerCase() == 'host') return;
        req.headers.set(k, v);
      });
      if (range != null && range.isNotEmpty) {
        req.headers.set(HttpHeaders.rangeHeader, range);
      }

      final resp = await req.close().timeout(const Duration(seconds: 30));
      final finalUrl = resp.redirects.isNotEmpty
          ? resp.redirects.last.location.toString()
          : entry.url;
      final ctype = resp.headers.contentType?.mimeType;
      final isPlaylist = _isPlaylist(entry.mime, ctype, finalUrl);

      if (isPlaylist || method == 'HEAD') {
        final body = await resp.fold<List<int>>(
          <int>[],
          (prev, chunk) => prev..addAll(chunk),
        );
        client.close(force: true);
        client = null;
        if (method == 'HEAD') {
          return Response(
            resp.statusCode,
            headers: _forwardResponseHeaders(resp, entry.mime ?? ctype),
          );
        }
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
          return Response(resp.statusCode, body: 'upstream ${resp.statusCode}');
        }
        final text = utf8.decode(body, allowMalformed: true);
        final host = request.requestedUri.host;
        final rewritten = _rewritePlaylist(text, finalUrl, entry.headers, host);
        final bytes = utf8.encode(rewritten);
        return Response.ok(
          bytes,
          headers: {
            HttpHeaders.contentTypeHeader: 'application/vnd.apple.mpegurl',
            HttpHeaders.contentLengthHeader: '${bytes.length}',
            HttpHeaders.cacheControlHeader: 'no-cache',
          },
        );
      }

      // Stream segment/media bytes; close the client only after the body ends.
      final owned = client;
      client = null;
      final controller = StreamController<List<int>>();
      resp.listen(
        controller.add,
        onError: (Object e, StackTrace st) {
          controller.addError(e, st);
          owned.close(force: true);
        },
        onDone: () {
          unawaited(controller.close());
          owned.close(force: true);
        },
        cancelOnError: true,
      );
      return Response(
        resp.statusCode,
        body: controller.stream,
        headers: _forwardResponseHeaders(resp, entry.mime ?? ctype),
      );
    } catch (e) {
      client?.close(force: true);
      debugPrint('[stream-proxy] upstream failed: $e');
      return Response.internalServerError(body: 'proxy error');
    }
  }

  Map<String, Object> _forwardResponseHeaders(HttpClientResponse resp, String? mime) {
    final headers = <String, Object>{
      HttpHeaders.contentTypeHeader:
          mime ?? resp.headers.contentType?.toString() ?? 'application/octet-stream',
      HttpHeaders.acceptRangesHeader: 'bytes',
    };
    final len = resp.headers.value(HttpHeaders.contentLengthHeader);
    if (len != null) headers[HttpHeaders.contentLengthHeader] = len;
    final cr = resp.headers.value(HttpHeaders.contentRangeHeader);
    if (cr != null) headers[HttpHeaders.contentRangeHeader] = cr;
    return headers;
  }

  bool _isPlaylist(String? mime, String? ctype, String url) {
    final path = url.toLowerCase().split('?').first;
    if (path.endsWith('.m3u8')) return true;
    final m = '${mime ?? ''} ${ctype ?? ''}'.toLowerCase();
    return m.contains('mpegurl') || m.contains('m3u8');
  }

  /// Rewrite every URI in an m3u8 so sub-requests hit this proxy with headers.
  String _rewritePlaylist(
    String body,
    String baseUrl,
    Map<String, String> headers,
    String host,
  ) {
    final uriAttr = RegExp(r'URI="([^"]*)"');
    return body.split('\n').map((raw) {
      final line = raw.replaceAll('\r', '');
      if (line.trim().isEmpty) return line;
      if (line.startsWith('#')) {
        return line.replaceAllMapped(uriAttr, (m) {
          final proxied = _proxify(m.group(1)!, baseUrl, headers, host);
          return 'URI="$proxied"';
        });
      }
      return _proxify(line.trim(), baseUrl, headers, host);
    }).join('\n');
  }

  String _proxify(
    String ref,
    String baseUrl,
    Map<String, String> headers,
    String host,
  ) {
    final abs = _resolve(baseUrl, ref);
    final mime = abs.toLowerCase().split('?').first.endsWith('.m3u8')
        ? 'application/vnd.apple.mpegurl'
        : null;
    return _register(
      _RemoteEntry(abs, headers, mime),
      host,
      _guessExt(abs, mime),
    );
  }

  String _resolve(String base, String ref) {
    try {
      return Uri.parse(base).resolve(ref).toString();
    } catch (_) {
      return ref;
    }
  }

  String _guessExt(String url, String? mime) {
    final path = url.toLowerCase().split('?').first;
    if (path.endsWith('.m3u8') || (mime?.contains('mpegurl') ?? false)) {
      return '.m3u8';
    }
    if (path.endsWith('.ts')) return '.ts';
    if (path.endsWith('.m4s')) return '.m4s';
    if (path.endsWith('.mp4')) return '.mp4';
    if (path.endsWith('.mpd') || (mime?.contains('dash') ?? false)) {
      return '.mpd';
    }
    return '';
  }

  String _randomToken() {
    final bytes = List<int>.generate(16, (_) => _rng.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }
}
