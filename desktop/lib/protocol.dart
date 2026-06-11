import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:playbridge_protocol/messages.pb.dart';

export 'package:playbridge_protocol/messages.pb.dart'
    show PlayPayload, VisualMetadata, PlaylistPayload;

// ==================== PlayPayload ergonomics ====================
// Generated proto types treat optional scalars as empty-string/empty-list when
// unset. These extensions restore the legacy nullable accessors so consumer
// code can keep using `?? fallback` idioms without sprinkling `hasFoo()` checks.

extension PlayPayloadX on PlayPayload {
  String? get titleOrNull => hasTitle() ? title : null;
  Map<String, String>? get headersOrNull =>
      headers.isEmpty ? null : Map.unmodifiable(headers);
  List<String>? get subtitlesOrNull =>
      subtitles.isEmpty ? null : List.unmodifiable(subtitles);
  int? get startPositionMsOrNull =>
      hasStartPositionMs() ? startPositionMs.toInt() : null;
  String? get bingeGroupOrNull => hasBingeGroup() ? bingeGroup : null;
  int? get seasonOrNull => hasVisualMetadata() && visualMetadata.hasSeason()
      ? visualMetadata.season
      : null;
  int? get episodeOrNull => hasVisualMetadata() && visualMetadata.hasEpisode()
      ? visualMetadata.episode
      : null;
  String? get imdbIdOrNull => hasVisualMetadata() && visualMetadata.hasImdbId()
      ? visualMetadata.imdbId
      : null;

  String? _vm(bool Function(VisualMetadata) has, String Function(VisualMetadata) get) {
    if (!hasVisualMetadata()) return null;
    if (!has(visualMetadata)) return null;
    final v = get(visualMetadata);
    return v.isEmpty ? null : v;
  }

  String? get backdropUrlOrNull =>
      _vm((m) => m.hasBackdropUrl(), (m) => m.backdropUrl);
  String? get posterUrlOrNull =>
      _vm((m) => m.hasPosterUrl(), (m) => m.posterUrl);
  String? get logoUrlOrNull => _vm((m) => m.hasLogoUrl(), (m) => m.logoUrl);
  String? get overviewOrNull => _vm((m) => m.hasOverview(), (m) => m.overview);
  String? get yearOrNull => _vm((m) => m.hasYear(), (m) => m.year);
  String? get ratingOrNull => _vm((m) => m.hasRating(), (m) => m.rating);
  String? get runtimeOrNull => _vm((m) => m.hasRuntime(), (m) => m.runtime);
  String? get episodeTitleOrNull =>
      _vm((m) => m.hasEpisodeTitle(), (m) => m.episodeTitle);
}

// ==================== Command sealed class ====================

sealed class Command {
  const Command();
}

class ControlCmd extends Command {
  final String command;
  const ControlCmd(this.command);
}

class PlaylistCmd extends Command {
  final List<PlayPayload> items;
  final int startIndex;
  const PlaylistCmd(this.items, this.startIndex);
}

class PlaylistJumpCmd extends Command {
  final int index;
  const PlaylistJumpCmd(this.index);
}

class QueueAddCmd extends Command {
  final PlayPayload item;
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

PlayPayload _parsePlayPayload(Map<String, dynamic> p) {
  final proto = PlayPayload();
  try {
    proto.mergeFromProto3Json(p, ignoreUnknownFields: true);
  } catch (e) {
    debugPrint('PlayPayload proto3 parse failed, falling back to url-only: $e');
    if (p['url'] case final String u) proto.url = u;
  }
  return proto;
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
            if (item is Map<String, dynamic>) {
              return QueueAddCmd(_parsePlayPayload(item));
            }
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

String pairingApprovedJson(
  String token, {
  String? certFingerprint,
  List<String> players = const [],
  List<String> browsers = const [],
}) =>
    jsonEncode({
      'type': 'pairing_approved',
      'token': token,
      if (certFingerprint != null) 'certFingerprint': certFingerprint,
      if (players.isNotEmpty) 'players': players,
      if (browsers.isNotEmpty) 'browsers': browsers,
    });

String pairingDeniedJson() => jsonEncode({'type': 'pairing_denied'});

String authResponseJson({
  required bool success,
  String? token,
  String? certFingerprint,
  List<String> players = const [],
  List<String> browsers = const [],
}) =>
    jsonEncode({
      'type': 'auth_response',
      'success': success,
      if (token != null) 'token': token,
      if (certFingerprint != null) 'certFingerprint': certFingerprint,
      if (players.isNotEmpty) 'players': players,
      if (browsers.isNotEmpty) 'browsers': browsers,
    });

String contextJson(String active) =>
    jsonEncode({'type': 'context', 'active': active});

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

/// Outgoing `tracks` message: the phone remote renders these as the
/// audio/subtitle chips and replies with `audio_track:<id>`/`sub_track:<id>`.
String tracksJson({
  required List<({String id, String name, bool selected})> audio,
  required List<({String id, String name, bool selected})> subtitle,
}) =>
    jsonEncode({
      'type': 'tracks',
      'audio': [
        for (final t in audio)
          {'id': t.id, 'name': t.name, 'selected': t.selected},
      ],
      'subtitle': [
        for (final t in subtitle)
          {'id': t.id, 'name': t.name, 'selected': t.selected},
      ],
    });

/// One entry of an outgoing `playlist_status`. The optional metadata mirrors
/// the Android receiver's echo (season/episode/imdbId/bingeGroup) so the phone
/// can re-attach its lazy episode queue and match watch progress.
typedef PlaylistStatusItem = ({
  int index,
  String title,
  int? season,
  int? episode,
  String? imdbId,
  String? bingeGroup,
});

String playlistStatusJson({
  required List<PlaylistStatusItem> items,
  required int currentIndex,
}) =>
    jsonEncode({
      'type': 'playlist_status',
      'items': items
          .map((e) => {
                'index': e.index,
                'title': e.title,
                if (e.season != null) 'season': e.season,
                if (e.episode != null) 'episode': e.episode,
                if (e.imdbId != null) 'imdbId': e.imdbId,
                if (e.bingeGroup != null) 'bingeGroup': e.bingeGroup,
              })
          .toList(),
      'currentIndex': currentIndex,
      'totalCount': items.length,
    });
