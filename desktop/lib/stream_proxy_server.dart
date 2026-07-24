import 'dart:async';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';

class StreamProxyServer {
  static final StreamProxyServer instance = StreamProxyServer._();

  StreamProxyServer._();

  SenderServices? _services;
  StreamSubscription<Map<String, Object?>>? _eventSubscription;
  int? _port;

  int? get port => _port;
  bool get isRunning => _services != null;
  SenderServices get services =>
      _services ?? (throw StateError('Rust sender services are not running'));
  Stream<Map<String, Object?>> get events => services.events;

  Future<void> start() async {
    if (_services != null) return;
    final services = SenderServices.start();
    _services = services;
    final started = Completer<void>();
    _eventSubscription = services.events.listen(
      (event) {
        if (event['event'] == 'started') {
          _port = event['proxyPort'] as int?;
          if (!started.isCompleted) started.complete();
        } else if (event['event'] == 'error' &&
            event['operation'] == 'start' &&
            !started.isCompleted) {
          started.completeError(
            StateError(event['message']?.toString() ?? 'Proxy failed to start'),
          );
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        if (!started.isCompleted) started.completeError(error, stackTrace);
      },
    );
    try {
      await started.future.timeout(const Duration(seconds: 10));
    } on Object {
      await stop();
      rethrow;
    }
  }

  Future<void> stop() async {
    final services = _services;
    _services = null;
    _port = null;
    await _eventSubscription?.cancel();
    _eventSubscription = null;
    services?.dispose();
  }

  Future<RegisteredMedia> registerRemote(
    String originalUrl,
    Map<String, String> headers, {
    String host = '127.0.0.1',
  }) =>
      services.registerUrl(host: host, url: originalUrl, headers: headers);

  /// Compatibility helper for local playback callers.
  Future<String> registerSession(
    String originalUrl,
    Map<String, String> headers,
  ) async =>
      (await registerRemote(originalUrl, headers)).url;

  bool ownsUrl(String value) {
    final uri = Uri.tryParse(value);
    final activePort = _port;
    if (uri == null || activePort == null || uri.port != activePort) {
      return false;
    }
    return uri.path.startsWith('/s/') ||
        uri.path.startsWith('/proxy/') ||
        uri.path.startsWith('/media/');
  }

  String urlForHost(String value, String host) =>
      Uri.parse(value).replace(host: host).toString();

  String mpvDashUrl(String value) {
    final uri = Uri.parse(value);
    final segments = [...uri.pathSegments];
    if (segments.isEmpty) return value;
    segments[segments.length - 1] = 'manifest.edl';
    return uri.replace(pathSegments: segments).toString();
  }

  Future<RegisteredMedia> registerFile(
    String path, {
    required String host,
    String? contentType,
  }) =>
      services.registerFile(
        host: host,
        path: path,
        contentType: contentType,
      );
}
