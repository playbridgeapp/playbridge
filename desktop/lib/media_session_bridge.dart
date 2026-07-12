import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_media_session/flutter_media_session.dart';
import 'package:flutter_media_session/flutter_media_session_platform_interface.dart';

import 'engines/mpv_engine.dart';
import 'player_controller.dart';

/// Hooks [PlayerController] into OS media controls so Bluetooth headsets and
/// keyboard media keys can play/pause/skip/seek.
///
/// * **macOS** — native [MediaRemoteHandler] (Now Playing +
///   `togglePlayPauseCommand` + `playbackState`). The pub plugin alone is not
///   enough on macOS for headset buttons.
/// * **Windows** — `flutter_media_session` SMTC.
/// * **Linux** — no-op (no MPRIS backend yet).
class MediaSessionBridge {
  MediaSessionBridge(
    this._player, {
    this.isPlaybackPromptActive,
    this.onPromptContinue,
    this.onPromptStop,
    this.onPlaybackActivity,
  });

  final PlayerController _player;
  final bool Function()? isPlaybackPromptActive;
  final VoidCallback? onPromptContinue;
  final VoidCallback? onPromptStop;
  final VoidCallback? onPlaybackActivity;
  final FlutterMediaSession _pluginSession = FlutterMediaSession();
  _PluginAdapter? _pluginAdapter;
  StreamSubscription? _macEvents;
  bool _started = false;

  static const _macMethod =
      MethodChannel('com.playbridge.desktop/media_remote');
  static const _macEventsCh =
      EventChannel('com.playbridge.desktop/media_remote_events');

  Future<void> start() async {
    if (_started) return;
    if (Platform.isLinux) {
      debugPrint('[media-session] skipped on Linux');
      return;
    }
    try {
      if (Platform.isMacOS) {
        await _startMac();
      } else {
        await _startPlugin();
      }
      _started = true;
      debugPrint(
          '[media-session] activated (${Platform.isMacOS ? "native-mac" : "plugin"})');
    } catch (e, st) {
      debugPrint('[media-session] failed to start: $e\n$st');
    }
  }

  Future<void> _startMac() async {
    await _macMethod.invokeMethod('activate');
    _macEvents = _macEventsCh.receiveBroadcastStream().listen(
          _onMacEvent,
          onError: (e) => debugPrint('[media-session] event error: $e'),
        );
    _player.addListener(_pushMacState);
    // Prefer mpv streams for timely playing/paused updates.
    final engine = _player.engine;
    if (engine is MpvEngine) {
      _macMpvSubs
          .add(engine.player.stream.playing.listen((_) => _pushMacState()));
      _macMpvSubs
          .add(engine.player.stream.completed.listen((_) => _pushMacState()));
      _macMpvSubs
          .add(engine.player.stream.duration.listen((_) => _pushMacState()));
      _macMpvSubs.add(engine.player.stream.position.listen((_) {
        // Throttle position pushes to Now Playing.
        final now = DateTime.now();
        if (now.difference(_lastMacPosPush) <
            const Duration(milliseconds: 800)) {
          return;
        }
        _lastMacPosPush = now;
        _pushMacState();
      }));
    }
    _pushMacState();
  }

  final List<StreamSubscription> _macMpvSubs = [];
  DateTime _lastMacPosPush = DateTime.fromMillisecondsSinceEpoch(0);

  void _pushMacState() {
    final title = _player.currentTitle;
    final idle = _player.queue.isEmpty || _player.currentIndex < 0;
    final status = idle
        ? 'idle'
        : switch (_player.state) {
            'playing' => 'playing',
            'buffering' => 'buffering',
            'ended' => 'ended',
            _ => 'paused',
          };
    unawaited(
      _macMethod.invokeMethod('update', {
        'title': (title != null && title.isNotEmpty) ? title : 'PlayBridge',
        'artist': 'PlayBridge',
        'status': status,
        'positionMs': _player.positionMs.clamp(0, 1 << 30),
        'durationMs': _player.durationMs.clamp(0, 1 << 30),
        'speed': 1.0,
      }).catchError((e) {
        debugPrint('[media-session] mac update: $e');
      }),
    );
  }

  void _onMacEvent(dynamic raw) {
    if (raw is! Map) return;
    final action = raw['action'] as String?;
    if (action == null) return;
    debugPrint('[media-session] mac action: $action');
    if (_handlePromptAction(action)) return;
    onPlaybackActivity?.call();
    switch (action) {
      case 'toggle':
        if (_player.state == 'playing') {
          unawaited(_player.pause());
        } else {
          unawaited(_player.resume());
        }
      case 'play':
        unawaited(_player.resume());
      case 'pause':
        unawaited(_player.pause());
      case 'stop':
        unawaited(_player.stop());
      case 'skipToNext':
        unawaited(_player.next());
      case 'skipToPrevious':
        unawaited(_player.previous());
      case 'fastForward':
        final dur = _player.durationMs;
        var t = _player.positionMs + 10000;
        if (dur > 0 && t > dur) t = dur;
        unawaited(_player.seek(Duration(milliseconds: t)));
      case 'rewind':
        final t = _player.positionMs - 10000;
        unawaited(_player.seek(Duration(milliseconds: t < 0 ? 0 : t)));
      case 'seekTo':
        final ms = raw['positionMs'];
        if (ms is int) {
          unawaited(_player.seek(Duration(milliseconds: ms)));
        } else if (ms is num) {
          unawaited(_player.seek(Duration(milliseconds: ms.toInt())));
        }
      default:
        break;
    }
  }

  bool _handlePromptAction(String action) {
    if (!(isPlaybackPromptActive?.call() ?? false)) return false;
    if (action == 'stop') {
      onPromptStop?.call();
    } else {
      onPromptContinue?.call();
    }
    return true;
  }

  Future<void> _startPlugin() async {
    if (Platform.isWindows) {
      await _pluginSession.setWindowsAppUserModelId(
        'com.playbridge.playbridgeDesktop',
        displayName: 'PlayBridge',
      );
    }
    await _pluginSession.setSkipIntervals(
        forwardSeconds: 10, backwardSeconds: 10);
    await _pluginSession.setAutoHandleInterruptions(false);
    await _pluginSession.activate();
    _pluginAdapter = _PluginAdapter(
      _player,
      isPlaybackPromptActive: isPlaybackPromptActive,
      onPromptContinue: onPromptContinue,
      onPromptStop: onPromptStop,
      onPlaybackActivity: onPlaybackActivity,
    );
    _pluginSession.bind(_pluginAdapter!);
  }

  Future<void> dispose() async {
    if (!_started) return;
    try {
      if (Platform.isMacOS) {
        _player.removeListener(_pushMacState);
        for (final s in _macMpvSubs) {
          await s.cancel();
        }
        _macMpvSubs.clear();
        await _macEvents?.cancel();
        _macEvents = null;
        await _macMethod.invokeMethod('deactivate');
      } else {
        _pluginSession.unbind();
        await _pluginSession.deactivate();
      }
    } catch (e) {
      debugPrint('[media-session] dispose: $e');
    }
    _pluginAdapter = null;
    _started = false;
  }
}

/// Windows (and future non-mac) adapter via flutter_media_session.
class _PluginAdapter implements MediaSessionAdapter {
  _PluginAdapter(
    this._player, {
    this.isPlaybackPromptActive,
    this.onPromptContinue,
    this.onPromptStop,
    this.onPlaybackActivity,
  });

  final PlayerController _player;
  final bool Function()? isPlaybackPromptActive;
  final VoidCallback? onPromptContinue;
  final VoidCallback? onPromptStop;
  final VoidCallback? onPlaybackActivity;
  final List<StreamSubscription<dynamic>> _subs = [];
  FlutterMediaSession? _session;

  @override
  void bind(FlutterMediaSession session) {
    unbind();
    _session = session;
    _player.addListener(_sync);
    final engine = _player.engine;
    if (engine is MpvEngine) {
      _subs.add(engine.player.stream.playing.listen((_) => _sync()));
      _subs.add(engine.player.stream.duration.listen((_) => _sync()));
      _subs.add(engine.player.stream.position.listen((_) => _syncPos()));
    }
    _subs.add(
      FlutterMediaSessionPlatform.instance.onMediaAction.listen(_onAction),
    );
    _sync();
  }

  DateTime _lastPos = DateTime.fromMillisecondsSinceEpoch(0);
  void _syncPos() {
    final now = DateTime.now();
    if (now.difference(_lastPos) < const Duration(milliseconds: 800)) return;
    _lastPos = now;
    _sync();
  }

  @override
  void unbind() {
    _player.removeListener(_sync);
    for (final s in _subs) {
      s.cancel();
    }
    _subs.clear();
    _session = null;
  }

  void _sync() {
    if (_session == null) return;
    final title = _player.currentTitle;
    final idle = _player.queue.isEmpty ||
        _player.currentIndex < 0 ||
        _player.currentIndex >= _player.queue.length;
    final item = idle ? null : _player.queue[_player.currentIndex];
    unawaited(
      FlutterMediaSessionPlatform.instance
          .updateMetadata(
        MediaMetadata(
          title: (title != null && title.isNotEmpty) ? title : 'PlayBridge',
          artist: 'PlayBridge',
          artworkUri: item?.posterUrl ?? item?.backdropUrl,
          duration:
              Duration(milliseconds: _player.durationMs.clamp(0, 1 << 30)),
        ),
      )
          .catchError((Object e) {
        debugPrint('[media-session] metadata: $e');
      }),
    );

    final PlaybackStatus status;
    if (idle) {
      status = PlaybackStatus.idle;
    } else {
      status = switch (_player.state) {
        'playing' => PlaybackStatus.playing,
        'buffering' => PlaybackStatus.buffering,
        'ended' => PlaybackStatus.ended,
        _ => PlaybackStatus.paused,
      };
    }
    unawaited(
      FlutterMediaSessionPlatform.instance
          .updatePlaybackState(
        PlaybackState(
          status: status,
          position:
              Duration(milliseconds: _player.positionMs.clamp(0, 1 << 30)),
        ),
      )
          .catchError((Object e) {
        debugPrint('[media-session] state: $e');
      }),
    );

    unawaited(
      FlutterMediaSessionPlatform.instance.updateAvailableActions({
        MediaAction.play,
        MediaAction.pause,
        MediaAction.stop,
        MediaAction.seekTo,
        MediaAction.rewind,
        MediaAction.fastForward,
        if (_player.hasNext) MediaAction.skipToNext,
        if (_player.hasPrevious) MediaAction.skipToPrevious,
      }).catchError((Object e) {
        debugPrint('[media-session] actions: $e');
      }),
    );
  }

  void _onAction(MediaAction action) {
    debugPrint('[media-session] plugin action: ${action.name}');
    if (isPlaybackPromptActive?.call() ?? false) {
      if (action.name == 'stop') {
        onPromptStop?.call();
      } else {
        onPromptContinue?.call();
      }
      return;
    }
    onPlaybackActivity?.call();
    switch (action.name) {
      case 'play':
        unawaited(_player.resume());
      case 'pause':
        unawaited(_player.pause());
      case 'stop':
        unawaited(_player.stop());
      case 'skipToNext':
        unawaited(_player.next());
      case 'skipToPrevious':
        unawaited(_player.previous());
      case 'seekTo':
        final pos = action.seekPosition;
        if (pos != null) unawaited(_player.seek(pos));
      case 'rewind':
        final t = _player.positionMs - 10000;
        unawaited(_player.seek(Duration(milliseconds: t < 0 ? 0 : t)));
      case 'fastForward':
        final dur = _player.durationMs;
        var t = _player.positionMs + 10000;
        if (dur > 0 && t > dur) t = dur;
        unawaited(_player.seek(Duration(milliseconds: t)));
    }
  }
}
