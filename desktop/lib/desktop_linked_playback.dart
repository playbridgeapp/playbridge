import 'dart:async';
import 'dart:convert';

import 'linked_playback.dart';
import 'player_controller.dart';
import 'protocol.dart';
import 'receiver_server.dart';
import 'tv_discovery.dart';
import 'tv_sender_controller.dart';

/// Adapter to the same queues and transport used by the native app UI.
class DesktopLinkedPlayback extends LinkedPlaybackBackend {
  DesktopLinkedPlayback(this.player, this.sender) {
    _destination = destination;
    player.addListener(_changed);
    sender.addListener(_changed);
  }
  final PlayerController player;
  final TvSenderController sender;
  StreamSubscription<String>? _messages;
  TvProtocol? _boundProtocol;
  late String _destination;
  int _revision = 0;
  bool _ownsLocalQueueEnd = false;
  Map<String, dynamic> _remote = {'state': 'connecting'};
  @override
  String get destination =>
      sender.selection.isLocal ? 'local' : sender.selection.identityKey!;
  @override
  bool get connected => destination == 'local' || sender.selectedTvConnected;
  @override
  bool get external =>
      !sender.selection.isLocal &&
      sender.selection.protocol != TvProtocol.playBridge;
  @override
  int get selectionRevision => _revision;
  @override
  Map<String, dynamic> get status => destination == 'local'
      ? {
          'state': player.state,
          'positionMs': player.positionMs,
          'durationMs': player.durationMs,
          'title': player.currentTitle,
          'currentIndex': player.currentIndex,
          'playbackId': player.playbackId,
        }
      : _remote;

  void _changed() {
    if (_destination != destination) {
      _destination = destination;
      _revision++;
      _remote = {'state': 'connecting'};
    }
    if (_messages != null) _bindMessages();
    notifyListeners();
  }

  void _bindMessages() {
    if (_boundProtocol == sender.activeProtocol) return;
    _boundProtocol = sender.activeProtocol;
    unawaited(_messages?.cancel());
    _messages = sender.messages.listen((text) {
      try {
        final frame = jsonDecode(text);
        if (frame is! Map) return;
        switch (frame['type']) {
          case 'status':
            _remote = {
              ..._remote,
              'state': frame['state'],
              'positionMs': frame['position'],
              'durationMs': frame['duration'],
              'title': frame['title'],
              'playbackId': frame['playbackId'],
            };
          case 'playlist_status':
            _remote = {..._remote, 'currentIndex': frame['currentIndex']};
            if (frame['items'] is List && (frame['items'] as List).isEmpty) {
              _remote['state'] = 'idle';
            }
          case 'context':
            if (frame['active'] == 'idle') _remote['state'] = 'idle';
          case 'error':
            _remote['state'] = 'error';
          default:
            return;
        }
        notifyListeners();
      } on Object {
        // Ignore unrecognized status frames, never log their contents.
      }
    });
  }

  @override
  void waitForItems(bool enabled) {
    if (enabled && destination == 'local') {
      player.onLinkedQueueEnd = () => true;
      _ownsLocalQueueEnd = true;
    } else if (_ownsLocalQueueEnd) {
      player.onLinkedQueueEnd = null;
      _ownsLocalQueueEnd = false;
    }
  }

  @override
  Future<bool> replace(
      List<LinkedItem> items,
      int index,
      VisualMetadata? metadata,
      bool skipPreplay,
      bool Function() canCommit) async {
    if (destination == 'local') {
      await player.playPlaylist(
          items.map((item) {
            final media = PlayPayload()..mergeFromMessage(item.media);
            if (!media.hasVisualMetadata() && metadata != null) {
              media.visualMetadata = metadata;
            }
            return receiverQueueItemFromPayload(media,
                skipPreplay: skipPreplay);
          }).toList(),
          index,
          isRemote: true,
          canCommit: canCommit);
      return canCommit();
    }
    _remote = {'state': 'connecting', 'currentIndex': index};
    _bindMessages();
    final playlist = PlaylistPayload(
        items: items.map((item) => item.media),
        startIndex: index,
        skipPreplay: skipPreplay,
        visualMetadata: metadata);
    return sender.castLinkedPlaylist(playlist, canCommit: canCommit);
  }

  @override
  Future<bool> append(List<LinkedItem> items, bool Function() canCommit) async {
    if (destination == 'local') {
      return player.queueAddAll(
          items
              .map((item) => receiverQueueItemFromPayload(item.media))
              .toList(),
          isRemote: true,
          canCommit: canCommit);
    }
    for (final item in items) {
      if (!await sender.queueAdd(item.media, canCommit: canCommit)) {
        return false;
      }
    }
    return true;
  }

  @override
  Future<bool> jump(int index, bool Function() canCommit) async {
    if (!canCommit()) {
      return false;
    }
    if (destination == 'local') {
      return player.jumpToGuarded(index,
          ifPlaybackId: player.playbackId, canCommit: canCommit);
    }
    return sender.playlistJump(index);
  }

  @override
  void dispose() {
    waitForItems(false);
    player.removeListener(_changed);
    sender.removeListener(_changed);
    unawaited(_messages?.cancel());
    super.dispose();
  }
}
