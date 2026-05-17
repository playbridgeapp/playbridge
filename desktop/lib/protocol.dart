import 'dart:convert';

import 'package:playbridge_protocol/messages.pb.dart';

export 'package:playbridge_protocol/messages.pb.dart'
    show PlayPayload, VisualMetadata, PlaylistPayload;

// ==================== Command sealed class ====================

sealed class Command {
  const Command();
}

// PlayCmd wraps the generated PlayPayload so all proto fields are preserved,
// while exposing the four fields server.dart pattern-matches on as getters.
class PlayCmd extends Command {
  final PlayPayload payload;

  PlayCmd(this.payload);

  String get url => payload.url;
  String? get title => payload.hasTitle() ? payload.title : null;
  Map<String, String>? get headers =>
      payload.headers.isEmpty ? null : Map.unmodifiable(payload.headers);
  List<String>? get subtitles =>
      payload.subtitles.isEmpty ? null : List.unmodifiable(payload.subtitles);
}

class ControlCmd extends Command {
  final String command;
  const ControlCmd(this.command);
}

class PlaylistCmd extends Command {
  final List<PlayCmd> items;
  final int startIndex;
  const PlaylistCmd(this.items, this.startIndex);
}

class PlaylistJumpCmd extends Command {
  final int index;
  const PlaylistJumpCmd(this.index);
}

class QueueAddCmd extends Command {
  final PlayCmd item;
  const QueueAddCmd(this.item);
}

class ContextQueryCmd extends Command {
  const ContextQueryCmd();
}

class PingCmd extends Command {
  const PingCmd();
}

class AuthCmd extends Command {
  final String? token;
  const AuthCmd({this.token});
}

class PairingRequestCmd extends Command {
  final String deviceName;
  final String deviceUUID;
  const PairingRequestCmd({required this.deviceName, required this.deviceUUID});
}

class PairingApprovedCmd extends Command {
  final String token;
  const PairingApprovedCmd(this.token);
}

class PairingDeniedCmd extends Command {
  const PairingDeniedCmd();
}

class UnknownCmd extends Command {
  final String type;
  const UnknownCmd(this.type);
}

// ==================== Parsing ====================

PlayCmd _parsePlayPayload(Map<String, dynamic> p) {
  final proto = PlayPayload();
  try {
    proto.mergeFromProto3Json(p, ignoreUnknownFields: true);
  } catch (e) {
    print('PlayPayload proto3 parse failed, falling back to url-only: $e');
    if (p['url'] case final String u) proto.url = u;
  }
  return PlayCmd(proto);
}

Command parseCommand(String json) {
  try {
    final root = jsonDecode(json);
    if (root is! Map) return const UnknownCmd('not_a_map');
    final type = root['type'] as String?;

    switch (type) {
      case 'ping':
        return const PingCmd();
      case 'pairing_request':
        return PairingRequestCmd(
          deviceName: (root['deviceName'] as String?) ?? '',
          deviceUUID: (root['deviceUUID'] as String?) ?? '',
        );
      case 'pairing_approved':
        return PairingApprovedCmd((root['token'] as String?) ?? '');
      case 'pairing_denied':
        return const PairingDeniedCmd();
      case 'auth':
        return AuthCmd(token: root['token'] as String?);
      case 'command':
        final action = root['action'] as String?;
        final payload = root['payload'];
        switch (action) {
          case 'play':
            if (payload is Map<String, dynamic>) return _parsePlayPayload(payload);
            return const UnknownCmd('play_no_payload');
          case 'control':
            return ControlCmd((payload?['command'] ?? '') as String);
          case 'context_query':
            return const ContextQueryCmd();
          case 'playlist':
            final items = (payload?['items'] as List? ?? const [])
                .whereType<Map<String, dynamic>>()
                .map(_parsePlayPayload)
                .toList();
            return PlaylistCmd(items, (payload?['startIndex'] ?? 0) as int);
          case 'playlist_jump':
            return PlaylistJumpCmd((payload?['index'] ?? 0) as int);
          case 'queue_add':
            final item = payload?['item'];
            if (item is Map<String, dynamic>) return QueueAddCmd(_parsePlayPayload(item));
            return const UnknownCmd('queue_add_no_item');
          default:
            return UnknownCmd(action ?? 'no_action');
        }
      default:
        return UnknownCmd(type ?? 'no_type');
    }
  } catch (e) {
    return UnknownCmd('parse_error: $e');
  }
}

// ==================== Outgoing message builders ====================

String pongJson() => jsonEncode({'type': 'pong'});

String pairingApprovedJson(String token) =>
    jsonEncode({'type': 'pairing_approved', 'token': token});

String pairingDeniedJson() => jsonEncode({'type': 'pairing_denied'});

String authResponseJson({required bool success, String? token}) => jsonEncode({
      'type': 'auth_response',
      'success': success,
      if (token != null) 'token': token,
    });

String contextJson(String active) => jsonEncode({'type': 'context', 'active': active});

String statusJson({
  required String state,
  required int positionMs,
  required int durationMs,
  String? title,
}) =>
    jsonEncode({
      'type': 'status',
      'state': state,
      'position': positionMs,
      'duration': durationMs,
      if (title != null) 'title': title,
    });

String playlistStatusJson({
  required List<({int index, String title})> items,
  required int currentIndex,
}) =>
    jsonEncode({
      'type': 'playlist_status',
      'items': items.map((e) => {'index': e.index, 'title': e.title}).toList(),
      'currentIndex': currentIndex,
      'totalCount': items.length,
    });
