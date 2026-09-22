import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart' as rust;

import 'cert_manager.dart';
import 'media_kind.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'protocol.dart';
import 'screen_mirror_receiver.dart';
import 'system_volume.dart';
import 'extension_request_debug_log.dart';

const int kDefaultPort = PairingStore.defaultReceiverPort;

@visibleForTesting
String queueAddFailureError({
  required String? startingPlaybackId,
  required String? currentPlaybackId,
  required int queueLength,
  required int itemCount,
}) {
  if (currentPlaybackId == null) return 'no_active_playback';
  if (currentPlaybackId != startingPlaybackId) return 'stale_playback';
  if (queueLength + itemCount > PlayerController.maxQueueItems) {
    return 'queue_full';
  }
  return 'invalid_command';
}

enum PairingPhase {
  idle,
  awaitingApproval,
  awaitingCode,
  authenticated,
}

class PendingPairingRequest {
  const PendingPairingRequest({
    required this.connectionId,
    required this.deviceName,
    required this.deviceUUID,
    required this.sasCode,
  });

  final int connectionId;
  final String deviceName;
  final String deviceUUID;
  final String sasCode;
}

/// Desktop adapter for the shared Rust PlayBridge receiver runtime.
///
/// Rust owns TLS/WSS, pairing, authentication, limits and command decoding.
/// Dart retains application lifecycle, UI and playback.
class ReceiverServer extends ChangeNotifier {
  ReceiverServer({
    required this.player,
    required this.store,
    this.isPlaybackPromptActive,
    this.onPromptContinue,
    this.onPromptStop,
    this.onPlaybackActivity,
    this.onNewMedia,
    this.onScreenMirrorStarted,
  }) {
    screenMirror = ScreenMirrorReceiver(send: _sendScreenMirrorMessage);
    screenMirror.addListener(_handleScreenMirrorChange);
  }

  final PlayerController player;
  final PairingStore store;
  final bool Function()? isPlaybackPromptActive;
  final VoidCallback? onPromptContinue;
  final VoidCallback? onPromptStop;
  final VoidCallback? onPlaybackActivity;
  final VoidCallback? onNewMedia;
  final VoidCallback? onScreenMirrorStarted;
  late final ScreenMirrorReceiver screenMirror;

  rust.ReceiverRuntime? _runtime;
  StreamSubscription<Map<String, Object?>>? _eventsSubscription;
  Timer? _statusTimer;
  bool _disposed = false;
  bool _pairingInProgress = false;
  int _connectedClientCount = 0;
  int _authedClientCount = 0;
  PendingPairingRequest? _pendingPairingRequest;
  int? _wssPort;
  String? tlsError;
  bool _mirrorWasActive = false;

  int? get wssPort => _wssPort;
  int get connectedClientCount => _connectedClientCount;
  int get authedClientCount => _authedClientCount;
  PendingPairingRequest? get pendingPairingRequest => _pendingPairingRequest;

  PairingPhase get phase {
    if (_pendingPairingRequest != null) return PairingPhase.awaitingCode;
    if (_authedClientCount > 0) return PairingPhase.authenticated;
    if (_pairingInProgress) return PairingPhase.awaitingApproval;
    return PairingPhase.idle;
  }

  Future<int> start({int? port}) async {
    if (_runtime != null) return _wssPort ?? kDefaultPort;
    final cert = await CertManager.loadOrCreate(commonName: store.deviceName);
    final runtime = rust.ReceiverRuntime.start(
      rust.ReceiverRuntimeConfig(
        name: store.deviceName,
        uuid: store.deviceId,
        certificateDer: cert.certificateDer,
        privateKeyDer: cert.privateKeyDer,
        privateKeyKind: cert.privateKeyKind,
        preferredPort: port ?? store.receiverPort,
        authorizedTokens: [
          for (final device in store.pairedDevices) device.token,
        ],
        players: const ['internal_mpv'],
        mediaKinds: const ['video', 'audio', 'image'],
        features: const [
          'queue_crud_v1',
          'stable_item_ids',
          'command_results',
        ],
        screenMirrorWebRtc: true,
      ),
    );
    _runtime = runtime;
    _eventsSubscription = runtime.events.listen(
      _handleRuntimeEvent,
      onError: (Object error, StackTrace stackTrace) {
        debugPrint('[server] Rust receiver event error: $error');
      },
    );
    try {
      final boundPort =
          await runtime.started.timeout(const Duration(seconds: 15));
      _wssPort = boundPort;
      await store.setReceiverPort(boundPort);
      tlsError = null;
      debugPrint(
        '[server] Rust WSS receiver listening on 0.0.0.0:$boundPort '
        '(pin ${cert.fingerprint})',
      );
      player.addListener(_broadcastStatus);
      player.indexChanges.addListener(_broadcastPlaylistStatus);
      player.queueChanges.addListener(_broadcastPlaylistStatus);
      _statusTimer = Timer.periodic(
        const Duration(milliseconds: 500),
        (_) => _broadcastStatus(),
      );
      notifyListeners();
      return boundPort;
    } catch (error) {
      tlsError = 'Secure server failed to start';
      await stop();
      rethrow;
    }
  }

  void _handleRuntimeEvent(Map<String, Object?> event) {
    switch (event['event']) {
      case 'client_count':
        _connectedClientCount = event['total']! as int;
        _authedClientCount = event['authenticated']! as int;
        if (_connectedClientCount == 0) {
          _pairingInProgress = false;
          _pendingPairingRequest = null;
          unawaited(screenMirror.stopForReplacement(
            reason: 'sender_disconnected',
            notifySender: false,
          ));
        }
        notifyListeners();
      case 'client_disconnected':
        final connectionId = event['connection_id'];
        if (connectionId is int) {
          screenMirror.connectionClosed(connectionId);
        }
      case 'pairing_started':
        _pairingInProgress = true;
        notifyListeners();
      case 'pairing_requested':
        _pairingInProgress = true;
        _pendingPairingRequest = PendingPairingRequest(
          connectionId: event['connection_id']! as int,
          deviceName: event['device_name']! as String,
          deviceUUID: event['device_uuid']! as String,
          sasCode: event['sas_code']! as String,
        );
        notifyListeners();
      case 'paired':
        final token = event['token']! as String;
        unawaited(store.addPairedDevice(PairedDeviceRecord(
          deviceUUID: event['device_uuid']! as String,
          deviceName: event['device_name']! as String,
          token: token,
          lastConnected: DateTime.now(),
        )));
        _pairingInProgress = false;
        _pendingPairingRequest = null;
        notifyListeners();
      case 'authenticated':
        final digest = event['token_digest']! as String;
        unawaited(store.updateLastConnectedDigest(digest));
      case 'command':
        final raw = event['raw'];
        final connectionId = event['connection_id'];
        if (raw is String && connectionId is int) {
          final requestId = event['request_id'] as String?;
          _commandSerial = _commandSerial.then((_) async {
            try {
              await _handleCommand(parseCommand(raw), connectionId, requestId);
            } catch (error) {
              debugPrint('[server] command failed: $error');
              _completeCommand(connectionId, requestId,
                  ok: false, error: 'invalid_command');
            }
          }).catchError((Object error, StackTrace stackTrace) {
            debugPrint('[server] command failed: $error');
          });
        }
      case 'error':
        debugPrint('[server] Rust receiver: ${event['message']}');
      case 'finished':
        _connectedClientCount = 0;
        _authedClientCount = 0;
        _pairingInProgress = false;
        _pendingPairingRequest = null;
        unawaited(screenMirror.stopForReplacement(
          reason: 'receiver_stopped',
          notifySender: false,
        ));
        notifyListeners();
    }
  }

  Future<void> stop() async {
    if (_disposed) return;
    _disposed = true;
    _statusTimer?.cancel();
    player.removeListener(_broadcastStatus);
    player.indexChanges.removeListener(_broadcastPlaylistStatus);
    player.queueChanges.removeListener(_broadcastPlaylistStatus);
    await _eventsSubscription?.cancel();
    _eventsSubscription = null;
    screenMirror.removeListener(_handleScreenMirrorChange);
    await screenMirror.disposeReceiver();
    _runtime?.dispose();
    _runtime = null;
    _wssPort = null;
    _connectedClientCount = 0;
    _authedClientCount = 0;
  }

  Future<void> kickAll() async => _runtime?.disconnectAll();

  void refreshAuthorizedTokens() {
    _runtime?.replaceAuthorizedTokens(
      store.pairedDevices.map((device) => device.token),
    );
  }

  void denyPairing() {
    final request = _pendingPairingRequest;
    if (request == null) return;
    _runtime?.denyPairing(request.connectionId);
    _pendingPairingRequest = null;
    _pairingInProgress = false;
    notifyListeners();
  }

  void broadcastIdleContext() {
    _runtime?.broadcast(const {'type': 'context', 'active': 'idle'});
  }

  Future<void> _commandSerial = Future<void>.value();
  final Map<String, Map<String, Object?>> _commandResults = {};

  Future<void> _handleCommand(
    Command cmd,
    int connectionId,
    String? requestId,
  ) async {
    final cacheKey = requestId;
    final cached = cacheKey == null ? null : _commandResults[cacheKey];
    if (cached != null) {
      _runtime?.sendTo(connectionId, cached);
      return;
    }
    switch (cmd) {
      case ContextQueryCmd():
        _runtime?.sendTo(connectionId, {
          'type': 'context',
          'active': screenMirror.isActive
              ? 'screen_mirror'
              : player.state == 'idle'
                  ? 'idle'
                  : 'player',
        });
        _sendStatus(connectionId);
        _sendPlaylistStatus(connectionId);
        _broadcastTracksIfChanged(force: true);
      case PlaylistCmd(:final items, :final startIndex, :final skipPreplay):
        unawaited(
          screenMirror.stopForReplacement(reason: 'media_started'),
        );
        onNewMedia?.call();
        await player.playPlaylist(
          items
              .map((item) =>
                  receiverQueueItemFromPayload(item, skipPreplay: skipPreplay))
              .toList(),
          startIndex,
          isRemote: true,
        );
        _broadcastPlaylistStatus();
      case PlaylistJumpCmd(:final index, :final itemId, :final ifPlaybackId):
        if (requestId != null && player.playbackId == null) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'no_active_playback');
          return;
        }
        if (!_playbackMatches(ifPlaybackId)) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'stale_playback');
          return;
        }
        onNewMedia?.call();
        var found = false;
        if (itemId != null) {
          found = await player.jumpToItem(itemId, ifPlaybackId: ifPlaybackId);
        } else if (index != null && index >= 0 && index < player.queue.length) {
          found = await player.jumpToGuarded(index, ifPlaybackId: ifPlaybackId);
        }
        final jumpError = !found && !_playbackMatches(ifPlaybackId)
            ? 'stale_playback'
            : 'item_not_found';
        _completeCommand(connectionId, requestId,
            ok: found, error: found ? null : jumpError);
        _sendPlaylistStatus(connectionId);
      case QueueAddCmd(:final items, :final ifPlaybackId):
        if (requestId != null && player.playbackId == null) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'no_active_playback');
          return;
        }
        if (!_playbackMatches(ifPlaybackId)) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'stale_playback');
          return;
        }
        if (items.length > PlayerController.maxQueueBatchItems) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'invalid_command');
          return;
        }
        if (player.queue.length + items.length >
            PlayerController.maxQueueItems) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'queue_full');
          return;
        }
        if (isPlaybackPromptActive?.call() ?? false) {
          onPromptContinue?.call();
        } else {
          onPlaybackActivity?.call();
        }
        final startingPlaybackId = player.playbackId;
        final added = await player.queueAddAll(
          items.map(receiverQueueItemFromPayload).toList(growable: false),
          isRemote: true,
          ifPlaybackId: ifPlaybackId,
        );
        final addError = added
            ? null
            : queueAddFailureError(
                startingPlaybackId: startingPlaybackId,
                currentPlaybackId: player.playbackId,
                queueLength: player.queue.length,
                itemCount: items.length,
              );
        _completeCommand(connectionId, requestId, ok: added, error: addError);
        _sendPlaylistStatus(connectionId);
      case QueueQueryCmd():
        _sendPlaylistStatus(connectionId);
        _completeCommand(connectionId, requestId, ok: true);
      case QueueRemoveCmd(:final itemIds, :final ifPlaybackId):
        if (player.playbackId == null) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'no_active_playback');
          return;
        }
        if (!_playbackMatches(ifPlaybackId)) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'stale_playback');
          return;
        }
        final removed = await player.removeQueueItems(itemIds.toSet(),
            ifPlaybackId: ifPlaybackId);
        final removeError = !removed && !_playbackMatches(ifPlaybackId)
            ? 'stale_playback'
            : 'item_not_found';
        _completeCommand(connectionId, requestId,
            ok: removed, error: removed ? null : removeError);
        _sendPlaylistStatus(connectionId);
      case QueueMoveCmd(
          :final itemId,
          :final beforeItemId,
          :final ifPlaybackId
        ):
        if (player.playbackId == null) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'no_active_playback');
          return;
        }
        if (!_playbackMatches(ifPlaybackId)) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'stale_playback');
          return;
        }
        final moved = await player.moveQueueItem(itemId, beforeItemId,
            ifPlaybackId: ifPlaybackId);
        final moveError = !moved && !_playbackMatches(ifPlaybackId)
            ? 'stale_playback'
            : 'item_not_found';
        _completeCommand(connectionId, requestId,
            ok: moved, error: moved ? null : moveError);
        _sendPlaylistStatus(connectionId);
      case QueueClearCmd(:final ifPlaybackId):
        if (player.playbackId == null) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'no_active_playback');
          return;
        }
        if (!_playbackMatches(ifPlaybackId)) {
          _completeCommand(connectionId, requestId,
              ok: false, error: 'stale_playback');
          return;
        }
        await player.stop();
        _completeCommand(connectionId, requestId, ok: true);
        _sendPlaylistStatus(connectionId);
      case ScreenMirrorStartCmd():
        onNewMedia?.call();
        unawaited(player.stop());
        onScreenMirrorStarted?.call();
        screenMirror.start(cmd, connectionId);
      case ScreenMirrorOfferCmd():
        screenMirror.applyOffer(cmd, connectionId);
      case ScreenMirrorCandidateCmd():
        screenMirror.addCandidate(cmd, connectionId);
      case ScreenMirrorStopCmd():
        screenMirror.stop(cmd, connectionId);
      case ControlCmd(:final command):
        _handleControl(command);
      case RemoteCmd(:final key):
        if (isPlaybackPromptActive?.call() ?? false) {
          onPromptContinue?.call();
        } else {
          onPlaybackActivity?.call();
        }
        _handleRemoteKey(key);
      case MouseCmd(:final event, :final dx, :final dy):
        if (player.currentMediaKind == MediaKind.image) {
          switch (event) {
            case 'move' || 'scroll':
              player.panImage(dx, dy);
            case 'zoom':
              player.zoomImage(dx);
            case 'transform_anchor':
              player.setImageTransformAnchor(dx, dy);
            case 'reset':
              player.resetImageTransform();
            case 'rotate':
              player.rotateImage(dx);
          }
        }
      case UnknownCmd():
        _completeCommand(connectionId, requestId,
            ok: false, error: 'invalid_command');
      default:
        break;
    }
  }

  bool _playbackMatches(String? expected) =>
      expected == null || expected == player.playbackId;

  void _completeCommand(
    int connectionId,
    String? requestId, {
    required bool ok,
    String? error,
  }) {
    if (requestId == null) return;
    final result = <String, Object?>{
      'type': 'command_result',
      'requestId': requestId,
      'ok': ok,
      if (error != null) 'error': error,
      if (player.playbackId != null) 'playbackId': player.playbackId,
      'queueRevision': player.queueRevision,
    };
    _commandResults[requestId] = result;
    if (_commandResults.length > 256) {
      _commandResults.remove(_commandResults.keys.first);
    }
    _runtime?.sendTo(connectionId, result);
  }

  void _sendScreenMirrorMessage(
    int connectionId,
    Map<String, Object?> message,
  ) {
    _runtime?.sendTo(connectionId, message);
  }

  void _handleScreenMirrorChange() {
    final active = screenMirror.isActive;
    if (active != _mirrorWasActive) {
      _mirrorWasActive = active;
      _runtime?.broadcast({
        'type': 'context',
        'active': active
            ? 'screen_mirror'
            : player.state == 'idle'
                ? 'idle'
                : 'player',
      });
    }
    notifyListeners();
  }

  void _handleControl(String command) {
    if (isPlaybackPromptActive?.call() ?? false) {
      if (command == 'play') {
        onPromptContinue?.call();
      } else if (command == 'stop') {
        onPromptStop?.call();
      } else {
        onPromptContinue?.call();
      }
      return;
    }
    onPlaybackActivity?.call();
    if (command.startsWith('seek_to:')) {
      final milliseconds = int.tryParse(command.substring('seek_to:'.length));
      if (milliseconds != null) {
        final duration = player.durationMs;
        final target = milliseconds
            .clamp(0, duration > 0 ? duration : milliseconds)
            .toInt();
        unawaited(player.seek(Duration(milliseconds: target)));
      }
      return;
    }
    if (command.startsWith('audio_track:')) {
      unawaited(
        player.selectAudioTrackById(command.substring('audio_track:'.length)),
      );
      return;
    }
    if (command.startsWith('sub_track:')) {
      unawaited(
        player.selectSubtitleTrackById(command.substring('sub_track:'.length)),
      );
      return;
    }
    switch (command) {
      case 'play':
        unawaited(player.resume());
      case 'pause':
        unawaited(player.pause());
      case 'toggle':
        unawaited(player.state == 'playing' ? player.pause() : player.resume());
      case 'stop':
        unawaited(player.stop());
        if (screenMirror.isActive) {
          unawaited(screenMirror.stopForReplacement(
            reason: 'stopped_by_remote',
          ));
        } else {
          broadcastIdleContext();
        }
      case 'seek_back':
        unawaited(player.seek(
          Duration(
            milliseconds: (player.positionMs - 10000).clamp(0, 1 << 62).toInt(),
          ),
        ));
      case 'seek_forward':
        final duration = player.durationMs;
        final target = (duration > 0
                ? (player.positionMs + 10000).clamp(0, duration)
                : player.positionMs + 10000)
            .toInt();
        unawaited(player.seek(Duration(milliseconds: target)));
    }
  }

  void _handleRemoteKey(String key) {
    switch (key) {
      case 'volume_up':
        unawaited(_adjustVolume(up: true));
      case 'volume_down':
        unawaited(_adjustVolume(up: false));
      default:
        debugPrint('[server] ignoring remote key: $key');
    }
  }

  bool _volumeBusy = false;

  Future<void> _adjustVolume({required bool up}) async {
    if (_volumeBusy) return;
    _volumeBusy = true;
    try {
      final handled = await SystemVolume.step(up: up);
      if (!handled) {
        const step = 0.05;
        final next =
            (player.volume + (up ? step : -step)).clamp(0.0, 1.0).toDouble();
        await player.setVolume(next);
      }
    } finally {
      _volumeBusy = false;
    }
  }

  void _broadcastStatus() {
    _runtime?.broadcast(_statusMessage());
    _broadcastTracksIfChanged();
  }

  Map<String, Object?> _statusMessage() => {
        'type': 'status',
        'state': player.state,
        'position': player.positionMs,
        'duration': player.durationMs,
        if (player.currentTitle != null) 'title': player.currentTitle,
        if (player.currentMediaKind != null)
          'mediaKind': player.currentMediaKind!.wireValue,
        if (player.playbackId != null) 'playbackId': player.playbackId,
        if (player.currentItemId != null) 'currentItemId': player.currentItemId,
      };

  void _sendStatus(int connectionId) =>
      _runtime?.sendTo(connectionId, _statusMessage());

  String? _lastTracksJson;

  void _broadcastTracksIfChanged({bool force = false}) {
    final exposeTracks = player.currentMediaKind != MediaKind.image;
    final message = <String, Object?>{
      'type': 'tracks',
      'audio': [
        if (exposeTracks)
          for (final track in player.audioTrackInfos)
            {
              'id': track.id,
              'name': track.name,
              'selected': track.selected,
            },
      ],
      'subtitle': [
        if (exposeTracks)
          for (final track in player.subtitleTrackInfos)
            {
              'id': track.id,
              'name': track.name,
              'selected': track.selected,
            },
      ],
    };
    final encoded = message.toString();
    if (!force && encoded == _lastTracksJson) return;
    _lastTracksJson = encoded;
    _runtime?.broadcast(message);
  }

  void _broadcastPlaylistStatus() {
    _runtime?.broadcast(_playlistStatusMessage());
  }

  void _sendPlaylistStatus(int connectionId) =>
      _runtime?.sendTo(connectionId, _playlistStatusMessage());

  Map<String, Object?> _playlistStatusMessage() => {
        'type': 'playlist_status',
        'items': [
          for (var index = 0; index < player.queue.length; index++)
            {
              'index': index,
              'itemId': player.queueItemIds[index],
              'title': player.queue[index].title,
              'mediaKind': player.queue[index].mediaKind.wireValue,
              if (player.queue[index].season != null)
                'season': player.queue[index].season,
              if (player.queue[index].episode != null)
                'episode': player.queue[index].episode,
              if (player.queue[index].imdbId != null)
                'imdbId': player.queue[index].imdbId,
              if (player.queue[index].tmdbId != null)
                'tmdbId': player.queue[index].tmdbId,
              if (player.queue[index].bingeGroup != null)
                'bingeGroup': player.queue[index].bingeGroup,
            },
        ],
        'currentIndex': player.queue.isEmpty
            ? 0
            : player.currentIndex.clamp(0, player.queue.length - 1).toInt(),
        'totalCount': player.queue.length,
        if (player.playbackId != null) 'playbackId': player.playbackId,
        if (player.currentItemId != null) 'currentItemId': player.currentItemId,
        'queueRevision': player.queueRevision,
      };
}

/// Convert wire media without losing per-cast history policy.
QueueItem receiverQueueItemFromPayload(PlayPayload payload,
    {bool skipPreplay = false}) {
  debugLogNetworkRequest(
    source: 'receiver',
    url: payload.url,
    headers: payload.headersOrNull,
  );
  return QueueItem(
    url: payload.url,
    title: payload.titleOrNull ?? payload.url,
    headers: payload.headersOrNull,
    subtitles: payload.subtitlesOrNull,
    subtitleResources: payload.subtitleResources
        .map((resource) => SubtitleRequest(
              url: resource.url,
              headers: Map.unmodifiable(resource.headers),
              label: resource.hasLabel() ? resource.label : null,
              language: resource.hasLanguage() ? resource.language : null,
            ))
        .toList(growable: false),
    contentType: payload.contentTypeOrNull,
    declaredMediaKind: payload.mediaKindOrNull,
    displayDurationMs: payload.displayDurationMsOrNull,
    artist: payload.artistOrNull,
    album: payload.albumOrNull,
    artworkUrl: payload.artworkUrlOrNull,
    skipPreplay: skipPreplay,
    skipHistory: payload.skipHistory,
    enforcePageNetworkPolicy: payload.detectedByOrNull == 'page_cast' ||
        payload.detectedByOrNull == 'linked_page',
    allowedPrivateOrigins: payload.allowedPrivateOrigins,
    startPositionMs: payload.startPositionMsOrNull,
    bingeGroup: payload.bingeGroupOrNull,
    season: payload.seasonOrNull,
    episode: payload.episodeOrNull,
    imdbId: payload.imdbIdOrNull,
    tmdbId: payload.tmdbIdOrNull,
    backdropUrl: payload.backdropUrlOrNull,
    posterUrl: payload.posterUrlOrNull,
    logoUrl: payload.logoUrlOrNull,
    overview: payload.overviewOrNull,
    year: payload.yearOrNull,
    rating: payload.ratingOrNull,
    runtime: payload.runtimeOrNull,
    episodeTitle: payload.episodeTitleOrNull,
  );
}
