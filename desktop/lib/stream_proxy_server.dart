import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:playbridge_stream_proxy/stream_proxy_server.dart' as proxy;

class StreamProxyServer {
  static final StreamProxyServer instance = StreamProxyServer._();

  StreamProxyServer._();

  proxy.StreamProxyServer? _server;
  int? _port;
  String? _sessionToken;

  int? get port => _port;
  bool get isRunning => _server != null;
  String? get sessionToken => _sessionToken;

  Future<void> start() async {
    if (_server != null) return;

    // 1. Find an available port on loopback
    final socket = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    _port = socket.port;
    await socket.close();

    // 2. Generate a secure random token for the proxy session
    final rng = Random.secure();
    final randomBytes = List<int>.generate(16, (_) => rng.nextInt(256));
    _sessionToken = base64Url.encode(randomBytes).replaceAll('=', '');

    // 3. Start the Shelf server directly in-process
    stdout
        .writeln('[stream-proxy] Starting in-process proxy on port $_port...');
    _server = proxy.StreamProxyServer(password: _sessionToken);

    // Using 127.0.0.1 for local loopback proxying
    await _server!.start(host: '127.0.0.1', port: _port!);
  }

  Future<void> stop() async {
    if (_server == null) return;
    stdout.writeln('[stream-proxy] Stopping in-process proxy...');
    await _server!.stop();
    _server = null;
    _port = null;
    _sessionToken = null;
  }

  /// Registers a new upstream streaming session and returns the stateless loopback URL.
  String registerSession(String originalUrl, Map<String, String> headers) {
    if (_port == null) {
      throw StateError('Proxy server is not running');
    }

    final urlB64 =
        base64Url.encode(utf8.encode(originalUrl)).replaceAll('=', '');

    // Filter headers to only pass non-empty string headers
    final cleanHeaders = <String, String>{};
    headers.forEach((k, v) {
      if (k.isNotEmpty && v.isNotEmpty) {
        cleanHeaders[k] = v;
      }
    });

    final headersB64 = cleanHeaders.isNotEmpty
        ? base64Url
            .encode(utf8.encode(jsonEncode(cleanHeaders)))
            .replaceAll('=', '')
        : 'empty';

    // Derive the filename from the original URL so the proxy can detect
    // media type (e.g. .m3u8 vs .mp4) from the path.
    final origUri = Uri.tryParse(originalUrl);
    final filename = (origUri != null && origUri.pathSegments.isNotEmpty)
        ? origUri.pathSegments.last
        : 'stream';

    final tokenQuery = _sessionToken != null ? '?token=$_sessionToken' : '';
    return 'http://127.0.0.1:$_port/s/play/$urlB64/$headersB64/$filename$tokenQuery';
  }
}
