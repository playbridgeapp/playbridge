import 'dart:async';
import 'dart:convert';
import 'dart:io';

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
import 'remote_volume_commands.dart';
import 'extension_request_debug_log.dart';
import 'late_subtitle_loader.dart';
import 'progress_webhook.dart';
import 'receiver_capabilities.dart';
import 'remote_player_settings.dart';
import 'remote_timeline.dart';
import 'video_scaling.dart';

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
    this.onRemoteVolumeFeedback,
  }) {
    player.onTerminalError = _handlePlaybackError;
    screenMirror = ScreenMirrorReceiver(send: _sendScreenMirrorMessage);
    screenMirror.addListener(_handleScreenMirrorChange);
  }

  @visibleForTesting
  void Function(Map<String, Object?> message)? onBroadcastForTest;

  @visibleForTesting
  void Function(int connectionId, Map<String, Object?> message)? onSendForTest;

  @visibleForTesting
  Future<void> handleCommandForTest(Command command, {int connectionId = 1}) =>
      _handleCommand(command, connectionId, null);

  @visibleForTesting
  void attachPlayerListenersForTest() {
    player.addListener(_broadcastStatus);
    player.indexChanges.addListener(_broadcastPlaylistStatus);
    player.queueChanges.addListener(_broadcastPlaylistStatus);
  }

  void _broadcast(Map<String, Object?> message) {
    onBroadcastForTest?.call(message);
    _runtime?.broadcast(message);
  }

  final PlayerController player;
  final PairingStore store;
  final bool Function()? isPlaybackPromptActive;
  final VoidCallback? onPromptContinue;
  final VoidCallback? onPromptStop;
  final VoidCallback? onPlaybackActivity;
  final VoidCallback? onNewMedia;
  final VoidCallback? onScreenMirrorStarted;
  final void Function(String)? onRemoteVolumeFeedback;
  late final _volumeCommands = RemoteVolumeCommands(_applyVolumeChange);
  StreamSubscription<SystemVolumeState?>? _volumeEvents;
  late final ScreenMirrorReceiver screenMirror;

  rust.ReceiverRuntime? _runtime;
  StreamSubscription<Map<String, Object?>>? _eventsSubscription;
  Timer? _statusTimer;
  Timer? _volumeTimer;
  Future<SystemVolumeState?>? _volumeRead;
  final _volumePublication = VolumeStatePublication();
  int _volumeEpoch = 0;
  bool _usingAppVolume = false;
  bool _macVolumeAvailable = false;
  int _capabilitiesRevision = 1;
  final _capabilitySubscribers = <int>{};
  Map<String, Object?> get _currentCapabilities =>
      buildDesktopReceiverCapabilities(macNativeVolume: _macVolumeAvailable);
  void _sendCapabilities(int connectionId) => _runtime?.sendTo(connectionId, {
        'type': 'receiver_capabilities',
        'revision': _capabilitiesRevision,
        'capabilities': _currentCapabilities,
      });
  void _updateVolumeCapabilities() {
    if (_macVolumeAvailable == SystemVolume.macNativeAvailable) return;
    _macVolumeAvailable = SystemVolume.macNativeAvailable;
    if (_macVolumeAvailable) _usingAppVolume = false;
    _capabilitiesRevision++;
    _runtime?.replaceCapabilities(_currentCapabilities);
    for (final id in _capabilitySubscribers) {
      _sendCapabilities(id);
    }
  }

  final _progressReporter = ProgressWebhookReporter();
  StreamSubscription<PlaybackBoundary>? _playbackBoundaries;
  bool _disposed = false;
  bool _pairingInProgress = false;
  int _connectedClientCount = 0;
  int _authedClientCount = 0;
  PendingPairingRequest? _pendingPairingRequest;
  int? _wssPort;
  String? tlsError;
  bool _mirrorWasActive = false;
  // Last player context sent to senders. Local stops, the end of the queue and
  // closing the player never pass through a sender command, so the player's own
  // idle transitions are broadcast too (Android TV sends context on its player
  // activity lifecycle).
  late bool _playerWasIdle = player.state == 'idle';

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
    if (SystemVolume.usesEvents) await SystemVolume.read();
    _macVolumeAvailable = SystemVolume.macNativeAvailable;
    final cert = await CertManager.loadOrCreate(commonName: store.deviceName);
    final runtime = rust.ReceiverRuntime.start(
      buildDesktopReceiverRuntimeConfig(
        macNativeVolume: SystemVolume.macNativeAvailable,
        name: store.deviceName,
        uuid: store.deviceId,
        certificateDer: cert.certificateDer,
        privateKeyDer: cert.privateKeyDer,
        privateKeyKind: cert.privateKeyKind,
        preferredPort: port ?? store.receiverPort,
        authorizedTokens: [
          for (final device in store.pairedDevices) device.token,
        ],
      ),
    );
    _runtime = runtime;
    if (SystemVolume.usesEvents) {
      _volumeEvents = SystemVolume.changes.listen((state) {
        _volumeEpoch++;
        _volumeRead = null;
        _updateVolumeCapabilities();
        if (!_usingAppVolume && state != null) _sendVolumeFrame(state.frame());
      }, onError: (Object _) {});
    }
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
        '[server] Rust WSS receiver listening on 0.0.0.0:$boundPort',
      );
      _playbackBoundaries = player.playbackBoundaries.stream.listen((boundary) {
        _progressReporter.terminal(boundary.event,
            sessionEnded: boundary.sessionEnded, snapshot: _progressSnapshot());
      });
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
        if (_authedClientCount > 0) {
          if (!SystemVolume.usesEvents) {
            _volumeTimer ??= Timer.periodic(
                const Duration(seconds: 2), (_) => unawaited(_publishVolume()));
          }
        } else {
          _volumeTimer?.cancel();
          _volumeTimer = null;
          _volumePublication.reset();
          _capabilitySubscribers.clear();
          _volumeEpoch++;
          _volumeRead = null;
        }
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
          _capabilitySubscribers.remove(connectionId);
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
        _sendPlayerSettings(event['connection_id']! as int);
        unawaited(_publishVolume(connectionId: event['connection_id']! as int));
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
        _sendPlayerSettings(event['connection_id']! as int);
        unawaited(_publishVolume(connectionId: event['connection_id']! as int));
        final digest = event['token_digest']! as String;
        unawaited(store.updateLastConnectedDigest(digest));
      case 'command':
        final raw = event['raw'];
        final connectionId = event['connection_id'];
        if (raw is String && connectionId is int) {
          final requestId = event['request_id'] as String?;
          final command = parseCommand(raw);
          if (command is VolumeCmd ||
              command is RemoteCmd &&
                  const {'volume_up', 'volume_down', 'mute'}
                      .contains(command.key)) {
            unawaited(_handleCommand(command, connectionId, requestId)
                .catchError((Object _) {
              _completeCommand(connectionId, requestId,
                  ok: false, error: 'invalid_command');
            }));
            return;
          }
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
        _volumeTimer?.cancel();
        _volumeTimer = null;
        _volumeEpoch++;
        _volumeRead = null;
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
    _progressReporter.terminal('stopped',
        sessionEnded: true, snapshot: _progressSnapshot());
    await _playbackBoundaries?.cancel();
    _statusTimer?.cancel();
    _volumeTimer?.cancel();
    await _volumeEvents?.cancel();
    _volumeCommands.close();
    if (player.onTerminalError == _handlePlaybackError) {
      player.onTerminalError = null;
    }
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
    _broadcast(const {'type': 'context', 'active': 'idle'});
  }

  Future<void> _commandSerial = Future<void>.value();
  final Map<String, Map<String, Object?>> _commandResults = {};
  final Set<String> _pendingLateSubtitleIds = {};
  int _lateSubtitleGeneration = 0;

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
        _sendPlayerSettings(connectionId);
        _sendTimeline(connectionId);
        await _publishVolume(connectionId: connectionId);
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
        _progressReporter.configure(cmd.progressWebhook);
        _progressReporter.update(_progressSnapshot());
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
      case ControlCmd(:final command, :final subtitleResource):
        if (command == 'add_subtitle' || command.startsWith('add_subtitle:')) {
          final resource = subtitleResource != null
              ? SubtitleRequest(
                  url: subtitleResource.url,
                  headers: Map<String, String>.from(subtitleResource.headers),
                  label: subtitleResource.hasLabel()
                      ? subtitleResource.label
                      : null,
                  language: subtitleResource.hasLanguage()
                      ? subtitleResource.language
                      : null,
                )
              : command.startsWith('add_subtitle:')
                  ? SubtitleRequest(
                      url: command.substring('add_subtitle:'.length))
                  : null;
          if (resource == null || !validLateSubtitleResource(resource)) {
            _completeCommand(connectionId, requestId,
                ok: false, error: 'invalid_command');
            return;
          }
          if (player.playbackId == null ||
              player.currentMediaKind != MediaKind.video ||
              player.currentIndex < 0 ||
              player.currentIndex >= player.queue.length) {
            _completeCommand(connectionId, requestId,
                ok: false, error: 'no_active_playback');
            return;
          }
          final playbackId = player.playbackId;
          final itemId = player.currentItemId;
          final item = player.queue[player.currentIndex];
          if (requestId != null && !_pendingLateSubtitleIds.add(requestId)) {
            return;
          }
          final generation = ++_lateSubtitleGeneration;
          // Network I/O must not hold the receiver's serial command queue: Stop or a
          // replacement Play command needs to run while a sidecar is downloading.
          unawaited(() async {
            File? file;
            try {
              file = await downloadLateSubtitle(
                resource,
                allowedPrivateOrigins: item.proxyAllowedPrivateOrigins,
              );
              if (player.playbackId != playbackId ||
                  player.currentItemId != itemId ||
                  _lateSubtitleGeneration != generation) {
                _completeCommand(connectionId, requestId,
                    ok: false,
                    error: _lateSubtitleGeneration != generation
                        ? 'subtitle_unavailable'
                        : 'stale_playback');
                return;
              }
              final stagedFile = file;
              final attachment = Completer<bool>();
              _commandSerial = _commandSerial.then((_) async {
                try {
                  if (player.playbackId != playbackId ||
                      player.currentItemId != itemId ||
                      _lateSubtitleGeneration != generation) {
                    attachment.complete(false);
                    return;
                  }
                  attachment.complete(await player.addExternalSubtitleFile(
                    stagedFile.path,
                    resource.label?.trim().isNotEmpty == true
                        ? resource.label!.trim()
                        : 'External subtitle',
                  ));
                } catch (_) {
                  attachment.complete(false);
                }
              });
              final attached = await attachment.future;
              if (player.playbackId != playbackId ||
                  player.currentItemId != itemId ||
                  _lateSubtitleGeneration != generation) {
                _completeCommand(connectionId, requestId,
                    ok: false,
                    error: _lateSubtitleGeneration != generation
                        ? 'subtitle_unavailable'
                        : 'stale_playback');
                return;
              }
              if (attached) file = null; // The player now owns the staged file.
              _completeCommand(connectionId, requestId,
                  ok: attached,
                  error: attached ? null : 'subtitle_unavailable');
              _broadcastTracksIfChanged(force: true);
            } catch (_) {
              _completeCommand(connectionId, requestId,
                  ok: false, error: 'subtitle_unavailable');
            } finally {
              if (requestId != null) _pendingLateSubtitleIds.remove(requestId);
              if (file != null) {
                try {
                  await file.parent.delete(recursive: true);
                } catch (e) {
                  debugPrint(
                      '[receiver] subtitle temp cleanup failed: ${e.runtimeType}');
                }
              }
            }
          }());
        } else {
          await _handleControl(command);
        }
      case RemoteCmd(:final key):
        if (isPlaybackPromptActive?.call() ?? false) {
          onPromptContinue?.call();
        } else {
          onPlaybackActivity?.call();
        }
        await _handleRemoteKey(key);
      case VolumeCmd(:final scope, :final level):
        if (scope == 'system') {
          await _volumeCommands.add(RemoteVolumeChange(level: level));
        }
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
      case UnknownCmd(:final type):
        if (type == 'receiver_capabilities_subscribe') {
          _capabilitySubscribers.add(connectionId);
          _sendCapabilities(connectionId);
          return;
        }
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
      _broadcast({
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

  Future<void> _handleControl(String command) async {
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
    final setting = parseRemotePlayerSetting(command);
    if (setting != null) {
      if (player.currentMediaKind == null ||
          player.currentMediaKind == MediaKind.image) {
        return;
      }
      switch (setting) {
        case RemotePlaybackSpeed(:final rate):
          await player.setPlaybackRate(rate);
        case RemoteSubtitleOffset(:final deltaMs):
          await player.adjustSubtitleDelayMs(deltaMs);
        case RemoteLoop(:final enabled):
          await player.setLooping(enabled);
        case RemoteScaling(:final mode):
          await player.setVideoScaling(mode);
      }
      return;
    }
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

  Future<void> _handleRemoteKey(String key) async {
    switch (key) {
      case 'volume_up':
        await _adjustVolume(up: true);
      case 'volume_down':
        await _adjustVolume(up: false);
      case 'mute':
        await _volumeCommands.add(const RemoteVolumeChange(mute: true));
      default:
        debugPrint('[server] ignoring unsupported remote key');
    }
  }

  Future<void> _adjustVolume({required bool up}) =>
      _volumeCommands.add(RemoteVolumeChange(steps: up ? 1 : -1));

  Future<void> _applyVolumeChange(RemoteVolumeChange change) async {
    if (_disposed) return;
    _volumeEpoch++;
    _volumeRead = null;
    Map<String, Object?>? frame;
    if (SystemVolume.usesEvents) {
      final state = await SystemVolume.applyMac(
          steps: change.steps, level: change.level, mute: change.mute);
      if (state != null) {
        _usingAppVolume = false;
        frame = state.frame();
      }
    } else {
      var handled = false;
      if (change.mute) {
        handled = await SystemVolume.toggleMute();
      } else if (change.level != null) {
        handled = await SystemVolume.setLevel(change.level!);
      } else {
        handled = true;
        for (var i = 0; i < change.steps.abs(); i++) {
          if (!await SystemVolume.step(up: change.steps > 0)) {
            handled = false;
            break;
          }
        }
      }
      if (handled) {
        _usingAppVolume = false;
        frame = (await SystemVolume.read())?.frame();
      }
    }
    if (frame == null && !change.mute && change.level == null) {
      _usingAppVolume = true;
      final next =
          (player.volume + change.steps * .05).clamp(0.0, 1.0).toDouble();
      await player.setVolume(next);
      frame = {'type': 'volume_state', 'scope': 'app', 'level': next};
    }
    if (frame != null && !_disposed) {
      _sendVolumeFrame(frame);
      final feedback = remoteVolumeFeedback(frame);
      if (feedback != null) onRemoteVolumeFeedback?.call(feedback);
    }
  }

  void _sendVolumeFrame(Map<String, Object?> frame) {
    if (_disposed || _authedClientCount == 0) return;
    final changed = _volumePublication.changed(frame);
    if (changed != null) _broadcast(changed);
  }

  Future<void> _publishVolume({int? connectionId}) async {
    if (_disposed || (connectionId == null && _authedClientCount == 0)) return;
    final epoch = _volumeEpoch;
    Map<String, Object?>? frame;
    if (_usingAppVolume) {
      frame = {
        'type': 'volume_state',
        'scope': 'app',
        'level': player.volume.clamp(0.0, 1.0)
      };
    } else {
      final read = _volumeRead ??= SystemVolume.read();
      final state = await read;
      if (SystemVolume.usesEvents) _updateVolumeCapabilities();
      if (identical(_volumeRead, read)) _volumeRead = null;
      frame = state?.frame();
    }
    if (_disposed ||
        epoch != _volumeEpoch ||
        frame == null ||
        (connectionId == null && _authedClientCount == 0)) {
      return;
    }
    if (connectionId != null) {
      _runtime?.sendTo(connectionId, _volumePublication.sync(frame));
    } else {
      final changed = _volumePublication.changed(frame);
      if (changed != null) _broadcast(changed);
    }
  }

  ProgressSnapshot? _progressSnapshot() {
    final index = player.currentIndex;
    if (index < 0 ||
        index >= player.queue.length ||
        player.playbackId == null ||
        player.currentItemId == null) {
      return null;
    }
    final identity = player.queue[index].progressIdentity;
    if (identity == null) return null;
    return ProgressSnapshot(
        playbackId: player.playbackId!,
        itemId: player.currentItemId!,
        content: identity,
        state: player.state,
        positionMs: player.positionMs,
        durationMs: player.durationMs);
  }

  void _handlePlaybackError(PlaybackFailure failure) {
    _broadcast({
      'type': 'status',
      'state': 'error',
      'position': failure.positionMs,
      'duration': failure.durationMs,
      if (failure.title != null) 'title': failure.title,
      if (failure.mediaKind != null) 'mediaKind': failure.mediaKind!.wireValue,
      if (failure.playbackId != null) 'playbackId': failure.playbackId,
      if (failure.currentItemId != null) 'currentItemId': failure.currentItemId,
      'error': failure.error,
    });
  }

  void _broadcastStatus() {
    if (_usingAppVolume) {
      _sendVolumeFrame({
        'type': 'volume_state',
        'scope': 'app',
        'level': player.volume.clamp(0.0, 1.0)
      });
    }
    _broadcastPlayerContextIfChanged();
    _broadcastPlayerSettingsIfChanged();
    _broadcastTimelineIfChanged();
    if (player.hasTerminalError) return;
    if (!player.isOpening) _progressReporter.update(_progressSnapshot());
    _broadcast(_statusMessage());
    _broadcastTracksIfChanged();
  }

  void _broadcastPlayerContextIfChanged() {
    final idle = player.state == 'idle';
    if (idle == _playerWasIdle) return;
    _playerWasIdle = idle;
    // Screen mirroring owns the context while it runs; its handler reports the
    // player context when it ends.
    if (screenMirror.isActive) return;
    _broadcast({'type': 'context', 'active': idle ? 'idle' : 'player'});
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
        if (player.terminalError != null) 'error': player.terminalError,
        if (player.bufferedMs case final buffered?) 'buffered': buffered,
      };

  Map<String, Object?> _playerSettingsMessage() {
    final image = player.currentMediaKind == MediaKind.image;
    final active = player.currentMediaKind != null;
    return {
      'type': 'player_settings',
      'speed': player.playbackRate,
      'scaling': videoScalingId(player.videoScaling),
      'subtitleOffsetMs': player.subtitleDelayMs,
      'loop': player.looping,
      'engine': 'mpv',
      'audioBoost': false,
      'speedAvailable': active && !image,
      'scalingAvailable': active && !image,
      'audioBoostAvailable': false,
      'qualityAvailable': false,
      'qualityMaxHeight': 0,
      'currentVideoHeight': player.currentVideoHeight,
      'isLive': player.isLive,
      'isSeekable': player.isSeekable,
    };
  }

  Map<String, Object?>? _lastPlayerSettings;
  (String?, String?)? _lastSettingsItem;
  void _broadcastPlayerSettingsIfChanged() {
    if (player.isOpening) return;
    final message = _playerSettingsMessage();
    final item = (player.playbackId, player.currentItemId);
    if (_lastSettingsItem == item && mapEquals(_lastPlayerSettings, message)) {
      return;
    }
    _lastSettingsItem = item;
    _lastPlayerSettings = message;
    _broadcast(message);
  }

  void _sendPlayerSettings(int connectionId) {
    final message = _playerSettingsMessage();
    onSendForTest?.call(connectionId, message);
    _runtime?.sendTo(connectionId, message);
  }

  // The controller already withholds chapters while an item opens and binds
  // skip segments to their item, so every frame describes the current item.
  Map<String, Object?> _timelineMessage() => buildTimelineMessage(
        playbackId: player.playbackId,
        currentItemId: player.currentItemId,
        chapters: player.chapters,
        segments: player.skipSegments,
      );

  String? _lastTimelineJson;
  void _broadcastTimelineIfChanged() {
    final message = _timelineMessage();
    // The encoding carries playbackId/currentItemId, so a new item with the
    // same (often empty) timeline is still announced.
    final encoded = jsonEncode(message);
    if (encoded == _lastTimelineJson) return;
    _lastTimelineJson = encoded;
    _broadcast(message);
  }

  void _sendTimeline(int connectionId) {
    final message = _timelineMessage();
    onSendForTest?.call(connectionId, message);
    _runtime?.sendTo(connectionId, message);
  }

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
    _broadcast(message);
  }

  void _broadcastPlaylistStatus() {
    _broadcast(_playlistStatusMessage());
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
  if (payload.detectedByOrNull != 'linked_page' &&
      payload.detectedByOrNull != 'page_cast') {
    debugLogNetworkRequest(
      source: 'receiver',
      url: payload.url,
      headers: payload.headersOrNull,
    );
  }
  return QueueItem(
    url: payload.url,
    progressIdentity: payload.hasProgressIdentity()
        ? validProgressIdentity({
            'type': payload.progressIdentity.type,
            'contentId': payload.progressIdentity.contentId,
            'videoId': payload.progressIdentity.videoId,
            if (payload.progressIdentity.hasSeason())
              'season': payload.progressIdentity.season,
            if (payload.progressIdentity.hasEpisode())
              'episode': payload.progressIdentity.episode,
          })
        : null,
    title: payload.titleOrNull ??
        (payload.detectedByOrNull == 'linked_page'
            ? 'Website media'
            : payload.url),
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
