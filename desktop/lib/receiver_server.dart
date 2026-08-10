import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:playbridge_cast_core/playbridge_cast_core.dart' as rust;

import 'cert_manager.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'protocol.dart';
import 'screen_mirror_receiver.dart';
import 'system_volume.dart';
import 'extension_request_debug_log.dart';

const int kDefaultPort = PairingStore.defaultReceiverPort;

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
          _handleCommand(parseCommand(raw), connectionId);
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

  void _handleCommand(Command cmd, int connectionId) {
    switch (cmd) {
      case ContextQueryCmd():
        _runtime?.broadcast({
          'type': 'context',
          'active': screenMirror.isActive
              ? 'screen_mirror'
              : player.state == 'idle'
                  ? 'idle'
                  : 'player',
        });
        _broadcastStatus();
        _broadcastPlaylistStatus();
        _broadcastTracksIfChanged(force: true);
      case PlaylistCmd(:final items, :final startIndex):
        unawaited(
          screenMirror.stopForReplacement(reason: 'media_started'),
        );
        onNewMedia?.call();
        unawaited(player.playPlaylist(
          items.map(_toQueueItem).toList(),
          startIndex,
          isRemote: true,
        ));
        _broadcastPlaylistStatus();
      case PlaylistJumpCmd(:final index):
        onNewMedia?.call();
        unawaited(player.jumpTo(index));
        _broadcastPlaylistStatus();
      case QueueAddCmd(:final item):
        if (isPlaybackPromptActive?.call() ?? false) {
          onPromptContinue?.call();
        } else {
          onPlaybackActivity?.call();
        }
        unawaited(player.queueAdd(_toQueueItem(item), isRemote: true));
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
      default:
        break;
    }
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

  QueueItem _toQueueItem(PlayPayload payload) {
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
      startPositionMs: payload.startPositionMsOrNull,
      bingeGroup: payload.bingeGroupOrNull,
      season: payload.seasonOrNull,
      episode: payload.episodeOrNull,
      imdbId: payload.imdbIdOrNull,
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

  void _broadcastStatus() {
    _runtime?.broadcast({
      'type': 'status',
      'state': player.state,
      'position': player.positionMs,
      'duration': player.durationMs,
      if (player.currentTitle != null) 'title': player.currentTitle,
    });
    _broadcastTracksIfChanged();
  }

  String? _lastTracksJson;

  void _broadcastTracksIfChanged({bool force = false}) {
    final message = <String, Object?>{
      'type': 'tracks',
      'audio': [
        for (final track in player.audioTrackInfos)
          {
            'id': track.id,
            'name': track.name,
            'selected': track.selected,
          },
      ],
      'subtitle': [
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
    _runtime?.broadcast({
      'type': 'playlist_status',
      'items': [
        for (var index = 0; index < player.queue.length; index++)
          {
            'index': index,
            'title': player.queue[index].title,
            if (player.queue[index].season != null)
              'season': player.queue[index].season,
            if (player.queue[index].episode != null)
              'episode': player.queue[index].episode,
            if (player.queue[index].imdbId != null)
              'imdbId': player.queue[index].imdbId,
            if (player.queue[index].bingeGroup != null)
              'bingeGroup': player.queue[index].bingeGroup,
          },
      ],
      'currentIndex': player.queue.isEmpty
          ? 0
          : player.currentIndex.clamp(0, player.queue.length - 1).toInt(),
      'totalCount': player.queue.length,
    });
  }
}
