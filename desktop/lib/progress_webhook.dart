import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:uuid/uuid.dart';

/// Cast-only authorization. Never put this object on QueueItem or in history.
class ProgressWebhook {
  ProgressWebhook._(this.url, this.bearerToken);
  final Uri url;
  final String bearerToken;

  static ProgressWebhook? parse(Object? value) {
    if (value is! Map) return null;
    final rawUrl = value['url'];
    final token = value['bearerToken'];
    if (rawUrl is! String ||
        rawUrl.length > 2048 ||
        token is! String ||
        token.isEmpty ||
        token.length > 4096 ||
        token.contains(RegExp(r'[^\x21-\x7e]'))) {
      return null;
    }
    final uri = Uri.tryParse(rawUrl);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasFragment ||
        uri.hasQuery ||
        uri.host.endsWith('.') ||
        uri.host == 'localhost' ||
        uri.host.endsWith('.localhost') ||
        uri.host.endsWith('.local') ||
        uri.port != 443) {
      return null;
    }
    final literal = InternetAddress.tryParse(uri.host);
    if (literal != null && !isPublicWebhookAddress(literal)) return null;
    return ProgressWebhook._(uri, token);
  }

  @override
  String toString() => 'ProgressWebhook(<redacted>)';
}

/// Conservatively allow global unicast only, including checks for IPv4-mapped IPv6.
bool isPublicWebhookAddress(InternetAddress address) {
  final b = address.rawAddress;
  if (b.length == 4) {
    return !(b[0] == 0 ||
        b[0] == 10 ||
        b[0] == 127 ||
        b[0] >= 224 ||
        (b[0] == 100 && b[1] >= 64 && b[1] <= 127) ||
        (b[0] == 169 && b[1] == 254) ||
        (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
        (b[0] == 192 && (b[1] == 168 || (b[1] == 0) || (b[1] == 2))) ||
        (b[0] == 198 && (b[1] == 18 || b[1] == 19 || b[1] == 51)) ||
        (b[0] == 203 && b[1] == 0 && b[2] == 113));
  }
  if (b.length != 16) return false;
  if (b.take(10).every((v) => v == 0) && b[10] == 255 && b[11] == 255) {
    return isPublicWebhookAddress(
        InternetAddress.fromRawAddress(b.sublist(12)));
  }
  // Excludes local, link-local, multicast, translation and transition prefixes.
  return b[0] & 0xe0 == 0x20 &&
      !(b[0] == 0x20 &&
          b[1] == 0x01 &&
          (b[2] <= 1 || (b[2] == 0x0d && b[3] == 0xb8))) &&
      !(b[0] == 0x20 && b[1] == 0x02);
}

class WebhookHttpFailure implements Exception {
  WebhookHttpFailure(this.retryable);
  final bool retryable;
}

typedef ProgressWebhookTransport = Future<void> Function(
    ProgressWebhook config, Map<String, Object?> body);

/// Validate every resolved address and pin the socket to one validated address.
/// TLS still authenticates the original hostname. Redirects and proxies are off.
Future<void> postProgressWebhook(
    ProgressWebhook config, Map<String, Object?> body) async {
  final addresses = await InternetAddress.lookup(config.url.host)
      .timeout(const Duration(seconds: 5));
  if (addresses.isEmpty || addresses.any((a) => !isPublicWebhookAddress(a))) {
    throw WebhookHttpFailure(false);
  }
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
  client.findProxy = (_) => 'DIRECT';
  client.connectionFactory = (uri, proxyHost, proxyPort) =>
      Socket.startConnect(addresses.first, uri.port);
  try {
    final request =
        await client.postUrl(config.url).timeout(const Duration(seconds: 5));
    request.followRedirects = false;
    request.headers.contentType = ContentType.json;
    request.headers
        .set(HttpHeaders.authorizationHeader, 'Bearer ${config.bearerToken}');
    request.write(jsonEncode(body));
    final response = await request.close().timeout(const Duration(seconds: 5));
    // No provider response body is needed; closing avoids unbounded downloads.
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw WebhookHttpFailure(
          response.statusCode == 429 || response.statusCode >= 500);
    }
  } finally {
    client.close(force: true);
  }
}

Map<String, Object?>? validProgressIdentity(Map<String, Object?> value) {
  final type = value['type'];
  final contentId = value['contentId'];
  final videoId = value['videoId'];
  if ((type != 'movie' && type != 'series') ||
      contentId is! String ||
      contentId.isEmpty ||
      contentId.length > 256 ||
      videoId is! String ||
      videoId.isEmpty ||
      videoId.length > 256) {
    return null;
  }
  for (final key in ['season', 'episode']) {
    final number = value[key];
    if (number != null && (number is! int || number < 0)) return null;
  }
  if (type == 'series' &&
      (value['season'] is! int || value['episode'] is! int)) {
    return null;
  }
  return Map.unmodifiable({
    'type': type,
    'contentId': contentId,
    'videoId': videoId,
    if (value['season'] != null) 'season': value['season'],
    if (value['episode'] != null) 'episode': value['episode'],
  });
}

class ProgressSnapshot {
  const ProgressSnapshot(
      {required this.playbackId,
      required this.itemId,
      required this.content,
      required this.state,
      required this.positionMs,
      required this.durationMs});
  final String playbackId;
  final String itemId;
  final Map<String, Object?> content;
  final String state;
  final int positionMs;
  final int durationMs;
  String get key => '$playbackId/$itemId';
}

/// Bounded, memory-only delivery. Network failure never blocks playback.
class ProgressWebhookReporter {
  ProgressWebhookReporter(
      {ProgressWebhookTransport? transport, DateTime Function()? now})
      : _transport = transport ?? postProgressWebhook,
        _now = now ?? DateTime.now;
  final ProgressWebhookTransport _transport;
  final DateTime Function() _now;
  ProgressWebhook? _config;
  ProgressSnapshot? _last;
  ProgressSnapshot? _lastValid;
  String? _finishedKey;
  String? _stableState;
  DateTime? _lastSent;
  Future<void> _pending = Future.value();
  int _queued = 0;
  int _generation = 0;

  void configure(ProgressWebhook? config) {
    _generation++;
    _config = config;
    _last = null;
    _lastValid = null;
    _finishedKey = null;
    _lastSent = null;
    _stableState = null;
  }

  void update(ProgressSnapshot? snapshot) {
    if (_config == null || snapshot == null || snapshot.key == _finishedKey) {
      return;
    }
    if (_last != null && _last!.key != snapshot.key) terminal('stopped');
    _last = snapshot;
    if (snapshot.durationMs > 0 &&
        !((snapshot.state == 'ended' || snapshot.state == 'stopped') &&
            snapshot.positionMs == 0 &&
            _lastValid != null &&
            _lastValid!.key == snapshot.key &&
            _lastValid!.positionMs > 0)) {
      _lastValid = snapshot;
    }
    if (snapshot.state == 'ended') {
      terminal('ended');
    } else if (snapshot.durationMs <= 0) {
      return;
    } else if (snapshot.state == 'playing') {
      if (_stableState != 'playing') {
        _emit('started', snapshot);
      } else if (_lastSent == null ||
          _now().difference(_lastSent!) >= const Duration(seconds: 30)) {
        _emit('progress', snapshot);
      }
      _stableState = 'playing';
    } else if (snapshot.state == 'paused' && _stableState == 'playing') {
      _emit('paused', snapshot);
      _stableState = 'paused';
    }
  }

  void terminal(String event,
      {bool sessionEnded = false, ProgressSnapshot? snapshot}) {
    final sample = snapshot ?? _last;
    final current = sample != null &&
            (sample.durationMs <= 0 || sample.positionMs == 0) &&
            _lastValid != null &&
            _lastValid!.key == sample.key &&
            _lastValid!.positionMs > 0
        ? _lastValid
        : sample;
    if (current != null && current.key != _finishedKey) {
      _emit(event, current);
      _finishedKey = current.key;
    }
    _last = null;
    _lastValid = null;
    _stableState = null;
    if (sessionEnded) _config = null;
  }

  void _emit(String event, ProgressSnapshot snapshot) {
    final config = _config;
    if (config == null || snapshot.durationMs <= 0 || _queued >= 16) return;
    _lastSent = _now();
    final body = <String, Object?>{
      'version': 1,
      'eventId': const Uuid().v4(),
      'playbackId': snapshot.playbackId,
      'itemId': snapshot.itemId,
      'event': event,
      'content': snapshot.content,
      'positionMs': snapshot.positionMs < 0 ? 0 : snapshot.positionMs,
      'durationMs': snapshot.durationMs < 0 ? 0 : snapshot.durationMs,
      'occurredAt': _now().toUtc().toIso8601String(),
    };
    final generation = _generation;
    final created = _now();
    _queued++;
    _pending = _pending.then((_) async {
      try {
        for (var attempt = 0; attempt < 2; attempt++) {
          if (_now().difference(created) > const Duration(seconds: 90)) return;
          // Already queued final events get one attempt after replacement.
          try {
            await _transport(config, body).timeout(const Duration(seconds: 16));
            return;
          } catch (error) {
            if (generation != _generation ||
                (error is WebhookHttpFailure && !error.retryable)) {
              return;
            }
            if (attempt == 0) {
              await Future<void>.delayed(const Duration(seconds: 1));
            }
          }
        }
      } finally {
        _queued--;
      }
    });
  }

  Future<void> get drained => _pending;
}
