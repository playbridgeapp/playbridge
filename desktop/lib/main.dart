import 'dart:async';
import 'dart:io';
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:window_manager/window_manager.dart';

import 'package:desktop_drop/desktop_drop.dart';
import 'package:file_selector/file_selector.dart';

import 'logging/log_store.dart';
import 'logging/memory_diagnostics.dart';
import 'chapter_navigation.dart';
import 'context_menu_installer.dart';
import 'discovery.dart';
import 'engines/mpv_engine.dart';
import 'extension_bridge.dart';
import 'linked_playback_banner.dart';
import 'bookmarks_screen.dart';
import 'devices_screen.dart';
import 'home_screen.dart';
import 'bookmark_thumbnail.dart';
import 'history_store.dart';
import 'keyboard_shortcuts_sheet.dart';
import 'media_kit_initializer.dart';
import 'media_session_bridge.dart';
import 'media_kind.dart';
import 'media_presentation_surface.dart';
import 'native_host_installer.dart';
import 'now_casting_screen.dart';
import 'pair_screen.dart';
import 'pairing_store.dart';
import 'playback_clock.dart';
import 'playback_error_notifier.dart';
import 'playback_osd.dart';
import 'playback_speed.dart';
import 'subtitle_delay.dart';
import 'video_scaling.dart';
import 'player_chrome_policy.dart';
import 'playback_surface.dart'
    show subtitleBottomFull, subtitleBottomHidden, subtitleBottomMini;
import 'player_controller.dart';
import 'player_surface_click.dart';
import 'player_engine.dart';
import 'preplay_overlay.dart';
import 'receiver_server.dart';
import 'screen_mirror_surface.dart';
import 'settings_screen.dart';
import 'skip_segment.dart';
import 'skip_segment_fetcher.dart';
import 'single_instance_coordinator.dart';
import 'stream_proxy_server.dart';
import 'stats_overlay.dart';
import 'still_watching_controller.dart';
import 'still_watching_prompt.dart';
import 'together/desktop_together.dart';
import 'together/together_screen.dart';
import 'tray_controller.dart';
import 'tv_connection_store.dart';
import 'tv_sender_controller.dart';
import 'update/app_version.dart';
import 'update/update_checker.dart';
import 'update/update_gate.dart';
import 'player/player_chrome.dart';
import 'shell/main_drop_overlay.dart';
import 'shell/nav_sidebar.dart';
import 'shell/paste_link_dialog.dart';
import 'shell/play_request.dart';
import 'shell/play_target.dart';
import 'shell/playback_bar.dart';
import 'shell/receiver_status.dart';
import 'ui/pb_theme.dart';

Future<void> main(List<String> args) async {
  final launchRequest = InstanceLaunchRequest.fromArgs(args);
  final instanceResult = await SingleInstanceCoordinator.coordinate(
    request: launchRequest,
  );
  if (!instanceResult.isPrimary) {
    if (!instanceResult.forwarded) {
      stderr.writeln(
        '[single-instance] running instance could not be activated: '
        '${instanceResult.forwardingError}',
      );
      exit(1);
    }

    // The native Flutter runner has already created its process and window by
    // the time Dart starts. Returning from main leaves that secondary runner
    // alive with a blank window, so terminate it after the primary acknowledges
    // the forwarded launch request.
    exit(0);
  }
  final instanceCoordinator = instanceResult.coordinator!;

  WidgetsFlutterBinding.ensureInitialized();
  ensurePlayBridgeMediaKitInitialized();
  await windowManager.ensureInitialized();
  try {
    await PlaybackErrorNotifier.setUp();
  } catch (e) {
    debugPrint('Notifications unavailable: $e');
  }

  // Diagnostics: opt-in persistent logging (off by default). Tee debugPrint into the
  // store so existing logs are captured with no call-site changes, and record crashes.
  await LogStore.instance.init();
  final originalDebugPrint = debugPrint;
  debugPrint = (String? message, {int? wrapWidth}) {
    originalDebugPrint(message, wrapWidth: wrapWidth);
    if (message != null) LogStore.instance.appendRaw(message);
  };
  final priorOnError = FlutterError.onError;
  FlutterError.onError = (details) {
    priorOnError?.call(details);
    LogStore.instance.logCrash(
      details.exception,
      details.stack ?? StackTrace.current,
      context: 'FlutterError',
    );
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    LogStore.instance.logCrash(error, stack, context: 'PlatformDispatcher');
    return false; // not handled — preserve default reporting
  };

  windowManager.waitUntilReadyToShow(const WindowOptions(), () async {
    await windowManager.setPreventClose(true);
  });

  final results = await Future.wait([
    PairingStore.load(),
    HistoryStore.load(),
    TvConnectionStore.load(),
  ]);
  runApp(ReceiverApp(
    store: results[0] as PairingStore,
    history: results[1] as HistoryStore,
    tvStore: results[2] as TvConnectionStore,
    instanceCoordinator: instanceCoordinator,
    // "Play on TV" cold-start: the cast helper launches the app with these when
    // it isn't already running. The app casts the file once a TV connects.
    initialCastFile: launchRequest.castFile,
    initialCastTitle: launchRequest.castTitle,
  ));
}

// Keyboard shortcuts
class PlayPauseIntent extends Intent {
  const PlayPauseIntent();
}

class SeekForwardIntent extends Intent {
  const SeekForwardIntent();
}

class SeekBackwardIntent extends Intent {
  const SeekBackwardIntent();
}

class VolumeUpIntent extends Intent {
  const VolumeUpIntent();
}

class VolumeDownIntent extends Intent {
  const VolumeDownIntent();
}

class SpeedDownIntent extends Intent {
  const SpeedDownIntent();
}

class SpeedUpIntent extends Intent {
  const SpeedUpIntent();
}

class CycleScalingIntent extends Intent {
  const CycleScalingIntent();
}

class SubtitleDelayMinusIntent extends Intent {
  const SubtitleDelayMinusIntent();
}

class SubtitleDelayPlusIntent extends Intent {
  const SubtitleDelayPlusIntent();
}

class PreviousChapterIntent extends Intent {
  const PreviousChapterIntent();
}

class NextChapterIntent extends Intent {
  const NextChapterIntent();
}

class BookmarkIntent extends Intent {
  const BookmarkIntent();
}

class StatsToggleIntent extends Intent {
  const StatsToggleIntent();
}

// Navigation destinations. The local player is a mode (_showingVideo), not a
// destination; nowCasting is reached from Devices and is not in the sidebar.
enum _Dest { home, bookmarks, devices, nowCasting, together, settings }

class ReceiverApp extends StatefulWidget {
  const ReceiverApp({
    super.key,
    required this.store,
    required this.history,
    required this.tvStore,
    required this.instanceCoordinator,
    this.initialCastFile,
    this.initialCastTitle,
  });

  final PairingStore store;
  final HistoryStore history;
  final TvConnectionStore tvStore;
  final SingleInstanceCoordinator instanceCoordinator;
  final String? initialCastFile;
  final String? initialCastTitle;

  @override
  State<ReceiverApp> createState() => _ReceiverAppState();
}

class _ReceiverAppState extends State<ReceiverApp> with WindowListener {
  final FocusNode _keyboardFocusNode =
      FocusNode(debugLabel: 'desktop-shortcuts');
  late final PlayerController _player;
  late final MemoryDiagnostics _memoryDiagnostics;
  late final ReceiverServer _server;
  late final DiscoveryPublisher _discovery;
  late final TrayController _tray;
  late final TvSenderController _sender;
  late final ExtensionBridge _extBridge;
  late final DesktopTogether _together;
  StreamSubscription<String>? _togetherToasts;
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();
  late final MediaSessionBridge _mediaSession;
  late final StillWatchingController _stillWatching;
  final UpdateChecker _updateChecker = UpdateChecker();

  bool _hadMedia = false;
  String? _lastTrackedUrl;

  /// Last coarse player state the shell was built for. The root builder no
  /// longer listens to the player directly (see build), so real transitions
  /// (queue, state, index) trigger a setState here instead.
  (bool, int, String, int)? _lastCoarse;
  bool? _lastChromePlaying;
  // (playbackId, index) whose first frame has played; chrome stays up before.
  (String?, int)? _startedItem;
  // Playback state before the first click of a possible double-click.
  bool? _clickWasPlaying;
  bool _fullScreenOnRelease = false;

  // Pre-play screen state: the item being introduced, and the volume to
  // restore once playback starts (video buffers muted behind the overlay).
  QueueItem? _prePlayItem;
  double? _prePlayPrevVolume;

  String _hostInfo = 'starting…';
  String? _serverError;
  String? _discoveryError;

  _Dest _dest = _Dest.home;
  bool _showingVideo = false;
  bool _isFullScreen = false;

  // Live playback-stats overlay. Toggled by the `i` hotkey or the Settings
  // switch; both mutate this notifier and the change is persisted via the store.
  late final ValueNotifier<bool> _showStats;

  // Full while paused, not started, or pinned. Mini while playing.
  // Fullscreen hides mini after the pointer stays still.
  PlayerChromeMode _chromeMode = PlayerChromeMode.full;
  Timer? _chromeIdleTimer;
  bool _pointerIdle = false;
  int _menusOpen = 0;
  bool _scrubbing = false;
  final _surfaceClick = PlayerSurfaceClick();

  /// Center OSD (pause / seek / volume); cleared by [_osdTimer].
  String? _osdMessage;
  Timer? _osdTimer;

  bool _mainDragging = false;

  /// Pointer position over the drop surface while dragging (drives the split
  /// "Play here" / "Send to TV" highlight).
  final ValueNotifier<Offset?> _dropPointer = ValueNotifier<Offset?>(null);
  final GlobalKey _dropAreaKey = GlobalKey(debugLabel: 'main-drop-area');

  final _skipFetcher = SkipSegmentFetcher();
  List<SkipSegment> _skipSegments = const [];
  SkipSegment? _lastSkipped;
  String? _skipItemKey;
  int _skipFetchGen = 0;
  int _seekRepeatCount = 0;
  LogicalKeyboardKey? _seekRepeatKey;

  bool get _chromePinned => _menusOpen > 0 || _scrubbing;
  bool get _playing => PlayerChromePolicy.isPlaying(_player.state);
  (String?, int) get _currentItem => (_player.playbackId, _player.currentIndex);
  bool get _started => _startedItem == _currentItem;

  bool _canHideChrome(bool playing) =>
      _isFullScreen && playing && _started && !_chromePinned;

  void _armChromeIdleTimer({bool? playing}) {
    _chromeIdleTimer?.cancel();
    if (!_canHideChrome(playing ?? _playing)) return;
    _chromeIdleTimer = Timer(PlayerChromePolicy.fullscreenIdleDuration, () {
      if (!mounted || !_canHideChrome(_playing)) return;
      _pointerIdle = true;
      _applyChrome();
    });
  }

  void _onPlayerHover(PointerHoverEvent _) {
    if (_pointerIdle) {
      _pointerIdle = false;
      _applyChrome();
      return;
    }
    if (_canHideChrome(_playing)) _armChromeIdleTimer();
  }

  void _applyChrome({bool? playing}) {
    if (!mounted) return;
    final playingNow = playing ?? _playing;
    final canHide = _canHideChrome(playingNow);
    if (!canHide) {
      _chromeIdleTimer?.cancel();
      _pointerIdle = false;
    } else if (!_pointerIdle && !(_chromeIdleTimer?.isActive ?? false)) {
      _armChromeIdleTimer(playing: playingNow);
    }
    final mode = PlayerChromePolicy.resolve(
      playing: playingNow,
      pinned: _chromePinned,
      started: _started,
      fullscreen: _isFullScreen,
      pointerIdle: _pointerIdle,
    ).mode;
    if (mode != _chromeMode) {
      setState(() => _chromeMode = mode);
    }
  }

  SkipAutoPrefs get _skipAutoPrefs => SkipAutoPrefs(
        intro: widget.store.autoSkipIntro,
        recap: widget.store.autoSkipRecap,
        outro: widget.store.autoSkipOutro,
      );

  void _syncSkipSegments() {
    QueueItem? item;
    if (_player.queue.isNotEmpty &&
        _player.currentIndex >= 0 &&
        _player.currentIndex < _player.queue.length) {
      item = _player.queue[_player.currentIndex];
    }
    final key = item == null
        ? null
        : '${item.imdbId}|${item.tmdbId}|${item.season}|${item.episode}|${item.url}';
    if (key == _skipItemKey) {
      // The same content under a new playback/item identity keeps its segments.
      _player.publishSkipSegments(_skipSegments);
      return;
    }
    _skipItemKey = key;
    _lastSkipped = null;
    _skipSegments = const [];
    _player.publishSkipSegments(const []);
    // Bumped on every item change so a pending fetch never lands on another item.
    final gen = ++_skipFetchGen;
    if (item == null ||
        !skipFetchAllowed(
          imdbId: item.imdbId,
          tmdbId: item.tmdbId,
          season: item.season,
          episode: item.episode,
        )) {
      return;
    }
    unawaited(_skipFetcher
        .fetch(
      provider: widget.store.skipSegmentsProvider,
      imdbId: item.imdbId,
      tmdbId: item.tmdbId,
      season: item.season,
      episode: item.episode,
      introDbApiKey: widget.store.introDbApiKey,
    )
        .then((segments) {
      if (!mounted || gen != _skipFetchGen) return;
      setState(() => _skipSegments = segments);
      _player.publishSkipSegments(segments);
    }));
  }

  void _performSkip(SkipSegment segment) {
    if (_lastSkipped == segment) return;
    _lastSkipped = segment;
    final target = skipTargetMs(segment, _player.durationMs);
    unawaited(_player.seek(Duration(milliseconds: target)));
    _showOsd(skipButtonLabel(segment.type));
    setState(() {});
  }

  @override
  void initState() {
    super.initState();
    _showStats = ValueNotifier<bool>(widget.store.showStats);
    _showStats.addListener(() => widget.store.setShowStats(_showStats.value));
    _player = PlayerController(
      initialEngine: widget.store.engineType,
      preselectHlsQuality: widget.store.preselectHlsQuality,
      store: widget.store,
    );
    _memoryDiagnostics = MemoryDiagnostics(
      enabled: LogStore.instance.enabledListenable,
      readSnapshot: _readMemorySnapshot,
      write: (level, message) =>
          LogStore.instance.log(level, 'memory', message),
    )..start();
    _stillWatching = StillWatchingController(
      player: _player,
      enabled: widget.store.stillWatchingEnabled,
      threshold: Duration(minutes: widget.store.stillWatchingThresholdMinutes),
      gracePeriod: Duration(seconds: widget.store.stillWatchingResponseSeconds),
      onAutoStop: () => _server.broadcastIdleContext(),
    );
    _stillWatching.addListener(_handleStillWatchingChange);
    _server = ReceiverServer(
      player: _player,
      store: widget.store,
      isPlaybackPromptActive: () => _stillWatching.isPrompting,
      onPromptContinue: () => unawaited(_stillWatching.continueWatching()),
      onPromptStop: () => unawaited(_stillWatching.stopWatching()),
      onPlaybackActivity: _stillWatching.recordUserActivity,
      onNewMedia: _stillWatching.resetForNewMedia,
      onScreenMirrorStarted: _handleScreenMirrorStarted,
      onRemoteVolumeFeedback: (message) =>
          _showOsd(message, duration: const Duration(milliseconds: 1500)),
    );
    _discovery = DiscoveryPublisher(
      serviceName: widget.store.deviceName,
      deviceId: widget.store.deviceId,
    );
    _sender = TvSenderController(identity: widget.store, store: widget.tvStore);
    _together = DesktopTogether(
      player: _player,
      sender: _sender,
      prefs: widget.store.prefs,
      deviceName: () => widget.store.deviceName,
    );
    _server.onWatchTogetherJoin = _together.joinFromPhone;
    _togetherToasts = _together.toasts.listen((message) => _messengerKey
        .currentState
        ?.showSnackBar(SnackBar(content: Text(message))));
    _extBridge = ExtensionBridge(
      _sender,
      _player,
      isPlaybackPromptActive: () => _stillWatching.isPrompting,
      onPromptContinue: () => unawaited(_stillWatching.continueWatching()),
      onPromptStop: () => unawaited(_stillWatching.stopWatching()),
      onPlaybackActivity: _stillWatching.recordUserActivity,
      onNewMedia: _stillWatching.resetForNewMedia,
      party: ({required url, title, headers}) {
        if (!_together.isInParty) return null;
        if (url.isEmpty) return '';
        final message = _together.shareVideo(
            url: url, title: title, headers: headers ?? const {});
        _messengerKey.currentState
            ?.showSnackBar(SnackBar(content: Text(message)));
        return message;
      },
      onShowApp: (pairId) async {
        await _showSenderScreen();
        if (pairId == null) return;
        for (final tv in _sender.discovered) {
          if (tv.identityKey == pairId) {
            // The existing Send to TV view displays the SAS/PIN stages.
            unawaited(
                _sender.connectToDiscovered(tv).catchError((Object error) {
              debugPrint(
                  '[ext-bridge] pairing connect failed: ${error.runtimeType}');
            }));
            break;
          }
        }
      },
    );
    _tray = TrayController(
      linkedPlayback: _extBridge.linkedPlayback,
      player: _player,
      server: _server,
      store: widget.store,
      sender: _sender,
      showSender: _showSenderScreen,
    );
    _mediaSession = MediaSessionBridge(
      _player,
      isPlaybackPromptActive: () => _stillWatching.isPrompting,
      onPromptContinue: () => unawaited(_stillWatching.continueWatching()),
      onPromptStop: () => unawaited(_stillWatching.stopWatching()),
      onPlaybackActivity: _stillWatching.recordUserActivity,
    );

    windowManager.addListener(this);
    _player.addListener(_handlePlayerChange);
    _player.playRequests.addListener(_handlePlayRequest);
    _server.addListener(_handlePairingPrompt);

    _bootServerThenDiscovery();
    _resolveHost();
    _initTrayAndWindow();
    unawaited(_sender.start());
    unawaited(_extBridge.start());
    // Now Playing / SMTC so Bluetooth headset play-pause reaches this player.
    unawaited(_mediaSession.start());
    // Register the browser native-messaging host + the OS "Play on TV" context
    // menu so the extension and file manager can reach us without the user
    // editing files by hand (idempotent, best-effort).
    unawaited(NativeHostInstaller.installSilently());
    unawaited(ContextMenuInstaller.installSilently());

    // Silent update check a few seconds after launch (stays quiet unless a
    // newer desktop release exists); Settings has the manual re-check.
    Timer(const Duration(seconds: 5), () {
      if (mounted) unawaited(_updateChecker.check(manual: false));
    });

    // Jump to the Now Playing tab when a cast starts (unless watching local
    // video here). Tracks the rising edge so it doesn't fight tab navigation.
    _sender.addListener(_handleSenderChange);

    // `autofocus` is not enough on desktop once widgets/platform views have
    // had a chance to take focus, so explicitly claim the shortcut focus.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _keyboardFocusNode.requestFocus();
    });

    // Cold-start file open (`playbridge_cast` launched the app): cast to a TV
    // if already linked, otherwise play on this desktop (same as extension
    // bridge when disconnected).
    final initialCastFile = widget.initialCastFile;
    if (initialCastFile != null) {
      _queuePendingCast(
        initialCastFile,
        widget.initialCastTitle,
        afterFirstFrame: true,
      );
    }

    // A secondary process can arrive while Flutter and the player are still
    // starting. The coordinator queues those requests until this handler is
    // installed, then routes them through the same cold-start cast path.
    widget.instanceCoordinator.setLaunchHandler(_handleInstanceLaunch);
  }

  bool _senderWasCasting = false;
  String? _lastReportedPlaybackError;
  late final _playbackErrorNotifier =
      PlaybackErrorNotifier(onClick: () => unawaited(_showSenderScreen()));
  bool _wasStillWatchingPrompting = false;

  void _handleStillWatchingChange() {
    final prompting = _stillWatching.isPrompting;
    if (prompting && !_wasStillWatchingPrompting) {
      unawaited(_revealWindow());
    }
    _wasStillWatchingPrompting = prompting;
    if (mounted) setState(() {});
  }

  void _applyStillWatchingSettings() {
    _stillWatching.updateSettings(
      enabled: widget.store.stillWatchingEnabled,
      threshold: Duration(minutes: widget.store.stillWatchingThresholdMinutes),
      gracePeriod: Duration(seconds: widget.store.stillWatchingResponseSeconds),
    );
  }

  void _handleSenderChange() {
    final playbackError = _sender.lastPlaybackError;
    if (playbackError != _lastReportedPlaybackError) {
      _lastReportedPlaybackError = playbackError;
      if (mounted) {
        // The in-window notice is the cast-failed corner card in UpdateGate.
        if (playbackError != null) {
          unawaited(_notifyPlaybackError(playbackError));
        } else {
          unawaited(_closePlaybackErrorNotification());
        }
      }
    }
    final casting = _sender.isCasting;
    if (casting == _senderWasCasting) return; // only react to edges
    _senderWasCasting = casting;
    setState(() {
      if (casting && !_showingVideo) {
        // Cast started → jump to the Now Playing tab.
        _dest = _Dest.nowCasting;
      } else if (!casting && _dest == _Dest.nowCasting) {
        // Cast ended while on the now-casting screen → fall back to Devices.
        _dest = _Dest.devices;
      }
    });
  }

  String? _pendingCastFile;
  String? _pendingCastTitle;

  Future<void> _handleInstanceLaunch(InstanceLaunchRequest request) async {
    await _revealWindow();
    if (!mounted || request.castFile == null) return;
    _queuePendingCast(request.castFile!, request.castTitle);
  }

  void _queuePendingCast(
    String path,
    String? title, {
    bool afterFirstFrame = false,
  }) {
    _pendingCastFile = path;
    _pendingCastTitle = title;
    _sender.removeListener(_maybeCastPendingFile);
    _sender.addListener(_maybeCastPendingFile);
    if (afterFirstFrame) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _resolvePendingCastFile();
      });
    } else {
      _resolvePendingCastFile();
    }
  }

  /// Rising edge: TV connected while a cold-start file is still pending → TV.
  void _maybeCastPendingFile() {
    if (_pendingCastFile == null || !_sender.isConnected) return;
    _resolvePendingCastFile();
  }

  /// One-shot: send [initialCastFile] to the TV if linked, else play locally.
  void _resolvePendingCastFile() {
    final path = _pendingCastFile;
    if (path == null) return;
    final title = _pendingCastTitle;
    _pendingCastFile = null;
    _pendingCastTitle = null;
    _sender.removeListener(_maybeCastPendingFile);
    final file = File(path);
    if (!file.existsSync()) {
      debugPrint('[main] pending cast file missing: $path');
      return;
    }
    if (_sender.isConnected) {
      unawaited(_sender.castLocalFile(file, title: title));
      return;
    }
    final name = title ??
        (file.uri.pathSegments.isNotEmpty ? file.uri.pathSegments.last : path);
    unawaited(_player.playUrl(
      file.uri.toString(),
      title: name,
      isRemote: true,
      localUserInitiated: true,
    ));
  }

  /// Casts from the browser extension usually happen while this window is
  /// hidden in the tray, where the snackbar can't be seen.
  Future<void> _notifyPlaybackError(String message) async {
    try {
      if (await windowManager.isVisible() && await windowManager.isFocused()) {
        return;
      }
      final tv = _sender.selectionName;
      await _playbackErrorNotifier.show(
        title: tv.isEmpty ? 'Playback failed' : 'Playback failed on $tv',
        body: message,
      );
    } catch (e) {
      debugPrint('Playback error notification failed: $e');
    }
  }

  Future<void> _closePlaybackErrorNotification() async {
    try {
      await _playbackErrorNotifier.close();
    } catch (_) {}
  }

  Future<void> _initTrayAndWindow() async {
    await _tray.init();
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
    await windowManager.show();
    await windowManager.focus();
  }

  Future<void> _showSenderScreen() async {
    if (!mounted) return;
    setState(() {
      _dest = _Dest.devices;
      _showingVideo = false;
    });
    await windowManager.show();
    await windowManager.focus();
  }

  void _handlePlayRequest() {
    _stillWatching.resetForNewMedia();
    unawaited(_revealWindow(fullScreen: widget.store.autoFullScreen));
    if (!_showingVideo) {
      setState(() => _showingVideo = true);
    }
    _maybeShowPrePlay();
  }

  /// Show the pre-play screen for remote casts that carry visual metadata.
  /// The video keeps buffering underneath, muted; volume is restored on start.
  void _maybeShowPrePlay() {
    final idx = _player.currentIndex;
    final item =
        (idx >= 0 && idx < _player.queue.length) ? _player.queue[idx] : null;
    if (item == null ||
        item.mediaKind != MediaKind.video ||
        item.skipPreplay ||
        !item.hasPrePlayMetadata) {
      return;
    }
    _prePlayPrevVolume ??= _player.volume;
    unawaited(_player.setVolume(0));
    setState(() => _prePlayItem = item);
  }

  void _dismissPrePlay() {
    final prev = _prePlayPrevVolume;
    _prePlayPrevVolume = null;
    if (prev != null) unawaited(_player.setVolume(prev));
    if (_prePlayItem != null && mounted) {
      setState(() => _prePlayItem = null);
    }
  }

  void _handlePlayerChange() {
    _memoryDiagnostics.playbackChanged((
      _player.engine,
      _player.playbackId,
      _player.currentIndex,
      _player.state,
      _player.isOpening,
    ));
    final hasMedia = _player.queue.isNotEmpty;

    // Rising edge: new playback session started → switch to video view.
    if (hasMedia && !_hadMedia) {
      setState(() => _showingVideo = true);
    }

    // Falling edge: playback ended → leave video view.
    if (!hasMedia && _hadMedia) {
      _dismissPrePlay();
      setState(() => _showingVideo = false);
    }

    _hadMedia = hasMedia;
    _syncSkipSegments();

    // Coarse shell rebuild on real transitions only (the root builder doesn't
    // listen to the player; per-frame position ticks stay out of the shell).
    final coarse = (
      hasMedia,
      _player.queue.length,
      _player.state,
      _player.currentIndex,
    );
    if (hasMedia &&
        !_started &&
        _player.state == 'playing' &&
        !_player.isOpening) {
      _startedItem = _currentItem;
    }
    final chromePlaying = _playing && _started;
    if (chromePlaying != _lastChromePlaying) {
      _lastChromePlaying = chromePlaying;
      _applyChrome();
    }
    if (coarse != _lastCoarse) {
      _lastCoarse = coarse;
      if (mounted) setState(() {});
    }

    // Record history when the active track changes. Prefer original CDN URL
    // and keep demuxed synthetic handoff so History/Favorites resume with A/V.
    if (hasMedia) {
      final idx = _player.currentIndex;
      if (idx >= 0 && idx < _player.queue.length) {
        final item = _player.queue[idx];
        final historyUrl = item.originalUrl ?? item.url;
        if (item.skipHistory) {
          _lastTrackedUrl = null;
        } else if (historyUrl != _lastTrackedUrl) {
          _lastTrackedUrl = historyUrl;
          unawaited(widget.history.addOrBump(
            skipHistory: item.skipHistory,
            url: historyUrl,
            title: item.title,
            playlistBody: item.playlistBody,
            audioUrl: item.audioUrl,
            headers: item.originalHeaders ?? item.headers,
            contentType: item.contentType,
            mediaKind: item.mediaKind.wireValue,
            displayDurationMs: item.displayDurationMs,
          ));
        }
      }
    }
  }

  void _handleScreenMirrorStarted() {
    if (mounted) setState(() => _showingVideo = false);
    unawaited(_revealWindow());
  }

  Future<void> _revealWindow({bool fullScreen = false}) async {
    await windowManager.show();
    await windowManager.focus();
    if (fullScreen) {
      // The first play after launch often misses the transition (the window
      // isn't key yet right after show/focus), which is why it only worked on
      // the second try. Attempt it, then — after the macOS transition animation
      // would have finished — retry once if it didn't take. Checking post-
      // animation avoids toggling back out mid-transition.
      await windowManager.setFullScreen(true);
      await Future<void>.delayed(const Duration(milliseconds: 400));
      if (!await windowManager.isFullScreen()) {
        await windowManager.setFullScreen(true);
      }
    }
  }

  /// Rising edge into a pairing UI phase: bring the window forward (no
  /// fullscreen) so the user can enter the code even if the app was hidden
  /// or another app was focused — same idea as cast playback reveal.
  PairingPhase _lastPairingPhase = PairingPhase.idle;
  void _handlePairingPrompt() {
    final phase = _server.phase;
    final pairing = phase == PairingPhase.awaitingCode ||
        phase == PairingPhase.awaitingApproval;
    final wasPairing = _lastPairingPhase == PairingPhase.awaitingCode ||
        _lastPairingPhase == PairingPhase.awaitingApproval;
    _lastPairingPhase = phase;
    if (!pairing || wasPairing) return;

    // The pairing card is drawn over whatever screen is current (including
    // video), so there is nothing to navigate away from.
    unawaited(_revealWindow(fullScreen: false));
  }

  @override
  void onWindowClose() async {
    final prevented = await windowManager.isPreventClose();
    if (!prevented) return;
    // Fullscreen red X = leave immersive mode only (stay visible), same end
    // state as the green full-screen control. Windowed red X = hide to tray.
    // Quit is tray / Settings only.
    //
    // Green vs red in fullscreen both end windowed+visible; they differ after
    // that: green is “back to a normal window”, red is the first step of close
    // (second red X then hides). Matching Safari-style progressive close.
    if (await windowManager.isFullScreen()) {
      await windowManager.setFullScreen(false);
      return;
    }
    // A watch party keeps playing: pausing here would pause everyone.
    if (widget.store.pauseOnWindowHide &&
        !_player.inParty &&
        (_player.state == 'playing' || _player.state == 'buffering')) {
      await _player.pause();
    }
    await windowManager.hide();
  }

  @override
  void onWindowEnterFullScreen() {
    if (!mounted) return;
    setState(() => _isFullScreen = true);
    _applyChrome();
  }

  @override
  void onWindowLeaveFullScreen() {
    if (!mounted) return;
    setState(() => _isFullScreen = false);
    _applyChrome();
  }

  Future<void> _toggleFullScreen() async {
    final isFs = await windowManager.isFullScreen();
    await windowManager.setFullScreen(!isFs);
  }

  // Discovery starts after the server so it can advertise the actual bound
  // wss port (only known once the TLS listener is up).
  Future<void> _bootServerThenDiscovery() async {
    final port = await _bootServer();
    if (port == null) return;
    await _bootDiscovery(port);
  }

  Future<int?> _bootServer() async {
    late final int port;
    try {
      port = await _server.start();
    } catch (e) {
      if (mounted) setState(() => _serverError = '$e');
      return null;
    }

    try {
      await StreamProxyServer.instance.start();
    } catch (e) {
      if (mounted) setState(() => _serverError = '$e');
    }
    return port;
  }

  Future<void> _bootDiscovery(int port) async {
    try {
      await _discovery.start(port: port);
    } catch (e) {
      if (mounted) setState(() => _discoveryError = '$e');
    }
  }

  Future<void> _resolveHost() async {
    try {
      final ifaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLinkLocal: false,
        includeLoopback: false,
      );
      final addrs = ifaces
          .where((i) {
            final name = i.name.toLowerCase();
            return !name.startsWith('br-') &&
                !name.startsWith('docker') &&
                !name.startsWith('veth') &&
                !name.startsWith('virbr') &&
                !name.startsWith('vboxnet') &&
                !name.startsWith('vmnet') &&
                !name.startsWith('tun') &&
                !name.startsWith('tap') &&
                !name.startsWith('wg');
          })
          .expand((i) => i.addresses)
          .map((a) => a.address)
          .where((a) => !a.startsWith('169.254.'))
          .toList();
      setState(() =>
          _hostInfo = addrs.isEmpty ? 'no LAN address' : addrs.join(', '));
    } catch (_) {
      setState(() => _hostInfo = 'unknown');
    }
  }

  void _showOsd(String message,
      {Duration duration = const Duration(milliseconds: 1100)}) {
    _osdTimer?.cancel();
    if (!mounted) return;
    setState(() => _osdMessage = message);
    _osdTimer = Timer(duration, () {
      if (mounted) setState(() => _osdMessage = null);
    });
  }

  void _togglePlayPause({bool withOsd = true}) {
    if (_player.state == 'playing') {
      unawaited(_player.pause());
      _applyChrome(playing: false);
      if (withOsd) _showOsd('Paused');
    } else if (_player.state == 'paused' || _player.state == 'buffering') {
      unawaited(_player.resume());
      _applyChrome(playing: true);
      if (withOsd) _showOsd('Play');
    }
  }

  void _onVideoSurfaceClick() {
    if (_surfaceClick.isDouble(DateTime.now())) {
      // Restore the pre-click state explicitly: the engine may not have
      // reported the first click's pause yet, so toggling again could pause
      // twice.
      final wasPlaying = _clickWasPlaying;
      _clickWasPlaying = null;
      if (wasPlaying == true) {
        unawaited(_player.resume());
        _applyChrome(playing: true);
      } else if (wasPlaying == false) {
        unawaited(_player.pause());
        _applyChrome(playing: false);
      }
      // Toggle on release: the fullscreen animation would swallow this
      // click's mouse-up, leaving Flutter thinking the button is still held
      // and dropping the next click.
      _fullScreenOnRelease = true;
      return;
    }
    final state = _player.state;
    _clickWasPlaying =
        state == 'paused' ? false : PlayerChromePolicy.isPlaying(state);
    _togglePlayPause();
  }

  void _seekBy(int deltaMs, {required String osd}) {
    final dur = _player.durationMs;
    var target = _player.positionMs + deltaMs;
    if (target < 0) target = 0;
    if (dur > 0 && target > dur) target = dur;
    unawaited(_player.seek(Duration(milliseconds: target)));
    _showOsd(osd);
  }

  void _jumpChapter({required bool forward}) {
    final target = chapterJumpTarget(
      _player.chapters,
      _player.positionMs,
      forward: forward,
    );
    if (target == null) {
      if (forward && _player.chapters.length >= 2) _showOsd('Last chapter');
      return;
    }
    unawaited(_player.seek(Duration(milliseconds: target.chapter.startMs)));
    _showOsd(chapterLabel(target));
  }

  void _nudgeVolume(double delta) {
    final next = (_player.volume + delta).clamp(0.0, 1.0);
    unawaited(_player.setVolume(next));
    _showOsd('Volume ${(next * 100).round()}%');
  }

  void _cycleVideoScaling() {
    if (_player.currentMediaKind != MediaKind.video) return;
    final next = nextVideoScalingMode(_player.videoScaling);
    unawaited(_player.setVideoScaling(next));
    _showOsd(videoScalingLabel(next));
  }

  void _nudgeSubtitleDelay(int deltaMs) {
    if (!_player.subtitleActive) return;
    final next = clampSubtitleDelayMs(_player.subtitleDelayMs + deltaMs);
    unawaited(_player.setSubtitleDelayMs(next));
    _showOsd(subtitleDelayLabel(next));
  }

  void _stepPlaybackSpeed(int direction) {
    final kind = _player.currentMediaKind;
    if (_player.queue.isEmpty || kind == null || kind == MediaKind.image) {
      return;
    }
    final next = stepPlaybackSpeed(_player.playbackRate, direction);
    unawaited(_player.setPlaybackRate(next));
    _showOsd(playbackSpeedLabel(next));
  }

  /// Window-wide drop: with a TV selected the surface is split in two and the
  /// half under the pointer decides; otherwise everything plays here.
  void _onMainDrop(DropDoneDetails detail) {
    setState(() => _mainDragging = false);
    _dropPointer.value = null;
    final request = classifyPlayablePaths(detail.files.map((f) => f.path));
    var toTv = playsOnTv(_sender);
    final size = _dropAreaKey.currentContext?.size;
    if (toTv && size != null) {
      toTv = dropZoneAt(detail.localPosition, size) == DropZone.tv;
    }
    unawaited(_playOrSend(request, toTv: toTv));
  }

  Future<void> _chooseFiles() async {
    final group = XTypeGroup(
      label: 'Media',
      extensions: kPlayableExtensions.toList(),
    );
    final picked = await openFiles(acceptedTypeGroups: [group]);
    if (picked.isEmpty || !mounted) return;
    await _playOrSend(classifyPlayablePaths(picked.map((f) => f.path)));
  }

  Future<void> _pasteLink() async {
    // A context below the Navigator (the app-level ones sit above it).
    final navContext = _keyboardFocusNode.context;
    if (navContext == null) return;
    final link = await showPasteLinkDialog(navContext);
    if (link == null || !mounted) return;
    await _playOrSend(PlayRequest(urls: [link]));
  }

  /// Makes sure the selected TV is connected, reporting progress and failure.
  Future<bool> _ensureTvReady() async {
    if (_sender.selectedTvConnected) return true;
    final name = _sender.selectionName;
    _showOsd('Connecting to $name\u2026');
    final ok = await _sender.ensureSelectedConnected();
    if (!mounted) return false;
    if (!ok) _showOsd("Couldn't connect to $name");
    return ok;
  }

  /// The one entry point for "play this": files and links either go to the
  /// selected TV ([TvSenderController] cast paths) or play on this computer.
  /// [toTv] overrides the current "Plays on" choice (used by the split drop).
  Future<void> _playOrSend(PlayRequest request, {bool? toTv}) async {
    if (request.isEmpty) {
      _showOsd('No media in drop');
      return;
    }
    final files = request.files;
    final urls = request.urls;

    if (toTv ?? playsOnTv(_sender)) {
      if (!await _ensureTvReady()) return;
      if (files.isNotEmpty) {
        unawaited(_sender.castLocalFiles(files).then((ok) {
          if (!ok) _showOsd(_sender.lastCastError ?? 'Cast failed');
        }));
      }
      for (final u in urls) {
        unawaited(_sender.castUrl(u));
      }
      _showOsd('Casting\u2026');
      setState(() {
        _dest = _Dest.nowCasting;
        _showingVideo = false;
      });
      return;
    }

    // Local play: URLs + files as a playlist.
    final items = <QueueItem>[
      for (final u in urls)
        QueueItem(
          url: u,
          title: u.split('/').last.split('?').first,
          localUserInitiated: true,
        ),
      for (final f in files)
        QueueItem(
          url: f.uri.toString(),
          title:
              f.uri.pathSegments.isNotEmpty ? f.uri.pathSegments.last : f.path,
          localUserInitiated: true,
        ),
    ];
    unawaited(_player.playPlaylist(items, 0, isRemote: true));
    _showOsd(items.length == 1 ? 'Playing' : '${items.length} items');
  }

  /// A bookmark or recent item from Home, on the current "Plays on" choice.
  Future<void> _playHistoryItem(PlayHistoryItem item) async {
    if (playsOnTv(_sender)) {
      if (!await _ensureTvReady()) return;
      unawaited(_sender.castUrl(
        item.url,
        headers: item.headers,
        title: item.title,
        contentType: item.contentType,
        playlistBody: item.playlistBody,
        audioUrl: item.audioUrl,
      ));
      _showOsd('Casting\u2026');
      setState(() {
        _dest = _Dest.nowCasting;
        _showingVideo = false;
      });
      return;
    }
    unawaited(_player.playUrl(
      item.url,
      title: item.title,
      headers: item.headers,
      playlistBody: item.playlistBody,
      audioUrl: item.audioUrl,
      contentType: item.contentType,
      declaredMediaKind: item.mediaKind,
      displayDurationMs: item.displayDurationMs,
      localUserInitiated: true,
    ));
  }

  /// What is playing now, if it can be bookmarked. Every kind of cast can,
  /// including local files and private casts; only data URLs cannot.
  QueueItem? get _bookmarkableItem {
    final idx = _player.currentIndex;
    if (idx < 0 || idx >= _player.queue.length) return null;
    final item = _player.queue[idx];
    return HistoryStore.canBookmark(item.originalUrl ?? item.url) ? item : null;
  }

  Future<void> _toggleCurrentBookmark() async {
    final item = _bookmarkableItem;
    if (item == null) return;
    final url = item.originalUrl ?? item.url;
    final history = widget.history;
    if (history.isBookmarked(url)) {
      await history.toggleFavorite(url);
    } else {
      // Use the cast's own artwork when it has some; otherwise keep a frame
      // of the video as the card's picture.
      final artwork = [item.backdropUrl, item.posterUrl, item.artworkUrl]
          .whereType<String>()
          .where((u) => u.isNotEmpty)
          .firstOrNull;
      String? thumbnail;
      if (artwork == null && item.mediaKind == MediaKind.video) {
        final frame = await _player.engine.captureFrame();
        if (frame != null) thumbnail = await saveBookmarkThumbnail(frame, url);
      }
      await history.bookmark(
        url: url,
        title: item.title,
        imageUrl: artwork,
        thumbnailPath: thumbnail,
        // A private cast stays out of Recent even when bookmarked.
        recordable: !item.skipHistory,
        playlistBody: item.playlistBody,
        audioUrl: item.audioUrl,
        headers: item.originalHeaders ?? item.headers,
        contentType: item.contentType,
        mediaKind: item.mediaKind.wireValue,
        displayDurationMs: item.displayDurationMs,
      );
    }
    if (!mounted) return;
    setState(() {});
    _showOsd(history.isBookmarked(url) ? 'Bookmarked' : 'Bookmark removed');
  }

  void _openDevices() => setState(() {
        _dest = _Dest.devices;
        _showingVideo = false;
      });

  @override
  void dispose() {
    _memoryDiagnostics.dispose();
    _osdTimer?.cancel();
    _dropPointer.dispose();
    _chromeIdleTimer?.cancel();
    _keyboardFocusNode.dispose();
    windowManager.removeListener(this);
    _player.removeListener(_handlePlayerChange);
    _player.playRequests.removeListener(_handlePlayRequest);
    _server.removeListener(_handlePairingPrompt);
    _tray.dispose();
    _sender.removeListener(_handleSenderChange);
    if (_pendingCastFile != null) _sender.removeListener(_maybeCastPendingFile);
    unawaited(_mediaSession.dispose());
    _extBridge.stop();
    unawaited(_togetherToasts?.cancel());
    _together.dispose();
    _sender.dispose();
    _discovery.stop();
    _server.stop();
    unawaited(StreamProxyServer.instance.stop());
    _stillWatching.removeListener(_handleStillWatchingChange);
    _stillWatching.dispose();
    _player.dispose();
    _showStats.dispose();
    _updateChecker.dispose();
    super.dispose();
  }

  Future<Map<String, Object?>> _readMemorySnapshot() async {
    final engine = _player.engine;
    final index = _player.currentIndex;
    final item = index >= 0 && index < _player.queue.length
        ? _player.queue[index]
        : null;
    final context = <String, Object?>{
      'appVersion': kAppVersion,
      'engine': _player.engineType.name,
      'state': _player.state,
      'opening': _player.isOpening,
      'queueLength': _player.queue.length,
      'queueRevision': _player.queueRevision,
      'itemIndex': index,
      'mediaKind': item?.mediaKind.name,
      'positionMs': _player.positionMs,
      'durationMs': _player.durationMs,
      'hardwareVideoOutput': _player.hardwareVideoOutput,
      'proxied': item != null && StreamProxyServer.instance.ownsUrl(item.url),
      'proxyRunning': StreamProxyServer.instance.isRunning,
    };
    final snapshots = await Future.wait([
      readProcessMemory(),
      if (engine is MpvEngine && item?.mediaKind == MediaKind.video)
        engine.readMemoryDiagnostics(),
    ]);
    return {
      ...context,
      for (final snapshot in snapshots) ...snapshot,
      'engineChangedDuringSample': !identical(engine, _player.engine),
    };
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
        title: 'PlayBridge Desktop',
        scaffoldMessengerKey: _messengerKey,
        debugShowCheckedModeBanner: false,
        theme: buildPbTheme(),
        home: Shortcuts(
          shortcuts: {
            const SingleActivator(LogicalKeyboardKey.space):
                const PlayPauseIntent(),

            const SingleActivator(LogicalKeyboardKey.arrowUp):
                const VolumeUpIntent(),
            const SingleActivator(LogicalKeyboardKey.arrowDown):
                const VolumeDownIntent(),
            // mpv convention: `[` slower, `]` faster.
            const SingleActivator(LogicalKeyboardKey.bracketLeft):
                const SpeedDownIntent(),
            const SingleActivator(LogicalKeyboardKey.bracketRight):
                const SpeedUpIntent(),
            const SingleActivator(LogicalKeyboardKey.keyZ):
                const CycleScalingIntent(),
            const SingleActivator(LogicalKeyboardKey.keyG):
                const SubtitleDelayMinusIntent(),
            const SingleActivator(LogicalKeyboardKey.keyH):
                const SubtitleDelayPlusIntent(),
            // mpv convention: PageUp / PageDown jump between chapters.
            const SingleActivator(LogicalKeyboardKey.pageUp):
                const PreviousChapterIntent(),
            const SingleActivator(LogicalKeyboardKey.pageDown):
                const NextChapterIntent(),
            const SingleActivator(LogicalKeyboardKey.keyI):
                const StatsToggleIntent(),
            const SingleActivator(LogicalKeyboardKey.keyB):
                const BookmarkIntent(),
            const SingleActivator(LogicalKeyboardKey.keyF):
                const ToggleFullScreenIntent(),
            // `?` is Shift+/ on US keyboards.
            const SingleActivator(LogicalKeyboardKey.slash, shift: true):
                const ShowShortcutsIntent(),
            const SingleActivator(LogicalKeyboardKey.slash):
                const ShowShortcutsIntent(),
          },
          child: Actions(
            actions: {
              PlayPauseIntent: CallbackAction<PlayPauseIntent>(
                onInvoke: (_) {
                  _togglePlayPause();
                  return null;
                },
              ),
              SeekForwardIntent: CallbackAction<SeekForwardIntent>(
                onInvoke: (_) {
                  _seekBy(10000, osd: '≫ 10s');
                  return null;
                },
              ),
              SeekBackwardIntent: CallbackAction<SeekBackwardIntent>(
                onInvoke: (_) {
                  _seekBy(-10000, osd: '≪ 10s');
                  return null;
                },
              ),
              PreviousChapterIntent: CallbackAction<PreviousChapterIntent>(
                onInvoke: (_) {
                  _jumpChapter(forward: false);
                  return null;
                },
              ),
              NextChapterIntent: CallbackAction<NextChapterIntent>(
                onInvoke: (_) {
                  _jumpChapter(forward: true);
                  return null;
                },
              ),
              VolumeUpIntent: CallbackAction<VolumeUpIntent>(
                onInvoke: (_) {
                  _nudgeVolume(0.05);
                  return null;
                },
              ),
              VolumeDownIntent: CallbackAction<VolumeDownIntent>(
                onInvoke: (_) {
                  _nudgeVolume(-0.05);
                  return null;
                },
              ),
              SpeedDownIntent: CallbackAction<SpeedDownIntent>(
                onInvoke: (_) {
                  _stepPlaybackSpeed(-1);
                  return null;
                },
              ),
              SpeedUpIntent: CallbackAction<SpeedUpIntent>(
                onInvoke: (_) {
                  _stepPlaybackSpeed(1);
                  return null;
                },
              ),
              CycleScalingIntent: CallbackAction<CycleScalingIntent>(
                onInvoke: (_) {
                  _cycleVideoScaling();
                  return null;
                },
              ),
              SubtitleDelayMinusIntent:
                  CallbackAction<SubtitleDelayMinusIntent>(
                onInvoke: (_) {
                  _nudgeSubtitleDelay(-subtitleDelayFineMs);
                  return null;
                },
              ),
              SubtitleDelayPlusIntent: CallbackAction<SubtitleDelayPlusIntent>(
                onInvoke: (_) {
                  _nudgeSubtitleDelay(subtitleDelayFineMs);
                  return null;
                },
              ),
              BookmarkIntent: CallbackAction<BookmarkIntent>(
                onInvoke: (_) {
                  unawaited(_toggleCurrentBookmark());
                  return null;
                },
              ),
              StatsToggleIntent: CallbackAction<StatsToggleIntent>(
                onInvoke: (_) {
                  _showStats.value = !_showStats.value;
                  return null;
                },
              ),
              ToggleFullScreenIntent: CallbackAction<ToggleFullScreenIntent>(
                onInvoke: (_) {
                  unawaited(_toggleFullScreen());
                  return null;
                },
              ),
              ShowShortcutsIntent: CallbackAction<ShowShortcutsIntent>(
                onInvoke: (_) {
                  unawaited(showKeyboardShortcutsSheet(context));
                  return null;
                },
              ),
            },
            child: Focus(
              focusNode: _keyboardFocusNode,
              autofocus: true,
              onKeyEvent: (_, event) {
                if (event is KeyDownEvent && !_stillWatching.isPrompting) {
                  _stillWatching.recordUserActivity();
                }
                final left = event.logicalKey == LogicalKeyboardKey.arrowLeft;
                final right = event.logicalKey == LogicalKeyboardKey.arrowRight;
                if (left || right) {
                  if (event is KeyUpEvent) {
                    _seekRepeatCount = 0;
                    _seekRepeatKey = null;
                    return KeyEventResult.ignored;
                  }
                  if (event is KeyDownEvent || event is KeyRepeatEvent) {
                    if (event is KeyDownEvent ||
                        _seekRepeatKey != event.logicalKey) {
                      _seekRepeatCount = 0;
                      _seekRepeatKey = event.logicalKey;
                    } else {
                      _seekRepeatCount++;
                    }
                    final step = seekStepMs(_seekRepeatCount);
                    final label = step >= 50000 ? '50s' : '10s';
                    _seekBy(
                      left ? -step : step,
                      osd: left ? '≪ $label' : '≫ $label',
                    );
                    return KeyEventResult.handled;
                  }
                }
                return KeyEventResult.ignored;
              },
              child: Listener(
                behavior: HitTestBehavior.translucent,
                onPointerDown: (_) {
                  if (_stillWatching.isPrompting) {
                    unawaited(_stillWatching.continueWatching());
                  } else {
                    _stillWatching.recordUserActivity();
                  }
                  if (!_keyboardFocusNode.hasFocus) {
                    _keyboardFocusNode.requestFocus();
                  }
                },
                onPointerHover: (_) => _stillWatching.recordUserActivity(),
                child: Scaffold(
                  backgroundColor: PbColors.background,
                  // Deliberately NOT listening to _player here: it notifies on every
                  // ~200ms position tick, and rebuilding the whole shell at 5Hz steals frame time from the video. The
                  // controls bar listens to the player itself; structural
                  // changes arrive via the coarse setState in _handlePlayerChange.
                  body: AnimatedBuilder(
                    animation: Listenable.merge([_server, _showStats]),
                    builder: (context, _) {
                      final hasMedia = _player.queue.isNotEmpty;
                      final hasQueue = _player.queue.length > 1;
                      final screenMirroring = _server.screenMirror.isActive;
                      const titleBarHeight = 28.0;
                      // Hide every piece of chrome (title bar, sidebar, status bar)
                      // when the user is watching video in full-screen — the video
                      // should fill the entire monitor, not be framed by panels.
                      final hideChrome = _isFullScreen &&
                          ((_showingVideo && hasMedia) || screenMirroring);

                      return Stack(
                        fit: StackFit.expand,
                        children: [
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (!hideChrome)
                                const SizedBox(
                                  height: titleBarHeight,
                                  child: DragToMoveArea(
                                    child: Row(
                                      children: [
                                        SizedBox(
                                          width: PbMetrics.sidebarWidth,
                                          child: ColoredBox(
                                              color: PbColors.sidebar),
                                        ),
                                        Expanded(child: SizedBox.expand()),
                                      ],
                                    ),
                                  ),
                                ),
                              LinkedPlaybackBanner(
                                  coordinator: _extBridge.linkedPlayback),
                              Expanded(
                                child: Row(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    if (!hideChrome)
                                      NavSidebar(
                                        selected: _showingVideo ||
                                                screenMirroring ||
                                                _dest == _Dest.nowCasting
                                            ? null
                                            : _sidebarSelection,
                                        status: _receiverStatus,
                                        screenMirroring: screenMirroring,
                                        onSelect: (d) => setState(() {
                                          _dest = switch (d) {
                                            NavDest.home => _Dest.home,
                                            NavDest.bookmarks =>
                                              _Dest.bookmarks,
                                            NavDest.devices => _Dest.devices,
                                            NavDest.together => _Dest.together,
                                            NavDest.settings => _Dest.settings,
                                          };
                                          _showingVideo = false;
                                        }),
                                      ),
                                    Expanded(
                                      child: DropTarget(
                                        key: _dropAreaKey,
                                        onDragEntered: (d) {
                                          _dropPointer.value = d.localPosition;
                                          setState(() => _mainDragging = true);
                                        },
                                        onDragUpdated: (d) => _dropPointer
                                            .value = d.localPosition,
                                        onDragExited: (_) {
                                          _dropPointer.value = null;
                                          setState(() => _mainDragging = false);
                                        },
                                        onDragDone: _onMainDrop,
                                        child: MouseRegion(
                                          cursor: _chromeMode ==
                                                  PlayerChromeMode.hidden
                                              ? SystemMouseCursors.none
                                              : MouseCursor.defer,
                                          onHover: _showingVideo && hasMedia
                                              ? _onPlayerHover
                                              : null,
                                          child: Stack(
                                            fit: StackFit.expand,
                                            children: [
                                              // Video is always in the tree (Offstage) so
                                              // mpv is never torn down on screen switch.
                                              Positioned.fill(
                                                child: Offstage(
                                                  offstage: !_showingVideo ||
                                                      !hasMedia ||
                                                      screenMirroring,
                                                  child: Container(
                                                    color: Colors.black,
                                                    child:
                                                        MediaPresentationSurface(
                                                      controller: _player,
                                                      subtitleBottomInset:
                                                          switch (_chromeMode) {
                                                        PlayerChromeMode.full =>
                                                          subtitleBottomFull,
                                                        PlayerChromeMode.mini =>
                                                          subtitleBottomMini,
                                                        PlayerChromeMode
                                                              .hidden =>
                                                          subtitleBottomHidden,
                                                      },
                                                    ),
                                                  ),
                                                ),
                                              ),
                                              // Tap / double-tap video (not controls).
                                              // Below overlays so buttons/menus keep hits.
                                              if (_showingVideo && hasMedia)
                                                Positioned.fill(
                                                  child: Listener(
                                                    behavior: HitTestBehavior
                                                        .translucent,
                                                    onPointerDown: (event) {
                                                      if (event.buttons !=
                                                          kPrimaryButton) {
                                                        return;
                                                      }
                                                      _onVideoSurfaceClick();
                                                    },
                                                    onPointerUp: (_) {
                                                      if (!_fullScreenOnRelease) {
                                                        return;
                                                      }
                                                      _fullScreenOnRelease =
                                                          false;
                                                      final entering =
                                                          !_isFullScreen;
                                                      unawaited(
                                                          _toggleFullScreen());
                                                      _showOsd(entering
                                                          ? 'Fullscreen'
                                                          : 'Windowed');
                                                    },
                                                    onPointerCancel: (_) =>
                                                        _fullScreenOnRelease =
                                                            false,
                                                  ),
                                                ),
                                              if (!_showingVideo &&
                                                  !screenMirroring)
                                                Positioned.fill(
                                                    child: _buildScreen()),
                                              if (screenMirroring)
                                                Positioned.fill(
                                                  child: ScreenMirrorSurface(
                                                    receiver:
                                                        _server.screenMirror,
                                                    onToggleFullScreen: () =>
                                                        unawaited(
                                                            _toggleFullScreen()),
                                                  ),
                                                ),
                                              if (_showingVideo &&
                                                  hasMedia &&
                                                  _showStats.value &&
                                                  _player.currentMediaKind ==
                                                      MediaKind.video &&
                                                  _player.engine is MpvEngine)
                                                Positioned(
                                                  top: 88,
                                                  left: 16,
                                                  child: StatsOverlay(
                                                    engine: _player.engine
                                                        as MpvEngine,
                                                    player: _player,
                                                  ),
                                                ),
                                              // Title, controls, skip button and the
                                              // Subtitles / Queue / More side panels.
                                              if (_showingVideo && hasMedia)
                                                Positioned.fill(
                                                  child: PlayerChrome(
                                                    player: _player,
                                                    onBack: () => setState(() =>
                                                        _showingVideo = false),
                                                    onBookmark: _bookmarkableItem ==
                                                            null
                                                        ? null
                                                        : _toggleCurrentBookmark,
                                                    bookmarked: _bookmarkableItem ==
                                                            null
                                                        ? false
                                                        : widget.history.isBookmarked(
                                                            _bookmarkableItem!
                                                                    .originalUrl ??
                                                                _bookmarkableItem!
                                                                    .url),
                                                    mode: _chromeMode,
                                                    showQueueControls: hasQueue,
                                                    onPanelOpened: () {
                                                      setState(
                                                          () => _menusOpen++);
                                                      _applyChrome();
                                                    },
                                                    onPanelClosed: () {
                                                      setState(
                                                        () => _menusOpen =
                                                            (_menusOpen - 1)
                                                                .clamp(0, 99),
                                                      );
                                                      _applyChrome();
                                                    },
                                                    onScrubbing: (value) {
                                                      _scrubbing = value;
                                                      _applyChrome();
                                                    },
                                                    isFullScreen: _isFullScreen,
                                                    onToggleFullScreen:
                                                        _toggleFullScreen,
                                                    onToggleProxy: () async {
                                                      final wasProxied = _player
                                                          .isCurrentItemProxied;
                                                      final switched =
                                                          await _player
                                                              .toggleProxy();
                                                      if (switched && mounted) {
                                                        _showOsd(wasProxied
                                                            ? 'Direct playback'
                                                            : 'Proxied playback');
                                                      }
                                                    },
                                                    showStats: _showStats,
                                                    segments: _skipSegments,
                                                    lastSkipped: _lastSkipped,
                                                    autoPrefs: _skipAutoPrefs,
                                                    onSkip: _performSkip,
                                                    prePlayActive:
                                                        _prePlayItem != null,
                                                  ),
                                                ),
                                              // Pre-play screen for casts with
                                              // metadata; sits above everything.
                                              if (_showingVideo &&
                                                  _prePlayItem != null)
                                                Positioned.fill(
                                                  child: PrePlayOverlay(
                                                    key: ValueKey(
                                                        _prePlayItem!.url),
                                                    item: _prePlayItem!,
                                                    onStart: _dismissPrePlay,
                                                    onStartOver: () {
                                                      unawaited(_player
                                                          .seek(Duration.zero));
                                                      _dismissPrePlay();
                                                    },
                                                  ),
                                                ),
                                              if (_mainDragging)
                                                Positioned.fill(
                                                  child: ListenableBuilder(
                                                    listenable:
                                                        Listenable.merge([
                                                      _sender,
                                                      _dropPointer
                                                    ]),
                                                    builder: (context, _) =>
                                                        MainDropOverlay(
                                                      computerName: widget
                                                          .store.deviceName,
                                                      tvName: playsOnTv(_sender)
                                                          ? _sender
                                                              .selectionName
                                                          : null,
                                                      pointer:
                                                          _dropPointer.value,
                                                    ),
                                                  ),
                                                ),
                                              if (_stillWatching.isPrompting)
                                                Positioned.fill(
                                                  child: StillWatchingPrompt(
                                                    remainingSeconds:
                                                        _stillWatching
                                                            .remainingSeconds,
                                                    title: _player.currentTitle,
                                                    onContinue: () => unawaited(
                                                        _stillWatching
                                                            .continueWatching()),
                                                    onStopNow: () => unawaited(
                                                        _stillWatching
                                                            .stopWatching()),
                                                  ),
                                                ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              // Playback bar: something is playing but its full
                              // view (player or remote view) is not on screen.
                              if (!hideChrome &&
                                  !screenMirroring &&
                                  hasMedia &&
                                  !_showingVideo)
                                LocalPlaybackBar(
                                  player: _player,
                                  onOpen: () =>
                                      setState(() => _showingVideo = true),
                                )
                              else if (!hideChrome &&
                                  !screenMirroring &&
                                  !hasMedia &&
                                  _sender.isCasting &&
                                  _dest != _Dest.nowCasting)
                                RemotePlaybackBar(
                                  sender: _sender,
                                  onOpen: () =>
                                      setState(() => _dest = _Dest.nowCasting),
                                ),
                            ],
                          ),
                          // Self-update dialogs/banners; renders nothing while idle.
                          UpdateGate(checker: _updateChecker, sender: _sender),
                          // Phone pairing request, over whatever is on screen.
                          Positioned.fill(
                            child: PairRequestOverlay(
                              phase: _server.phase,
                              pendingRequest: _server.pendingPairingRequest,
                              onDeny: _server.denyPairing,
                            ),
                          ),
                          if (_osdMessage != null)
                            Positioned.fill(
                                child: PlaybackOsd(message: _osdMessage!)),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        ));
  }

  /// Sidebar highlight for the current destination. The remote view has no
  /// sidebar entry (it opens from the playback bar), so it highlights nothing.
  NavDest get _sidebarSelection => switch (_dest) {
        _Dest.home => NavDest.home,
        _Dest.bookmarks => NavDest.bookmarks,
        _Dest.devices || _Dest.nowCasting => NavDest.devices,
        _Dest.together => NavDest.together,
        _Dest.settings => NavDest.settings,
      };

  ReceiverStatusInfo get _receiverStatus => describeReceiverStatus(
        deviceName: widget.store.deviceName,
        hostInfo: _hostInfo,
        phase: _server.phase,
        serverError: _serverError,
        discoveryError: _discoveryError,
        tlsError: _server.tlsError,
      );

  Widget _buildScreen() {
    return switch (_dest) {
      _Dest.home => HomeScreen(
          store: widget.store,
          history: widget.history,
          sender: _sender,
          deviceName: widget.store.deviceName,
          status: _receiverStatus,
          enableHistory:
              widget.history.recentsEnabled && widget.store.enableHistory,
          onChooseFiles: () => unawaited(_chooseFiles()),
          onPasteLink: () => unawaited(_pasteLink()),
          onFindDevices: _openDevices,
          onSeeBookmarks: () => setState(() => _dest = _Dest.bookmarks),
          onPlayHistoryItem: (item) => unawaited(_playHistoryItem(item)),
          onMessage: _showOsd,
        ),
      _Dest.bookmarks => BookmarksScreen(
          store: widget.history,
          player: _player,
          enableHistory:
              widget.history.recentsEnabled && widget.store.enableHistory,
          onNavigateToNowPlaying: () => setState(() => _showingVideo = true),
          onPlayOnTarget: playsOnTv(_sender)
              ? (item) => unawaited(_playHistoryItem(item))
              : null,
          playOnTargetLabel: 'Play on ${_sender.selectionName}',
        ),
      _Dest.devices => DevicesScreen(
          sender: _sender,
          store: widget.store,
          onPairedDevicesChanged: _server.refreshAuthorizedTokens,
          onOpenNowCasting: () => setState(() => _dest = _Dest.nowCasting),
        ),
      _Dest.nowCasting => NowCastingScreen(
          controller: _sender,
          onBack: () => setState(() => _dest = _Dest.home),
        ),
      _Dest.together => TogetherScreen(
          together: _together,
          onOpenVideo: _player.queue.isEmpty
              ? null
              : () => setState(() => _showingVideo = true),
        ),
      _Dest.settings => SettingsScreen(
          server: _server,
          store: widget.store,
          player: _player,
          showStats: _showStats,
          updateChecker: _updateChecker,
          onNavigateToCast: () => setState(() => _dest = _Dest.home),
          onSettingsChanged: () {
            _applyStillWatchingSettings();
            setState(() {});
          },
          onQuit: () => unawaited(_tray.quit()),
        ),
    };
  }
}

// ─── Navigation sidebar ──────────────────────────────────────────────────────
