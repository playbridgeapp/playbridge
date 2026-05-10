import 'dart:convert';

/// Minimal Dart mirror of the PlayBridge protocol envelope.
/// See shared/src/commonMain/.../protocol/Message.kt for the source of truth.

sealed class Command {
  const Command();
}

class PlayCmd extends Command {
  final String url;
  final String? title;
  final Map<String, String>? headers;
  final List<String>? subtitles;
  const PlayCmd({required this.url, this.title, this.headers, this.subtitles});
}

class ControlCmd extends Command {
  /// One of: play, pause, stop, seek
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
  final String? pin;
  final String? token;
  const AuthCmd({this.pin, this.token});
}

class RequestPairingCmd extends Command {
  const RequestPairingCmd();
}

class UnknownCmd extends Command {
  final String type;
  const UnknownCmd(this.type);
}

PlayCmd _parsePlayPayload(Map<String, dynamic> p) {
  final headers = (p['headers'] as Map?)?.map(
    (k, v) => MapEntry(k.toString(), v.toString()),
  );
  final subs = (p['subtitles'] as List?)?.map((e) => e.toString()).toList();
  return PlayCmd(
    url: (p['url'] ?? '') as String,
    title: p['title'] as String?,
    headers: headers,
    subtitles: subs,
  );
}

Command parseCommand(String json) {
  try {
    final root = jsonDecode(json);
    if (root is! Map) return UnknownCmd('not_a_map');
    final type = root['type'] as String?;

    switch (type) {
      case 'ping':
        return const PingCmd();
      case 'request_pairing':
        return const RequestPairingCmd();
      case 'auth':
        return AuthCmd(
          pin: root['pin'] as String?,
          token: root['token'] as String?,
        );
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
            return PlaylistCmd(
              items,
              (payload?['startIndex'] ?? 0) as int,
            );
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

// ===== Outgoing message builders =====

String pongJson() => jsonEncode({'type': 'pong'});

String authResponseJson({required bool success, String? token}) =>
    jsonEncode({'type': 'auth_response', 'success': success, if (token != null) 'token': token});

String contextJson(String active) => jsonEncode({'type': 'context', 'active': active});

String statusJson({
  required String state,
  required int positionMs,
  required int durationMs,
  String? title,
}) => jsonEncode({
  'type': 'status',
  'state': state,
  'position': positionMs,
  'duration': durationMs,
  if (title != null) 'title': title,
});

String playlistStatusJson({
  required List<({int index, String title})> items,
  required int currentIndex,
}) => jsonEncode({
  'type': 'playlist_status',
  'items': items.map((e) => {'index': e.index, 'title': e.title}).toList(),
  'currentIndex': currentIndex,
  'totalCount': items.length,
});
