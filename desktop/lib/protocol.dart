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
  String? get contentTypeOrNull => hasContentType() ? contentType : null;
  String? get detectedByOrNull => hasDetectedBy() ? detectedBy : null;
  bool? get allowPrivateNetworkOrNull =>
      hasAllowPrivateNetwork() ? allowPrivateNetwork : null;
  int? get seasonOrNull => hasVisualMetadata() && visualMetadata.hasSeason()
      ? visualMetadata.season
      : null;
  int? get episodeOrNull => hasVisualMetadata() && visualMetadata.hasEpisode()
      ? visualMetadata.episode
      : null;
  String? get imdbIdOrNull => hasVisualMetadata() && visualMetadata.hasImdbId()
      ? visualMetadata.imdbId
      : null;

  String? _vm(
      bool Function(VisualMetadata) has, String Function(VisualMetadata) get) {
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

/// A remote key press from the phone (e.g. `volume_up` / `volume_down`). Most keys
/// are TV-browser navigation and don't apply to the desktop player; volume does.
class RemoteCmd extends Command {
  final String key;
  const RemoteCmd(this.key);
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

class ScreenMirrorStartCmd extends Command {
  const ScreenMirrorStartCmd(this.sessionId);

  final String sessionId;
}

class ScreenMirrorOfferCmd extends Command {
  const ScreenMirrorOfferCmd(this.sessionId, this.sdp);

  final String sessionId;
  final String sdp;
}

class ScreenMirrorCandidateCmd extends Command {
  const ScreenMirrorCandidateCmd({
    required this.sessionId,
    required this.sdpMid,
    required this.sdpMLineIndex,
    required this.candidate,
  });

  final String sessionId;
  final String? sdpMid;
  final int sdpMLineIndex;
  final String candidate;
}

class ScreenMirrorStopCmd extends Command {
  const ScreenMirrorStopCmd(this.sessionId, this.reason);

  final String sessionId;
  final String? reason;
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

class PairingCommitCmd extends Command {
  final String commit;
  final String deviceName;
  final String deviceUUID;
  const PairingCommitCmd({
    required this.commit,
    required this.deviceName,
    required this.deviceUUID,
  });
}

class PairingChallengeCmd extends Command {
  final String tvEphPub;
  final String nonceT;
  const PairingChallengeCmd({required this.tvEphPub, required this.nonceT});
}

class PairingRevealCmd extends Command {
  final String senderEphPub;
  final String nonceS;
  const PairingRevealCmd({required this.senderEphPub, required this.nonceS});
}

class PairingConfirmationCmd extends Command {
  final String mac;
  const PairingConfirmationCmd(this.mac);
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
      case 'pairing_commit':
        return PairingCommitCmd(
          commit: (root['commit'] as String?) ?? '',
          deviceName: (root['deviceName'] as String?) ?? '',
          deviceUUID: (root['deviceUUID'] as String?) ?? '',
        );
      case 'pairing_challenge':
        return PairingChallengeCmd(
          tvEphPub: (root['tvEphPub'] as String?) ?? '',
          nonceT: (root['nonceT'] as String?) ?? '',
        );
      case 'pairing_reveal':
        return PairingRevealCmd(
          senderEphPub: (root['senderEphPub'] as String?) ?? '',
          nonceS: (root['nonceS'] as String?) ?? '',
        );
      case 'pairing_confirmation':
        return PairingConfirmationCmd(
          (root['mac'] as String?) ?? '',
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
          case 'remote':
            return RemoteCmd((payload?['key'] ?? '') as String);
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
          case 'screen_mirror_start':
            final sessionId = _screenMirrorSessionId(payload);
            if (sessionId == null || payload?['protocolVersion'] != 1) {
              return const UnknownCmd('screen_mirror_start_parse_error');
            }
            return ScreenMirrorStartCmd(sessionId);
          case 'screen_mirror_offer':
            final sessionId = _screenMirrorSessionId(payload);
            final sdp = payload?['sdp'];
            if (sessionId == null || sdp is! String || sdp.isEmpty) {
              return const UnknownCmd('screen_mirror_offer_parse_error');
            }
            return ScreenMirrorOfferCmd(sessionId, sdp);
          case 'screen_mirror_candidate':
            final sessionId = _screenMirrorSessionId(payload);
            final sdpMid = payload?['sdpMid'];
            final sdpMLineIndex = payload?['sdpMLineIndex'];
            final candidate = payload?['candidate'];
            if (sessionId == null ||
                (sdpMid != null && sdpMid is! String) ||
                sdpMLineIndex is! int ||
                sdpMLineIndex < 0 ||
                candidate is! String ||
                candidate.isEmpty) {
              return const UnknownCmd('screen_mirror_candidate_parse_error');
            }
            return ScreenMirrorCandidateCmd(
              sessionId: sessionId,
              sdpMid: sdpMid as String?,
              sdpMLineIndex: sdpMLineIndex,
              candidate: candidate,
            );
          case 'screen_mirror_stop':
            final sessionId = _screenMirrorSessionId(payload);
            final reason = payload?['reason'];
            if (sessionId == null || (reason != null && reason is! String)) {
              return const UnknownCmd('screen_mirror_stop_parse_error');
            }
            return ScreenMirrorStopCmd(sessionId, reason as String?);
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

final RegExp _screenMirrorSessionPattern = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
);

String? _screenMirrorSessionId(dynamic payload) {
  if (payload is! Map) return null;
  final sessionId = payload['sessionId'];
  if (sessionId is! String ||
      !_screenMirrorSessionPattern.hasMatch(sessionId)) {
    return null;
  }
  return sessionId;
}

// ==================== Outgoing message builders ====================

String pongJson() => jsonEncode({'type': 'pong'});

String pairingApprovedJson(
  String nonce,
  String ciphertext,
) =>
    jsonEncode({
      'type': 'pairing_approved',
      'nonce': nonce,
      'ciphertext': ciphertext,
    });

String pairingDeniedJson() => jsonEncode({'type': 'pairing_denied'});

String authResponseJson({
  required bool success,
  String? certFingerprint,
  List<String> players = const [],
  List<String> browsers = const [],
}) =>
    jsonEncode({
      'type': 'auth_response',
      'success': success,
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

// ==================== Sender-side outgoing builders ====================
// The desktop acting as a SENDER (casting to a TV receiver) builds these. The
// wire format mirrors the phone's shared `create*Json` (IncomingMessage.kt) so
// the Android/Apple TV parsers accept them unchanged.
//
// NOTE: pairing_request uses snake_case `device_name`/`device_uuid` to match the
// TV receivers. (The receiver-side `parseCommand` above reads camelCase — that
// path is desktop-as-receiver only and is unrelated to this sender wire format.)

String senderPingJson() => jsonEncode({'type': 'ping'});

String senderAuthJson(String token) =>
    jsonEncode({'type': 'auth', 'token': token});

String senderPairingRequestJson({
  required String deviceName,
  required String deviceUUID,
}) =>
    jsonEncode({
      'type': 'pairing_request',
      'device_name': deviceName,
      'device_uuid': deviceUUID,
    });

// ==================== SAS handshake builders ====================

/// Sender → TV/receiver: commit + device identity (step 1).
String senderPairingCommitJson({
  required String commit,
  required String deviceName,
  required String deviceUUID,
}) =>
    jsonEncode({
      'type': 'pairing_commit',
      'commit': commit,
      'deviceName': deviceName,
      'deviceUUID': deviceUUID,
    });

/// Receiver → sender: TV's ephemeral public key + nonce (step 2).
String pairingChallengeJson({
  required String tvEphPub,
  required String nonceT,
}) =>
    jsonEncode({
      'type': 'pairing_challenge',
      'tvEphPub': tvEphPub,
      'nonceT': nonceT,
    });

/// Sender → TV/receiver: reveal the committed values (step 3).
String senderPairingRevealJson({
  required String senderEphPub,
  required String nonceS,
}) =>
    jsonEncode({
      'type': 'pairing_reveal',
      'senderEphPub': senderEphPub,
      'nonceS': nonceS,
    });

/// Sender → TV/receiver: HMAC confirmation of the SAS code (step 4).
String senderPairingConfirmationJson(String mac) =>
    jsonEncode({'type': 'pairing_confirmation', 'mac': mac});

/// `{"type":"command","action":<action>,"payload":<payload>}` — mirrors the
/// shared `envelope(...)` builder.
String _commandEnvelope(String action, Object? payload) =>
    jsonEncode({'type': 'command', 'action': action, 'payload': payload});

/// Proto payloads are serialized via the generated proto3-JSON encoder so the
/// field names match the `.proto` contract both TVs were generated from.
String senderPlaylistCommandJson(PlaylistPayload payload) =>
    _commandEnvelope('playlist', payload.toProto3Json());

/// A single video is sent as a one-item playlist — there is no standalone
/// `play` command; the TV always builds a queue so `queue_add` can append.
String senderSingleVideoCommandJson(PlayPayload video) =>
    senderPlaylistCommandJson(PlaylistPayload(items: [video]));

String senderQueueAddJson(PlayPayload item) =>
    _commandEnvelope('queue_add', {'item': item.toProto3Json()});

String senderPlaylistJumpJson(int index) =>
    _commandEnvelope('playlist_jump', {'index': index});

String senderControlCommandJson(String command) =>
    _commandEnvelope('control', {'command': command});

String senderContextQueryJson() =>
    jsonEncode({'type': 'command', 'action': 'context_query'});
