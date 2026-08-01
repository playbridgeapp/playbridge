import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;

import 'ffmpeg_avio_client.dart';
import 'hls_playlist_rewriter.dart';

class ProxySession {
  final String id;
  final String originalUrl;
  final Map<String, String> headers;
  final DateTime createdAt;
  DateTime lastAccessedAt;

  ProxySession({
    required this.id,
    required this.originalUrl,
    required this.headers,
  })  : createdAt = DateTime.now(),
        lastAccessedAt = DateTime.now();

  void touch() {
    lastAccessedAt = DateTime.now();
  }
}

class StreamProxyServer {
  static const defaultDockerComposePassword = 'CHANGEME';

  final String password;
  HttpServer? _server;
  final Map<String, ProxySession> _sessions = {};
  final Map<String, _EpgCacheEntry> _epgCache = {};
  Timer? _cleanupTimer;
  final _rng = Random.secure();

  StreamProxyServer({required String password})
      : password = _validatePassword(password);

  static String _validatePassword(String password) {
    if (password.trim().isEmpty) {
      throw ArgumentError(
          'A non-empty API password is required. Provide one with '
          '--password <password> or set PB_PROXY_PASSWORD=<password> in the '
          'environment.');
    }
    if (password.trim() == defaultDockerComposePassword) {
      throw ArgumentError(
          'The default Docker Compose password is not allowed; set a unique password');
    }
    return password;
  }

  int get port => _server?.port ?? 0;
  String get host => _server?.address.host ?? '';

  Future<void> start({String host = '127.0.0.1', int port = 0}) async {
    if (_server != null) return;

    final handler = Pipeline()
        .addMiddleware(logRequests())
        .addMiddleware(_cors())
        .addMiddleware(_authorize())
        .addHandler(_handle);

    _server = await shelf_io.serve(handler, host, port);
    stdout.writeln('[stream-proxy] Listening on $host:${_server!.port}');
    stdout.writeln('[stream-proxy] Authentication active token: "<Redacted>"');

    _startSessionCleanupTimer();
  }

  Middleware _cors() {
    const corsHeaders = <String, String>{
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Range, Authorization',
      'Access-Control-Expose-Headers':
          'Content-Length, Content-Range, Accept-Ranges',
    };

    return (innerHandler) {
      return (request) async {
        // Handle preflight
        if (request.method == 'OPTIONS') {
          return Response.ok('', headers: corsHeaders);
        }

        final response = await innerHandler(request);
        return response.change(headers: corsHeaders);
      };
    };
  }

  Middleware _authorize() {
    return (innerHandler) {
      return (request) async {
        final path = request.url.path;
        if (path == 'health' || path == 'ping') {
          return innerHandler(request);
        }

        final token = request.url.queryParameters['token'] ??
            request.headers['Authorization']?.replaceAll('Bearer ', '').trim();

        if (token == null || !_constantTimeEquals(utf8.encode(token), utf8.encode(password))) {
          return Response.forbidden(
              'Unauthorized: Invalid or missing API password');
        }

        return innerHandler(request);
      };
    };
  }

  Future<void> stop() async {
    _cleanupTimer?.cancel();
    _cleanupTimer = null;
    await _server?.close(force: true);
    _server = null;
    _sessions.clear();
    _epgCache.clear();
    stdout.writeln('[stream-proxy] Stopped proxy server');
  }

  /// Registers a streaming session for an upstream URL with its custom headers.
  /// Returns the local proxy URL to load in the player.
  String registerSession(String originalUrl, Map<String, String> headers) {
    final id = _randomId();
    _sessions[id] = ProxySession(
      id: id,
      originalUrl: originalUrl,
      headers: headers,
    );
    stdout.writeln(
        '[stream-proxy] Registered session $id for host: ${Uri.parse(originalUrl).host}');
    final authParam = '?token=$password';
    return 'http://127.0.0.1:$port/s/$id/manifest.m3u8$authParam';
  }

  FutureOr<Response> _handle(Request request) async {
    final pathSegments = request.url.pathSegments;

    // Route: GET /health or /ping
    if (pathSegments.length == 1 &&
        (pathSegments[0] == 'health' || pathSegments[0] == 'ping')) {
      return Response.ok('OK');
    }

    // Route: POST /register
    if (request.method == 'POST' &&
        pathSegments.length == 1 &&
        pathSegments[0] == 'register') {
      try {
        final bodyStr = await request.readAsString();
        final body = jsonDecode(bodyStr) as Map<String, dynamic>;
        final url = body['url'] as String;
        final headersMap =
            (body['headers'] as Map? ?? {}).cast<String, String>();

        final proxyUrl = registerSession(url, headersMap);
        return Response.ok(
          jsonEncode({'proxy_url': proxyUrl}),
          headers: {HttpHeaders.contentTypeHeader: 'application/json'},
        );
      } catch (e) {
        return Response.badRequest(body: 'Invalid registration: $e');
      }
    }

    // Route: GET /epg?uri=...
    if (pathSegments.length == 1 && pathSegments[0] == 'epg') {
      final uriStr = request.url.queryParameters['uri'];
      if (uriStr == null || uriStr.isEmpty) {
        return Response.badRequest(body: 'Missing "uri" parameter for EPG');
      }

      final cached = _epgCache[uriStr];
      if (cached != null && !cached.isExpired(const Duration(hours: 4))) {
        return Response.ok(
          cached.bytes,
          headers: {
            HttpHeaders.contentTypeHeader: 'application/xml',
            HttpHeaders.cacheControlHeader: 'public, max-age=14400',
          },
        );
      }

      try {
        final bytes = await _fetchUrlBytes(uriStr, {});
        _epgCache[uriStr] = _EpgCacheEntry(bytes);
        return Response.ok(
          bytes,
          headers: {
            HttpHeaders.contentTypeHeader: 'application/xml',
            HttpHeaders.cacheControlHeader: 'public, max-age=14400',
          },
        );
      } catch (e) {
        return Response.internalServerError(body: 'Failed to fetch EPG: $e');
      }
    }

    // Route: /s/<session_id>/<filename>
    if (pathSegments.length < 3 || pathSegments[0] != 's') {
      return Response.notFound('Not Found');
    }

    final sessionId = pathSegments[1];
    final String targetUrl;
    final Map<String, String> sessionHeaders;
    String? statelessHeadersB64;

    if (sessionId == 'play') {
      // Path format: /s/play/<uri_b64>/<headers_b64>/<filename>
      if (pathSegments.length < 5) {
        return Response.badRequest(
            body:
                'Invalid stateless path structure. Expected /s/play/<uri_b64>/<headers_b64>/<filename>');
      }
      final uriParam = pathSegments[2];
      final String baseSpec;
      try {
        baseSpec = utf8.decode(_b64UrlDecode(uriParam));
      } catch (e) {
        return Response.badRequest(body: 'Invalid base64 uri segment: $e');
      }

      // Resolve relative path segments from index 4 onwards against the base URL
      targetUrl =
          _resolveTargetUrl(baseSpec, pathSegments.sublist(4), request.url);

      final hB64 = pathSegments[3];
      statelessHeadersB64 = hB64;
      if (hB64 != 'empty') {
        try {
          final decoded = utf8.decode(_b64UrlDecode(hB64));
          sessionHeaders = (jsonDecode(decoded) as Map).cast<String, String>();
        } catch (e) {
          return Response.badRequest(
              body: 'Invalid base64 headers segment: $e');
        }
      } else {
        sessionHeaders = {};
      }
    } else {
      final session = _sessions[sessionId];
      if (session == null) {
        return Response.forbidden('Session expired or invalid');
      }
      session.touch();

      final queryUri = request.url.queryParameters['uri'];
      if (queryUri != null && queryUri.isNotEmpty) {
        targetUrl = queryUri;
      } else if (pathSegments.length == 3 &&
          pathSegments[2] == 'manifest.m3u8') {
        targetUrl = session.originalUrl;
      } else {
        // Resolve relative path segments from index 2 onwards against the original session URL
        targetUrl = _resolveTargetUrl(
            session.originalUrl, pathSegments.sublist(2), request.url);
      }
      sessionHeaders = session.headers;
    }

    final targetUri = Uri.tryParse(targetUrl);
    if (targetUri == null || targetUri.host.isEmpty) {
      return Response.internalServerError(body: 'Invalid upstream URL');
    }

    final forwardHeaders = _filterUpstreamHeaders(
        sessionHeaders, request.headers, targetUrl, sessionId);

    final isHls = targetUrl.toLowerCase().contains('.m3u8') ||
        request.url.path.toLowerCase().contains('.m3u8');

    if (isHls) {
      return _handleHlsPlaylist(
        sessionId,
        targetUrl,
        forwardHeaders,
        statelessHeadersB64,
        request.requestedUri,
      );
    } else {
      return await _handleSegment(targetUrl, forwardHeaders);
    }
  }

  Future<Response> _handleHlsPlaylist(
    String sessionId,
    String targetUrl,
    Map<String, String> headers,
    String? headersB64,
    Uri requestedUri,
  ) async {
    try {
      final bytes = await _fetchUrlBytes(targetUrl, headers);
      final content = utf8.decode(bytes);

      final baseUri = Uri.parse(targetUrl);
      final rewritten =
          HlsPlaylistRewriter.rewrite(content, baseUri, (resolvedTarget) {
        final resolvedUri = Uri.parse(resolvedTarget);
        final filename = resolvedUri.pathSegments.isNotEmpty
            ? resolvedUri.pathSegments.last
            : 'item';

        final String path;
        final Map<String, String> queryParams;
        if (sessionId == 'play') {
          final targetB64 = _b64UrlEncode(utf8.encode(resolvedTarget));
          final cleanHeadersB64 = headersB64 ?? 'empty';
          path = '/s/play/$targetB64/$cleanHeadersB64/$filename';
          queryParams = <String, String>{
            'token': password,
          };
        } else {
          path = '/s/$sessionId/$filename';
          queryParams = <String, String>{
            'uri': resolvedTarget,
            'token': password,
          };
        }
        final queryStr = queryParams.isNotEmpty
            ? '?${Uri(queryParameters: queryParams).query}'
            : '';
        final scheme = requestedUri.scheme;
        final authority = requestedUri.authority;
        return '$scheme://$authority$path$queryStr';
      });

      return Response.ok(
        rewritten,
        headers: {
          HttpHeaders.contentTypeHeader: 'application/vnd.apple.mpegurl',
          HttpHeaders.cacheControlHeader: 'no-cache, no-store, must-revalidate',
        },
      );
    } catch (e) {
      stderr.writeln('[stream-proxy] Error fetching/rewriting playlist: $e');
      return Response.internalServerError(
          body: 'Failed to fetch/rewrite HLS playlist: $e');
    }
  }

  Future<Response> _handleSegment(
    String targetUrl,
    Map<String, String> headers,
  ) async {
    try {
      final upstream = await _connectUpstream(targetUrl, headers);

      final contentType =
          upstream.headers[HttpHeaders.contentTypeHeader]?.toLowerCase() ?? '';
      final isDash = contentType.contains('dash+xml') ||
          targetUrl.toLowerCase().contains('.mpd') ||
          targetUrl.toLowerCase().contains('manifest/dash');

      if (isDash) {
        // Read manifest bytes from the upstream stream and rewrite them
        final bytesBuilder = BytesBuilder();
        await for (final chunk in upstream.stream) {
          bytesBuilder.add(chunk);
        }
        final rawContent = utf8.decode(bytesBuilder.takeBytes());
        final rewritten = _rewriteDashManifest(rawContent, password);
        return Response.ok(
          rewritten,
          headers: {
            HttpHeaders.contentTypeHeader: 'application/dash+xml',
            HttpHeaders.cacheControlHeader:
                'no-cache, no-store, must-revalidate',
          },
        );
      }

      final outHeaders = <String, String>{
        HttpHeaders.contentTypeHeader: _mimeFor(targetUrl),
        HttpHeaders.cacheControlHeader: 'public, max-age=3600',
        ...upstream.headers,
      };

      final controller = StreamController<List<int>>(
        onCancel: () {
          upstream.onCancel();
        },
      );

      upstream.stream.listen(
        (chunk) => controller.add(chunk),
        onError: (e) {
          controller.addError(e);
          controller.close();
        },
        onDone: () {
          controller.close();
        },
        cancelOnError: true,
      );

      return Response(
        upstream.statusCode,
        body: controller.stream,
        headers: outHeaders,
      );
    } catch (e) {
      return Response.internalServerError(body: 'Failed to fetch segment: $e');
    }
  }

  Future<Uint8List> _fetchUrlBytes(
      String url, Map<String, String> headers) async {
    final upstream = await _connectUpstream(url, headers);
    final bytesBuilder = BytesBuilder();
    await for (final chunk in upstream.stream) {
      bytesBuilder.add(chunk);
    }
    return bytesBuilder.takeBytes();
  }

  Future<UpstreamResponse> _connectUpstream(
      String url, Map<String, String> headers) async {
    // 1. Try standard HttpClient first (works for Pornhub CDN, etc. bypasses fingerprint block)
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 4)
      ..badCertificateCallback = (cert, host, port) => true;
    try {
      final req = await client.getUrl(Uri.parse(url));
      headers.forEach((k, v) => req.headers.set(k, v));
      final resp = await req.close();

      if (resp.statusCode >= 200 && resp.statusCode < 300) {
        final outHeaders = <String, String>{};
        resp.headers.forEach((k, values) {
          final lower = k.toLowerCase();
          if (lower == 'content-range' ||
              lower == 'accept-ranges' ||
              lower == 'content-type') {
            outHeaders[k] = values.join(', ');
          }
        });
        final contentEncoding =
            resp.headers.value(HttpHeaders.contentEncodingHeader);
        final isCompressed =
            contentEncoding != null && contentEncoding != 'identity';

        if (resp.contentLength != -1 && !isCompressed) {
          outHeaders[HttpHeaders.contentLengthHeader] =
              resp.contentLength.toString();
        }

        final controller = StreamController<List<int>>();
        resp.listen(
          (chunk) => controller.add(chunk),
          onError: (e) {
            controller.addError(e);
            controller.close();
            client.close();
          },
          onDone: () {
            controller.close();
            client.close();
          },
          cancelOnError: true,
        );

        return UpstreamResponse(
          statusCode: resp.statusCode,
          stream: controller.stream,
          headers: outHeaders,
          onCancel: () {
            client.close(force: true);
          },
        );
      } else {
        client.close(force: true);
      }
    } catch (_) {
      client.close(force: true);
    }

    // 2. Fall back to FFmpeg AvioClient (works for strmd.st, beeg.com CDNs, etc. that block standard TLS)
    final avioStream = AvioIsolateStream(url: url, headers: headers);
    final stream = avioStream.start();

    final outHeaders = <String, String>{
      if (headers.containsKey(HttpHeaders.rangeHeader))
        HttpHeaders.contentRangeHeader: headers[HttpHeaders.rangeHeader]!,
    };

    final status = headers.containsKey(HttpHeaders.rangeHeader)
        ? HttpStatus.partialContent
        : HttpStatus.ok;

    return UpstreamResponse(
      statusCode: status,
      stream: stream,
      headers: outHeaders,
      onCancel: () {
        // Handled automatically by AvioIsolateStream's built-in StreamController onCancel which kills the isolate
      },
    );
  }

  Map<String, String> _filterUpstreamHeaders(
    Map<String, String> sessionHeaders,
    Map<String, String> incomingRequestHeaders,
    String targetUrl,
    String sessionId,
  ) {
    final out = <String, String>{};

    sessionHeaders.forEach((k, v) {
      final lowerKey = k.toLowerCase();
      if (!_shouldSkipHeader(lowerKey)) {
        out[k] = v;
      }
    });

    // Forward Range headers from the player for seeking support.
    // For stateless 'play' routes (top-level MP4/media proxy), always forward.
    // For stateful HLS sessions, only forward Range on non-segment URLs
    // (.ts/.m4s are HLS segments that don't need Range).
    final lowerUrl = targetUrl.toLowerCase();
    final isHlsSegment = lowerUrl.contains('.ts') || lowerUrl.contains('.m4s');

    final shouldForwardRange = sessionId == 'play' || !isHlsSegment;

    if (shouldForwardRange) {
      final incomingRange = incomingRequestHeaders[HttpHeaders.rangeHeader];
      if (incomingRange != null) {
        out[HttpHeaders.rangeHeader] = incomingRange;
      }
    }

    return out;
  }

  bool _shouldSkipHeader(String lowerKey) {
    const skip = {
      'host',
      'connection',
      'content-length',
      'accept-encoding',
      'range',
    };
    return skip.contains(lowerKey) || lowerKey.startsWith(':');
  }

  void _startSessionCleanupTimer() {
    _cleanupTimer?.cancel();
    _cleanupTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      final now = DateTime.now();
      _sessions.removeWhere((id, session) {
        final inactiveDuration = now.difference(session.lastAccessedAt);
        final age = now.difference(session.createdAt);
        final shouldExpire = inactiveDuration > const Duration(minutes: 10) ||
            age > const Duration(hours: 2);
        if (shouldExpire) {
          stdout.writeln(
              '[stream-proxy] Expired session $id (inactive: ${inactiveDuration.inMinutes}m, age: ${age.inMinutes}m)');
        }
        return shouldExpire;
      });
    });
  }

  String _mimeFor(String path) {
    final lower = path.toLowerCase();
    if (lower.contains('.m3u8')) return 'application/vnd.apple.mpegurl';
    if (lower.contains('.mpd')) return 'application/dash+xml';
    if (lower.contains('.m4s')) return 'video/iso.segment';
    if (lower.contains('.ts')) return 'video/mp2t';
    if (lower.contains('.mp4')) return 'video/mp4';
    if (lower.contains('.key')) return 'application/octet-stream';
    return 'application/octet-stream';
  }

  String _randomId() {
    final bytes = List<int>.generate(16, (_) => _rng.nextInt(256));
    return base64Url.encode(bytes).replaceAll('=', '');
  }
}

class UpstreamResponse {
  final int statusCode;
  final Stream<List<int>> stream;
  final Map<String, String> headers;
  final void Function() onCancel;

  UpstreamResponse({
    required this.statusCode,
    required this.stream,
    required this.headers,
    required this.onCancel,
  });
}

class _EpgCacheEntry {
  final Uint8List bytes;
  final DateTime cachedAt;

  _EpgCacheEntry(this.bytes) : cachedAt = DateTime.now();

  bool isExpired(Duration ttl) {
    return DateTime.now().difference(cachedAt) > ttl;
  }
}

/// Decode base64url with or without padding.
List<int> _b64UrlDecode(String input) {
  // Re-add padding if stripped
  final remainder = input.length % 4;
  if (remainder == 2) {
    input = '$input==';
  } else if (remainder == 3) {
    input = '$input=';
  }
  return base64Url.decode(input);
}

/// Encode to base64url without padding (safe for URL path segments).
String _b64UrlEncode(List<int> bytes) {
  return base64Url.encode(bytes).replaceAll('=', '');
}

/// Resolves a list of relative URL path segments against a base URL specification.
/// Merges query parameters from the base URL and the incoming request.
String _resolveTargetUrl(
    String baseSpec, List<String> relativeSegments, Uri requestUri) {
  final baseUri = Uri.parse(baseSpec);
  if (relativeSegments.isEmpty) {
    return baseSpec;
  }

  final Uri resolvedUri;
  if (relativeSegments.first == '_root_') {
    // Resolve relative to the origin root of baseSpec
    final originRoot = Uri(
        scheme: baseUri.scheme,
        userInfo: baseUri.userInfo,
        host: baseUri.host,
        port: baseUri.port);
    final relativePath = relativeSegments.sublist(1).join('/');
    resolvedUri = originRoot.resolve(relativePath);
  } else {
    // Reconstruct the relative path
    final relativePath = relativeSegments.join('/');
    resolvedUri = baseUri.resolve(relativePath);
  }

  // Merge query parameters:
  // 1. Keep base URL query parameters (important for CDN authentication signatures/tokens)
  // 2. Add query parameters from the resolved target URL itself
  // 3. Add incoming request query parameters (excluding the proxy auth 'token')
  final mergedQuery = <String, String>{}
    ..addAll(baseUri.queryParameters)
    ..addAll(resolvedUri.queryParameters);

  requestUri.queryParameters.forEach((k, v) {
    if (k != 'token') {
      mergedQuery[k] = v;
    }
  });

  if (mergedQuery.isEmpty) {
    return resolvedUri.replace(query: null).toString();
  }

  return resolvedUri.replace(queryParameters: mergedQuery).toString();
}

/// Rewrites a DASH (.mpd) XML manifest to prefix root-relative paths with _root_/.
/// Also appends the auth token to rewritten paths so the player remains authorized.
String _rewriteDashManifest(String content, String? token) {
  String appendToken(String url) {
    if (url.startsWith('//') ||
        url.startsWith('http://') ||
        url.startsWith('https://')) {
      return url;
    }
    final cleanUrl = url.startsWith('/') ? '_root_$url' : url;
    if (token == null || token.isEmpty || cleanUrl.contains('token=')) {
      return cleanUrl;
    }
    final hasQuery = cleanUrl.contains('?');
    final separator = hasQuery ? '&amp;' : '?';
    return '$cleanUrl${separator}token=$token';
  }

  // 1. Rewrite <BaseURL>...</BaseURL>
  content = content
      .replaceAllMapped(RegExp(r'<BaseURL([^>]*)>([^<]+)</BaseURL>'), (match) {
    final attrs = match.group(1)!;
    final url = match.group(2)!.trim();
    return '<BaseURL$attrs>${appendToken(url)}</BaseURL>';
  });

  // 2. Rewrite <Location>...</Location>
  content = content.replaceAllMapped(
      RegExp(r'<Location([^>]*)>([^<]+)</Location>'), (match) {
    final attrs = match.group(1)!;
    final url = match.group(2)!.trim();
    return '<Location$attrs>${appendToken(url)}</Location>';
  });

  // 3. Rewrite attributes (media, initialization, location, baseUrl) in double quotes
  content = content.replaceAllMapped(
      RegExp(r'\b(media|initialization|location|baseUrl)\s*=\s*"([^"]+)"'),
      (match) {
    final attrName = match.group(1)!;
    final url = match.group(2)!;
    return '$attrName="${appendToken(url)}"';
  });

  // 4. Rewrite attributes (media, initialization, location, baseUrl) in single quotes
  content = content.replaceAllMapped(
      RegExp(r"\b(media|initialization|location|baseUrl)\s*=\s*'([^']+)'"),
      (match) {
    final attrName = match.group(1)!;
    final url = match.group(2)!;
    return "$attrName='${appendToken(url)}'";
  });

  return content;
}

bool _constantTimeEquals(List<int> a, List<int> b) {
  if (a.length != b.length) {
    return false;
  }
  int result = 0;
  for (int i = 0; i < a.length; i++) {
    result |= a[i] ^ b[i];
  }
  return result == 0;
}
