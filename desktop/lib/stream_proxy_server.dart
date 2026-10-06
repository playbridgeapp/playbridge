import 'dart:async';
import 'dart:io';

import 'package:playbridge_cast_core/playbridge_cast_core.dart';

class StreamProxyLease {
  StreamProxyLease(this._release);
  void Function()? _release;
  void close() {
    final release = _release;
    _release = null;
    release?.call();
  }
}

class StreamProxyServer {
  static final StreamProxyServer instance = StreamProxyServer._();

  StreamProxyServer._();

  SenderServices? _services;
  StreamSubscription<Map<String, Object?>>? _eventSubscription;
  int? _port;
  final Set<String> _ownedIds = {};
  final Map<String, String> _filePaths = {};
  final Map<String, int> _leaseReferences = {};
  Timer? _leaseTimer;

  String? _idFor(String value) {
    final uri = Uri.tryParse(value);
    if (uri == null ||
        uri.port != _port ||
        uri.pathSegments.length != 3 ||
        !const {'s', 'media'}.contains(uri.pathSegments.first)) {
      return null;
    }
    final id = uri.pathSegments[1];
    return _ownedIds.contains(id) ? id : null;
  }

  /// Retain whole playback bundles (video/audio/subtitles and local manifests).
  /// Only native registrations created by this host can be renewed.
  Future<StreamProxyLease> retainUrls(Iterable<String> values) async {
    final owner = _services;
    final ids = <String>{};
    for (final value in values) {
      final id = _idFor(value);
      if (id != null) ids.add(id);
      final uri = Uri.tryParse(value);
      final path = _filePaths[id] ??
          (uri?.scheme == 'file'
              ? uri!.toFilePath()
              : value.startsWith('/')
                  ? value
                  : null);
      if (path == null) continue;
      try {
        final file = File(path);
        if (await file.length() > 4 * 1024 * 1024) continue;
        final text = await file.readAsString();
        for (final match
            in RegExp(r'''https?://[^\s"'<>]+''').allMatches(text)) {
          final child = _idFor(match.group(0)!);
          if (child != null) ids.add(child);
        }
      } on Object {
        /* Not every local file is a manifest. Never fetch arbitrary URLs. */
      }
    }
    if (owner == null || owner != _services) {
      return StreamProxyLease(() {});
    }
    ids.removeWhere((id) => !_ownedIds.contains(id));
    if (ids.isEmpty) {
      return StreamProxyLease(() {});
    }
    for (final id in ids) {
      _leaseReferences.update(id, (n) => n + 1, ifAbsent: () => 1);
      unawaited(owner.renew(id).catchError((Object _) => false));
    }
    _leaseTimer ??= Timer.periodic(const Duration(minutes: 1), (_) {
      final active = _services;
      if (active == null) return;
      for (final id in _leaseReferences.keys.toList()) {
        unawaited(active.renew(id).catchError((Object _) => false));
      }
    });
    return StreamProxyLease(() {
      if (owner != _services) return;
      for (final id in ids) {
        final count = _leaseReferences[id];
        if (count == null) continue;
        if (count > 1) {
          _leaseReferences[id] = count - 1;
        } else {
          _leaseReferences.remove(id);
          _ownedIds.remove(id);
          _filePaths.remove(id);
          final active = _services;
          if (active != null) {
            unawaited(active.revoke(id).catchError((Object _) => false));
          }
        }
      }
      if (_leaseReferences.isEmpty) {
        _leaseTimer?.cancel();
        _leaseTimer = null;
      }
    });
  }

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
    _leaseTimer?.cancel();
    _leaseTimer = null;
    _ownedIds.clear();
    _filePaths.clear();
    _leaseReferences.clear();
    await _eventSubscription?.cancel();
    _eventSubscription = null;
    services?.dispose();
  }

  Future<RegisteredMedia> registerRemote(
    String originalUrl,
    Map<String, String> headers, {
    String host = '127.0.0.1',
    String? contentType,
    List<String>? allowedPrivateOrigins,
    bool remoteOrigin = false,
  }) async {
    final registration = await services.registerUrl(
      host: host,
      url: originalUrl,
      headers: headers,
      contentType: contentType,
      allowedPrivateOrigins: allowedPrivateOrigins,
      remoteOrigin: remoteOrigin,
    );
    _ownedIds.add(registration.id);
    return registration;
  }

  /// Compatibility helper for local playback callers.
  Future<String> registerSession(
    String originalUrl,
    Map<String, String> headers, {
    String? contentType,
    List<String>? allowedPrivateOrigins,
    bool remoteOrigin = false,
  }) async =>
      (await registerRemote(
        originalUrl,
        headers,
        contentType: contentType,
        allowedPrivateOrigins: allowedPrivateOrigins,
        remoteOrigin: remoteOrigin,
      ))
          .url;

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
  }) async {
    final registration = await services.registerFile(
        host: host, path: path, contentType: contentType);
    _ownedIds.add(registration.id);
    _filePaths[registration.id] = path;
    return registration;
  }
}
