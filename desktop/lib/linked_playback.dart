import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:playbridge_protocol/messages.pb.dart';
import 'package:uuid/uuid.dart';
import 'protocol.dart' show PlayPayloadX;

/// Playback operations are serialized; every reply still carries its own ID.
/// The authenticated extension owns consent, document identity and page IDs.
abstract class LinkedPlaybackBackend extends ChangeNotifier {
  String get destination;
  bool get connected;
  bool get external;
  int get selectionRevision;
  Map<String, dynamic> get status;
  void waitForItems(bool enabled);
  Future<bool> replace(List<LinkedItem> items, int index,
      VisualMetadata? metadata, bool skipPreplay, bool Function() canCommit);
  Future<bool> append(List<LinkedItem> items, bool Function() canCommit);
  Future<bool> jump(int index, bool Function() canCommit);
}

class LinkedPlaybackError implements Exception {
  const LinkedPlaybackError(this.code);
  final String code;
}

class LinkedItem {
  const LinkedItem(this.id, this.media);
  final String id;
  final PlayPayload media;
}

class _Session {
  _Session(this.owner, this.emit, this.origin, this.destination, this.revision,
      this.external, this.items, this.index, this.lastActivity);
  final String id = const Uuid().v4();
  final Object owner;
  final void Function(Map<String, dynamic>) emit;
  final String origin;
  final String destination;
  final int revision;
  final bool external;
  List<LinkedItem> items;
  int index;
  DateTime lastActivity;
  bool ready = false;
  bool updating = true;
  bool endOfList = false;
  String? pendingNeed;
  String? acceptedNeed;
  DateTime? lastNeed;
  String? lastStateKey;
  DateTime? lastState;
  bool seenPlayback = false;
  String? playbackId;
}

class LinkedPlaybackCoordinator extends ChangeNotifier {
  LinkedPlaybackCoordinator(this.backend,
      {DateTime Function()? now,
      this.idleTimeout = const Duration(minutes: 30),
      this.operationTimeout = const Duration(seconds: 20),
      Duration tickInterval = const Duration(seconds: 1)})
      : _now = now ?? DateTime.now {
    backend.addListener(poll);
    _timer = Timer.periodic(tickInterval, (_) => poll());
  }
  final LinkedPlaybackBackend backend;
  final DateTime Function() _now;
  final Duration idleTimeout;
  final Duration operationTimeout;
  final Expando<bool> _closedOwners = Expando<bool>();
  late final Timer _timer;
  _Session? _active;
  Future<void> _operations = Future<void>.value();
  int _inFlight = 0;
  bool _disposed = false;
  String? get controllerOrigin => _active?.origin;
  String? get sessionId => _active?.id;

  Future<Map<String, dynamic>> handle(
    Map message,
    Object owner,
    void Function(Map<String, dynamic>) emit,
  ) async {
    final requestId = message['bridgeRequestId'];
    Map<String, dynamic> reply(String? error, [Map<String, dynamic>? value]) =>
        {
          'type': 'linked_result',
          'bridgeRequestId': requestId is String ? requestId : '',
          'ok': error == null,
          if (error != null) 'error': error,
          ...?value,
        };
    try {
      if (requestId is! String ||
          requestId.trim().isEmpty ||
          requestId.length > 128) {
        throw const LinkedPlaybackError('invalid_request');
      }
      if (utf8.encode(jsonEncode(message)).length > 64 * 1024 ||
          _inFlight >= 8) {
        throw const LinkedPlaybackError('resource_limit');
      }
      if (message.containsKey('progressWebhook') ||
          (message['payload'] is Map &&
              ((message['payload'] as Map).containsKey('progressWebhook') ||
                  _hasItemWebhook((message['payload'] as Map)['items'])))) {
        throw const LinkedPlaybackError('invalid_request');
      }
      _inFlight++;
      final previous = _operations;
      final complete = Completer<void>();
      _operations = complete.future;
      try {
        await previous;
        if (_disposed) throw const LinkedPlaybackError('unavailable');
        if (_closedOwners[owner] == true) {
          throw const LinkedPlaybackError('session_ended');
        }
        poll();
        final result = await _handle(message, owner, emit);
        return reply(null, result);
      } finally {
        _inFlight--;
        complete.complete();
      }
    } on LinkedPlaybackError catch (error) {
      return reply(error.code);
    } on Object {
      // Never log request data or exceptions that may contain media credentials.
      return reply('unavailable');
    }
  }

  Future<Map<String, dynamic>> _handle(Map message, Object owner,
      void Function(Map<String, dynamic>) emit) async {
    final type = message['type'];
    if (type == 'linked_play' || type == 'linked_open') {
      final origin = normalizedOrigin(message['origin']);
      if (origin == null) throw const LinkedPlaybackError('invalid_request');
      final destination = backend.destination;
      if (type == 'linked_play' && message['destination'] != destination) {
        throw const LinkedPlaybackError('destination_changed');
      }
      if (!backend.connected) {
        throw const LinkedPlaybackError('destination_offline');
      }
      final payload = _payload(message);
      final items =
          parseLinkedItems(payload['items'], _privateOrigins(message));
      final index = _index(payload['startIndex'] ?? 0, items.length);
      _externalLimits(items, backend.external);
      final metadata = _metadata(payload['metadata']);
      final skip = payload['skipPreplay'] ?? false;
      if (skip is! bool) throw const LinkedPlaybackError('invalid_request');
      unlink('superseded');
      final session = _Session(owner, emit, origin, destination,
          backend.selectionRevision, backend.external, items, index, _now());
      _active = session;
      backend.waitForItems(!session.external);
      notifyListeners();
      await _update(
          session,
          () => backend.replace(
              items, index, metadata, skip, () => _canCommit(session)));
      return {'sessionId': session.id};
    }
    final session = _active;
    if (session == null ||
        message['sessionId'] != session.id ||
        !identical(owner, session.owner)) {
      throw const LinkedPlaybackError('session_ended');
    }
    _check(session);
    session.lastActivity = _now();
    switch (type) {
      case 'linked_ping':
        session.ready = true;
        poll();
      case 'linked_unlink':
        final reason = message['reason'] ?? 'unlinked';
        if (!const {'unlinked', 'navigation', 'permission_reset'}
            .contains(reason)) {
          throw const LinkedPlaybackError('invalid_request');
        }
        unlink(reason as String);
      case 'linked_replace':
        final payload = _payload(message);
        final items =
            parseLinkedItems(payload['items'], _privateOrigins(message));
        final index = _index(payload['startIndex'] ?? 0, items.length);
        _externalLimits(items, session.external);
        final metadata = _metadata(payload['metadata']);
        final skip = payload['skipPreplay'] ?? false;
        if (skip is! bool) throw const LinkedPlaybackError('invalid_request');
        await _update(
            session,
            () => backend.replace(
                items, index, metadata, skip, () => _canCommit(session)),
            commit: () {
          session.items = items;
          session.index = index;
          session.pendingNeed = null;
          session.acceptedNeed = null;
          session.endOfList = false;
          backend.waitForItems(!session.external);
          session.playbackId = null;
        });
      case 'linked_append':
      case 'linked_supply':
        final payload = _payload(message);
        final supply = type == 'linked_supply';
        final requestId = payload['requestId'];
        if (session.external) {
          throw const LinkedPlaybackError('unsupported_queue');
        }
        if (supply &&
            requestId is String &&
            requestId == session.acceptedNeed) {
          return {};
        }
        if (supply &&
            (requestId is! String || requestId != session.pendingNeed)) {
          throw const LinkedPlaybackError('invalid_request');
        }
        final end = supply ? payload['endOfList'] ?? false : false;
        if (end is! bool) throw const LinkedPlaybackError('invalid_request');
        final items = parseLinkedItems(
            payload['items'], _privateOrigins(message),
            allowEmpty: supply && end);
        if (supply && items.length > 3) {
          throw const LinkedPlaybackError('invalid_request');
        }
        if (items
            .any((item) => session.items.any((old) => old.id == item.id))) {
          throw const LinkedPlaybackError('invalid_request');
        }
        if (session.items.length + items.length > 200) {
          throw const LinkedPlaybackError('resource_limit');
        }
        await _update(
            session,
            () => items.isEmpty
                ? Future.value(true)
                : backend.append(items, () => _canCommit(session)), commit: () {
          session.items.addAll(items);
          if (supply) {
            session.pendingNeed = null;
            session.acceptedNeed = requestId as String;
            session.endOfList = end;
            backend.waitForItems(!end);
          }
        });
      case 'linked_jump':
        final index = _index(_payload(message)['index'], session.items.length);
        if (session.external) {
          throw const LinkedPlaybackError('unsupported_operation');
        }
        await _update(
            session, () => backend.jump(index, () => _canCommit(session)),
            commit: () {
          session.index = index;
          session.pendingNeed = null;
        });
      default:
        throw const LinkedPlaybackError('invalid_request');
    }
    return {};
  }

  Future<void> _update(_Session session, Future<bool> Function() operation,
      {VoidCallback? commit}) async {
    session.updating = true;
    try {
      final ok = await operation().timeout(operationTimeout,
          onTimeout: () => throw const LinkedPlaybackError('timeout'));
      _check(session);
      if (!ok) throw const LinkedPlaybackError('unavailable');
      commit?.call();
    } on Object {
      if (identical(_active, session)) unlink('queue_update_failed');
      rethrow;
    } finally {
      session.updating = false;
    }
    poll();
  }

  bool _canCommit(_Session session) =>
      !_disposed &&
      identical(_active, session) &&
      backend.destination == session.destination &&
      backend.selectionRevision == session.revision &&
      backend.connected;

  void _check(_Session session) {
    if (backend.destination != session.destination ||
        backend.selectionRevision != session.revision) {
      throw const LinkedPlaybackError('destination_changed');
    }
    if (!backend.connected) {
      throw const LinkedPlaybackError('destination_offline');
    }
    if (!identical(_active, session)) {
      throw const LinkedPlaybackError('session_ended');
    }
  }

  void poll() {
    final session = _active;
    if (session == null || _disposed) return;
    if (backend.destination != session.destination ||
        backend.selectionRevision != session.revision) {
      unlink('receiver_changed');
      return;
    }
    final now = _now();
    if (now.difference(session.lastActivity) > idleTimeout) {
      unlink('session_expired');
      return;
    }
    if (session.updating || !session.ready) return;
    final status = backend.status;
    final playbackId = status['playbackId'];
    if (playbackId is String) {
      if (session.playbackId != null && session.playbackId != playbackId) {
        unlink('superseded');
        return;
      }
      session.playbackId = playbackId;
    }
    final state = _state(status['state']);
    final index = (status['currentIndex'] as int? ?? session.index)
        .clamp(0, session.items.length - 1);
    session.index = index;
    final detail = <String, dynamic>{
      'state': state,
      'positionMs': _position(status['positionMs']),
      'durationMs': _position(status['durationMs']),
      'title':
          status['title'] as String? ?? session.items[index].media.titleOrNull,
      'currentIndex': index,
      'totalCount': session.items.length,
      'items': [
        for (var i = 0; i < session.items.length; i++)
          {
            'index': i,
            'id': session.items[i].id,
            'title': session.items[i].media.titleOrNull
          }
      ],
    };
    final stateKey = '$state:$index';
    if (stateKey != session.lastStateKey ||
        session.lastState == null ||
        now.difference(session.lastState!) >= const Duration(seconds: 1)) {
      session.lastStateKey = stateKey;
      session.lastState = now;
      _event(session, 'statechange', detail);
    }
    if (const {'playing', 'paused', 'buffering', 'ended'}.contains(state)) {
      session.seenPlayback = true;
    }
    if (state == 'error' ||
        (session.seenPlayback && state == 'idle') ||
        (state == 'ended' && (session.endOfList || session.external))) {
      unlink('receiver_stopped');
      return;
    }
    if (!session.external &&
        !session.endOfList &&
        index == session.items.length - 1) {
      if (session.pendingNeed == null ||
          session.lastNeed == null ||
          now.difference(session.lastNeed!) >= const Duration(seconds: 5)) {
        session.pendingNeed ??= const Uuid().v4();
        session.lastNeed = now;
        _event(session, 'needitems', {
          'requestId': session.pendingNeed,
          'afterIndex': session.items.length - 1,
          'afterItemId': session.items.last.id,
          'count': 3,
        });
      }
    }
  }

  void _event(_Session session, String name, Map<String, dynamic> detail) =>
      session.emit({
        'type': 'linked_event',
        'sessionId': session.id,
        'event': name,
        'detail': detail,
      });

  void unlink([String reason = 'unlinked']) {
    final session = _active;
    if (session == null) return;
    _active = null;
    backend.waitForItems(false);
    _event(session, 'ended', {'reason': reason});
    notifyListeners();
  }

  void disconnectOwner(Object owner) {
    _closedOwners[owner] = true;
    if (identical(_active?.owner, owner)) unlink('navigation');
  }

  @override
  void dispose() {
    unlink('unlinked');
    _disposed = true;
    _timer.cancel();
    backend.removeListener(poll);
    backend.dispose();
    super.dispose();
  }
}

Map _payload(Map message) {
  final payload = message['payload'];
  if (payload is! Map) throw const LinkedPlaybackError('invalid_request');
  return payload;
}

bool _hasItemWebhook(Object? items) =>
    items is List &&
    items.any((item) => item is Map && item.containsKey('progressWebhook'));

int _index(Object? value, int count) {
  if (value is! int || value < 0 || value >= count) {
    throw const LinkedPlaybackError('invalid_request');
  }
  return value;
}

int _position(Object? value) =>
    value is num && value.isFinite ? value.toInt().clamp(0, 1 << 53) : 0;
String _state(Object? value) => switch (value) {
      'playing' ||
      'paused' ||
      'buffering' ||
      'connecting' ||
      'idle' ||
      'ended' ||
      'error' =>
        value as String,
      'stopped' || 'finished' || 'complete' => 'ended',
      _ => 'connecting',
    };
String? normalizedOrigin(Object? value) {
  if (value is! String) return null;
  final uri = Uri.tryParse(value);
  if (uri == null ||
      !_http(uri) ||
      uri.userInfo.isNotEmpty ||
      !const {'', '/'}.contains(uri.path) ||
      uri.hasQuery ||
      uri.hasFragment) {
    return null;
  }
  return uri.origin.toLowerCase();
}

bool _http(Uri uri) =>
    const {'http', 'https'}.contains(uri.scheme) && uri.host.isNotEmpty;
List<String> _privateOrigins(Map message) {
  final values = message['allowedPrivateOrigins'] ?? const [];
  if (values is! List) throw const LinkedPlaybackError('invalid_request');
  if (values.length > 16) throw const LinkedPlaybackError('resource_limit');
  final result = <String>{};
  for (final value in values) {
    final origin = normalizedOrigin(value);
    if (origin == null) throw const LinkedPlaybackError('invalid_request');
    result.add(origin);
  }
  return result.toList();
}

void _externalLimits(List<LinkedItem> items, bool external) {
  if (!external) return;
  if (items.length > 1) throw const LinkedPlaybackError('unsupported_queue');
  if (items.any((item) =>
      item.media.subtitles.isNotEmpty ||
      item.media.subtitleResources.isNotEmpty)) {
    throw const LinkedPlaybackError('unsupported_subtitles');
  }
}

String? _text(Object? value, int limit) {
  if (value == null) return null;
  if (value is! String || value.length > limit) {
    throw const LinkedPlaybackError('invalid_request');
  }
  return value.isEmpty ? null : value;
}

Map<String, String> _headers(Object? value) {
  if (value == null) return {};
  if (value is! Map) throw const LinkedPlaybackError('invalid_request');
  if (value.length > 16) throw const LinkedPlaybackError('resource_limit');
  final result = <String, String>{};
  final names = <String>{};
  var size = 0;
  for (final entry in value.entries) {
    final key = entry.key;
    final text = entry.value;
    if (key is! String ||
        text is! String ||
        !const {
          'authorization',
          'cookie',
          'referer',
          'origin',
          'user-agent',
          'accept',
          'accept-language'
        }.contains(key.toLowerCase()) ||
        !names.add(key.toLowerCase()) ||
        text.codeUnits.any((c) => c < 32 || c == 127)) {
      throw const LinkedPlaybackError('invalid_request');
    }
    if (const {'origin', 'referer'}.contains(key.toLowerCase())) _url(text);
    size += utf8.encode(key).length + utf8.encode(text).length;
    if (size > 16 * 1024) throw const LinkedPlaybackError('resource_limit');
    result[key] = text;
  }
  return result;
}

String _url(Object? value) {
  if (value is! String || value.length > 8192) {
    throw const LinkedPlaybackError('invalid_request');
  }
  final uri = Uri.tryParse(value);
  if (uri == null || !_http(uri) || uri.userInfo.isNotEmpty) {
    throw const LinkedPlaybackError('invalid_request');
  }
  return value;
}

VisualMetadata? _metadata(Object? value) {
  if (value == null) return null;
  if (value is! Map) throw const LinkedPlaybackError('invalid_request');
  if (utf8.encode(jsonEncode(value)).length > 16 * 1024) {
    throw const LinkedPlaybackError('resource_limit');
  }
  try {
    return VisualMetadata()
      ..mergeFromProto3Json(value, ignoreUnknownFields: true);
  } on Object {
    throw const LinkedPlaybackError('invalid_request');
  }
}

List<LinkedItem> parseLinkedItems(Object? value, List<String> origins,
    {bool allowEmpty = false}) {
  if (value is! List || (value.isEmpty && !allowEmpty)) {
    throw const LinkedPlaybackError('invalid_request');
  }
  if (value.length > 50) throw const LinkedPlaybackError('resource_limit');
  final ids = <String>{};
  return value.map((item) {
    if (item is! Map || item.containsKey('progressWebhook')) {
      throw const LinkedPlaybackError('invalid_request');
    }
    final id = _text(item['id'], 128);
    if (id == null || id.trim().isEmpty || !ids.add(id)) {
      throw const LinkedPlaybackError('invalid_request');
    }
    final start = item['startPositionMs'];
    if (start != null && (start is! int || start < 0 || start > 604800000)) {
      throw const LinkedPlaybackError('invalid_request');
    }
    final subtitles = item['subtitles'] ?? const [];
    final resources = item['subtitleResources'] ?? const [];
    if (subtitles is! List || resources is! List) {
      throw const LinkedPlaybackError('invalid_request');
    }
    if (subtitles.length + resources.length > 16) {
      throw const LinkedPlaybackError('resource_limit');
    }
    final media = PlayPayload()
      ..url = _url(item['url'])
      ..detectedBy = 'linked_page'
      ..headers.addAll(_headers(item['headers']))
      ..allowedPrivateOrigins.addAll(origins)
      ..subtitles.addAll(subtitles.map(_url));
    final title = _text(item['title'], 4096);
    final contentType = _text(item['contentType'], 256);
    if (title != null) media.title = title;
    if (contentType != null) media.contentType = contentType;
    if (start != null) media.mergeFromProto3Json({'startPositionMs': '$start'});
    final metadata = _metadata(item['metadata']);
    if (metadata != null) media.visualMetadata = metadata;
    for (final resource in resources) {
      if (resource is! Map || resource.containsKey('progressWebhook')) {
        throw const LinkedPlaybackError('invalid_request');
      }
      final sub = SubtitleResource()
        ..url = _url(resource['url'])
        ..headers.addAll(_headers(resource['headers']));
      final label = _text(resource['label'], 256);
      final language = _text(resource['language'], 64);
      if (label != null) sub.label = label;
      if (language != null) sub.language = language;
      media.subtitleResources.add(sub);
    }
    return LinkedItem(id, media);
  }).toList();
}
