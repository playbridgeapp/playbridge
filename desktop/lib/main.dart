import 'dart:async';
import 'dart:io';
import 'dart:ui' show ImageFilter, PlatformDispatcher;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';

import 'package:desktop_drop/desktop_drop.dart';

import 'logging/log_store.dart';
import 'context_menu_installer.dart';
import 'discovery.dart';
import 'engines/mpv_engine.dart';
import 'extension_bridge.dart';
import 'favorites_screen.dart';
import 'history_screen.dart';
import 'history_store.dart';
import 'keyboard_shortcuts_sheet.dart';
import 'media_session_bridge.dart';
import 'native_host_installer.dart';
import 'now_casting_screen.dart';
import 'pairing_store.dart';
import 'pair_screen.dart';
import 'playback_osd.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'playback_surface.dart';
import 'preplay_overlay.dart';
import 'send_to_tv_screen.dart';
import 'server.dart';
import 'settings_screen.dart';
import 'stream_proxy_server.dart';
import 'shader_background.dart';
import 'stats_overlay.dart';
import 'still_watching_controller.dart';
import 'still_watching_prompt.dart';
import 'tray_controller.dart';
import 'tv_connection_store.dart';
import 'tv_sender_controller.dart';
import 'update/update_checker.dart';
import 'update/update_gate.dart';

Future<void> main(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();

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
    // "Play on TV" cold-start: the cast helper launches the app with these when
    // it isn't already running. The app casts the file once a TV connects.
    initialCastFile: _argValue(args, '--cast-file'),
    initialCastTitle: _argValue(args, '--cast-title'),
  ));
}

/// Reads `--flag value` from argv (null if absent).
String? _argValue(List<String> args, String flag) {
  final i = args.indexOf(flag);
  return (i >= 0 && i + 1 < args.length) ? args[i + 1] : null;
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

class StatsToggleIntent extends Intent {
  const StatsToggleIntent();
}

// Navigation destinations — nowPlaying is a mode, not a persistent screen.
enum _Dest { cast, sendToTv, nowCasting, history, favorites, settings }

class ReceiverApp extends StatefulWidget {
  const ReceiverApp({
    super.key,
    required this.store,
    required this.history,
    required this.tvStore,
    this.initialCastFile,
    this.initialCastTitle,
  });

  final PairingStore store;
  final HistoryStore history;
  final TvConnectionStore tvStore;
  final String? initialCastFile;
  final String? initialCastTitle;

  @override
  State<ReceiverApp> createState() => _ReceiverAppState();
}

class _ReceiverAppState extends State<ReceiverApp> with WindowListener {
  final FocusNode _keyboardFocusNode =
      FocusNode(debugLabel: 'desktop-shortcuts');
  late final PlayerController _player;
  late final ReceiverServer _server;
  late final DiscoveryPublisher _discovery;
  late final TrayController _tray;
  late final TvSenderController _sender;
  late final ExtensionBridge _extBridge;
  late final MediaSessionBridge _mediaSession;
  late final StillWatchingController _stillWatching;
  final UpdateChecker _updateChecker = UpdateChecker();

  bool _hadMedia = false;
  String? _lastTrackedUrl;

  /// Last coarse player state the shell was built for. The root builder no
  /// longer listens to the player directly (see build), so real transitions
  /// (queue, state, index) trigger a setState here instead.
  (bool, int, String, int)? _lastCoarse;

  // Pre-play screen state: the item being introduced, and the volume to
  // restore once playback starts (video buffers muted behind the overlay).
  QueueItem? _prePlayItem;
  double? _prePlayPrevVolume;

  String _hostInfo = 'starting…';
  String? _serverError;
  String? _discoveryError;

  _Dest _dest = _Dest.cast;
  bool _showingVideo = false;
  bool _isFullScreen = false;

  // Live playback-stats overlay. Toggled by the `i` hotkey or the Settings
  // switch; both mutate this notifier and the change is persisted via the store.
  late final ValueNotifier<bool> _showStats;

  // Controls visibility for the video overlay
  bool _videoHovered = false;
  int _menusOpen = 0;
  bool _playlistDrawerOpen = false;
  Timer? _hideTimer;
  static const _autoHide = Duration(seconds: 2);

  /// Center OSD (pause / seek / volume); cleared by [_osdTimer].
  String? _osdMessage;
  Timer? _osdTimer;

  /// Debounces single-tap play/pause so double-tap fullscreen isn't stolen.
  Timer? _tapTimer;
  bool _mainDragging = false;

  static const _mediaExts = {
    'mp4',
    'm4v',
    'mkv',
    'webm',
    'avi',
    'mov',
    'wmv',
    'flv',
    'mp3',
    'flac',
    'm4a',
    'aac',
    'ogg',
    'wav',
  };

  void _markActive() {
    if (!_videoHovered) setState(() => _videoHovered = true);
    _hideTimer?.cancel();
    _hideTimer = Timer(_autoHide, () {
      if (!mounted) return;
      if (_menusOpen > 0 || _playlistDrawerOpen) return;
      setState(() => _videoHovered = false);
    });
  }

  void _markInactive() {
    _hideTimer?.cancel();
    if (_menusOpen > 0 || _playlistDrawerOpen) return;
    if (_videoHovered) setState(() => _videoHovered = false);
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
    );
    _discovery = DiscoveryPublisher(
      serviceName: widget.store.deviceName,
      deviceId: widget.store.deviceId,
    );
    _tray =
        TrayController(player: _player, server: _server, store: widget.store);
    _sender = TvSenderController(identity: widget.store, store: widget.tvStore);
    _extBridge = ExtensionBridge(
      _sender,
      _player,
      isPlaybackPromptActive: () => _stillWatching.isPrompting,
      onPromptContinue: () => unawaited(_stillWatching.continueWatching()),
      onPromptStop: () => unawaited(_stillWatching.stopWatching()),
      onPlaybackActivity: _stillWatching.recordUserActivity,
      onNewMedia: _stillWatching.resetForNewMedia,
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
    if (widget.initialCastFile != null) {
      _pendingCastFile = widget.initialCastFile;
      _sender.addListener(_maybeCastPendingFile);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _resolvePendingCastFile();
      });
    }
  }

  bool _senderWasCasting = false;
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
    final casting = _sender.isCasting;
    if (casting == _senderWasCasting) return; // only react to edges
    _senderWasCasting = casting;
    setState(() {
      if (casting && !_showingVideo) {
        // Cast started → jump to the Now Playing tab.
        _dest = _Dest.nowCasting;
      } else if (!casting && _dest == _Dest.nowCasting) {
        // Cast ended while on Now Playing (now hidden) → fall back to Send to TV.
        _dest = _Dest.sendToTv;
      }
      // setState also rebuilds the sidebar so the Now Playing item shows/hides.
    });
  }

  String? _pendingCastFile;

  /// Rising edge: TV connected while a cold-start file is still pending → TV.
  void _maybeCastPendingFile() {
    if (_pendingCastFile == null || !_sender.isConnected) return;
    _resolvePendingCastFile();
  }

  /// One-shot: send [initialCastFile] to the TV if linked, else play locally.
  void _resolvePendingCastFile() {
    final path = _pendingCastFile;
    if (path == null) return;
    _pendingCastFile = null;
    _sender.removeListener(_maybeCastPendingFile);
    final file = File(path);
    if (!file.existsSync()) {
      debugPrint('[main] pending cast file missing: $path');
      return;
    }
    final title = widget.initialCastTitle;
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
    ));
  }

  Future<void> _initTrayAndWindow() async {
    await _tray.init();
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
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
    if (item == null || !item.hasPrePlayMetadata) return;
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

    // Coarse shell rebuild on real transitions only (the root builder doesn't
    // listen to the player; per-frame position ticks stay out of the shell).
    final coarse = (
      hasMedia,
      _player.queue.length,
      _player.state,
      _player.currentIndex,
    );
    if (coarse != _lastCoarse) {
      _lastCoarse = coarse;
      if (mounted) setState(() {});
    }

    // Record history when the active track changes.
    if (hasMedia) {
      final idx = _player.currentIndex;
      if (idx >= 0 && idx < _player.queue.length) {
        final item = _player.queue[idx];
        if (item.url != _lastTrackedUrl) {
          _lastTrackedUrl = item.url;
          unawaited(widget.history.addOrBump(item.url, item.title));
        }
      }
    }
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

    unawaited(_revealWindow(fullScreen: false));
    if (!mounted) return;
    setState(() {
      // Pair UI lives on the Cast tab; leave the video view so the code is
      // visible even if something was playing.
      _dest = _Dest.cast;
      _showingVideo = false;
    });
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
    if (widget.store.pauseOnWindowHide &&
        (_player.state == 'playing' || _player.state == 'buffering')) {
      await _player.pause();
    }
    await windowManager.hide();
  }

  @override
  void onWindowEnterFullScreen() {
    if (mounted) setState(() => _isFullScreen = true);
  }

  @override
  void onWindowLeaveFullScreen() {
    if (mounted) setState(() => _isFullScreen = false);
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

  void _showOsd(String message) {
    _osdTimer?.cancel();
    if (!mounted) return;
    setState(() => _osdMessage = message);
    _osdTimer = Timer(const Duration(milliseconds: 1100), () {
      if (mounted) setState(() => _osdMessage = null);
    });
  }

  void _togglePlayPause({bool withOsd = true}) {
    if (_player.state == 'playing') {
      unawaited(_player.pause());
      if (withOsd) _showOsd('Paused');
    } else if (_player.state == 'paused' || _player.state == 'buffering') {
      unawaited(_player.resume());
      if (withOsd) _showOsd('Play');
    }
  }

  void _seekBy(int deltaMs, {required String osd}) {
    final dur = _player.durationMs;
    var target = _player.positionMs + deltaMs;
    if (target < 0) target = 0;
    if (dur > 0 && target > dur) target = dur;
    unawaited(_player.seek(Duration(milliseconds: target)));
    _showOsd(osd);
  }

  void _nudgeVolume(double delta) {
    final next = (_player.volume + delta).clamp(0.0, 1.0);
    unawaited(_player.setVolume(next));
    _showOsd('Volume ${(next * 100).round()}%');
  }

  /// Drop files/URLs onto the main surface: cast if a TV is linked, else play here.
  void _onMainDrop(DropDoneDetails detail) {
    setState(() => _mainDragging = false);
    final files = <File>[];
    final urls = <String>[];
    for (final f in detail.files) {
      final path = f.path;
      if (path.startsWith('http://') || path.startsWith('https://')) {
        urls.add(path);
        continue;
      }
      final ext = _fileExt(path);
      if (_mediaExts.contains(ext)) files.add(File(path));
    }
    if (files.isEmpty && urls.isEmpty) {
      _showOsd('No media in drop');
      return;
    }

    final tvLinked = _sender.isConnected;
    if (tvLinked) {
      if (files.isNotEmpty) {
        unawaited(_sender.castLocalFiles(files).then((ok) {
          if (!ok) _showOsd('Cast failed');
        }));
      }
      for (final u in urls) {
        _sender.castUrl(u);
      }
      if (files.isNotEmpty || urls.isNotEmpty) {
        _showOsd('Casting…');
        setState(() {
          _dest = _Dest.nowCasting;
          _showingVideo = false;
        });
      }
      return;
    }

    // Local play: URLs + files as a playlist.
    final items = <QueueItem>[
      for (final u in urls)
        QueueItem(url: u, title: u.split('/').last.split('?').first),
      for (final f in files)
        QueueItem(
          url: f.uri.toString(),
          title:
              f.uri.pathSegments.isNotEmpty ? f.uri.pathSegments.last : f.path,
        ),
    ];
    if (items.isEmpty) return;
    unawaited(_player.playPlaylist(items, 0, isRemote: true));
    _showOsd(items.length == 1 ? 'Playing' : '${items.length} items');
  }

  static String _fileExt(String path) {
    final dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1).toLowerCase() : '';
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _osdTimer?.cancel();
    _tapTimer?.cancel();
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

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
        title: 'PlayBridge Desktop',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark(useMaterial3: true).copyWith(
          scaffoldBackgroundColor: Colors.transparent,
          canvasColor: Colors.transparent,
          // Brand font shared with the Android apps. Applies app-wide through the
          // text theme; explicit monospace styles (logs) are unaffected.
          textTheme: ThemeData.dark(useMaterial3: true).textTheme.apply(
                fontFamily: 'Poppins',
              ),
          primaryTextTheme: ThemeData.dark(useMaterial3: true)
              .primaryTextTheme
              .apply(fontFamily: 'Poppins'),
        ),
        home: Shortcuts(
          shortcuts: {
            const SingleActivator(LogicalKeyboardKey.space):
                const PlayPauseIntent(),
            const SingleActivator(LogicalKeyboardKey.arrowRight):
                const SeekForwardIntent(),
            const SingleActivator(LogicalKeyboardKey.arrowLeft):
                const SeekBackwardIntent(),
            const SingleActivator(LogicalKeyboardKey.arrowUp):
                const VolumeUpIntent(),
            const SingleActivator(LogicalKeyboardKey.arrowDown):
                const VolumeDownIntent(),
            const SingleActivator(LogicalKeyboardKey.keyI):
                const StatsToggleIntent(),
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
                  backgroundColor: Colors.transparent,
                  // Deliberately NOT listening to _player here: it notifies on every
                  // ~200ms position tick, and rebuilding the whole shell (backdrop
                  // blurs included) at 5Hz steals frame time from the video. The
                  // controls/status bars listen to the player themselves; structural
                  // changes arrive via the coarse setState in _handlePlayerChange.
                  body: AnimatedBuilder(
                    animation: Listenable.merge([_server, _showStats]),
                    builder: (context, _) {
                      final hasMedia = _player.queue.isNotEmpty;
                      final hasQueue = _player.queue.length > 1;
                      const titleBarHeight = 28.0;
                      // Hide every piece of chrome (title bar, sidebar, status bar)
                      // when the user is watching video in full-screen — the video
                      // should fill the entire monitor, not be framed by panels.
                      final hideChrome =
                          _isFullScreen && _showingVideo && hasMedia;

                      return Stack(
                        fit: StackFit.expand,
                        children: [
                          // Aurora background — only rendered when the user is NOT
                          // watching video. Six-octave FBM + domain warping is expensive,
                          // and during playback the video texture covers most of it
                          // anyway, so leaving it running just steals GPU from mpv.
                          if (!_showingVideo)
                            const Positioned.fill(child: AuroraBackground()),

                          Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (!hideChrome)
                                SizedBox(
                                  height: titleBarHeight,
                                  child: ClipRect(
                                    child: BackdropFilter(
                                      filter: ImageFilter.blur(
                                          sigmaX: 30, sigmaY: 30),
                                      child: DragToMoveArea(
                                        child: Container(
                                          decoration: BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topCenter,
                                              end: Alignment.bottomCenter,
                                              colors: [
                                                Colors.white
                                                    .withValues(alpha: 0.10),
                                                Colors.black
                                                    .withValues(alpha: 0.20),
                                              ],
                                            ),
                                            border: Border(
                                              bottom: BorderSide(
                                                color: Colors.white
                                                    .withValues(alpha: 0.08),
                                              ),
                                            ),
                                          ),
                                          child: Row(
                                            children: [
                                              const SizedBox(width: 78),
                                              Expanded(
                                                child: Container(
                                                    color: Colors.transparent),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              Expanded(
                                child: Row(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    if (!hideChrome)
                                      _NavSidebar(
                                        dest: _dest,
                                        showingVideo: _showingVideo,
                                        hasMedia: hasMedia,
                                        playerState: _player.state,
                                        senderCasting: _sender.isCasting,
                                        enableHistory:
                                            widget.store.enableHistory,
                                        onDestSelect: (d) => setState(() {
                                          _dest = d;
                                          _showingVideo = false;
                                        }),
                                        onShowVideo: () => setState(
                                            () => _showingVideo = true),
                                      ),
                                    Expanded(
                                      child: DropTarget(
                                        onDragEntered: (_) => setState(
                                            () => _mainDragging = true),
                                        onDragExited: (_) => setState(
                                            () => _mainDragging = false),
                                        onDragDone: _onMainDrop,
                                        child: MouseRegion(
                                          onEnter: (_) => _markActive(),
                                          onExit: (_) => _markInactive(),
                                          onHover: (_) => _markActive(),
                                          child: Stack(
                                            fit: StackFit.expand,
                                            children: [
                                              // Video is always in the tree (Offstage) so
                                              // mpv is never torn down on screen switch.
                                              Positioned.fill(
                                                child: Offstage(
                                                  offstage: !_showingVideo ||
                                                      !hasMedia,
                                                  child: Container(
                                                    color: Colors.black,
                                                    child: PlaybackSurface(
                                                      controller: _player,
                                                      controlsVisible:
                                                          _videoHovered ||
                                                              _playlistDrawerOpen ||
                                                              _menusOpen > 0,
                                                    ),
                                                  ),
                                                ),
                                              ),
                                              // Tap / double-tap video (not controls).
                                              // Below overlays so buttons/menus keep hits.
                                              if (_showingVideo && hasMedia)
                                                Positioned.fill(
                                                  child: GestureDetector(
                                                    behavior: HitTestBehavior
                                                        .translucent,
                                                    onTap: () {
                                                      _markActive();
                                                      // Wait out a possible double-tap.
                                                      _tapTimer?.cancel();
                                                      _tapTimer = Timer(
                                                        const Duration(
                                                            milliseconds: 220),
                                                        () =>
                                                            _togglePlayPause(),
                                                      );
                                                    },
                                                    onDoubleTap: () {
                                                      _tapTimer?.cancel();
                                                      _markActive();
                                                      final entering =
                                                          !_isFullScreen;
                                                      unawaited(
                                                          _toggleFullScreen());
                                                      _showOsd(entering
                                                          ? 'Fullscreen'
                                                          : 'Windowed');
                                                    },
                                                  ),
                                                ),
                                              if (!_showingVideo)
                                                Positioned.fill(
                                                    child: _buildScreen()),
                                              if (_showingVideo &&
                                                  hasMedia &&
                                                  _showStats.value &&
                                                  _player.engine is MpvEngine)
                                                Positioned(
                                                  top: 16,
                                                  left: 16,
                                                  child: StatsOverlay(
                                                    engine: _player.engine
                                                        as MpvEngine,
                                                    player: _player,
                                                  ),
                                                ),
                                              if (_showingVideo && hasMedia)
                                                Positioned(
                                                  left: 0,
                                                  right: 0,
                                                  bottom: 0,
                                                  child: _PlayerControlsBar(
                                                    player: _player,
                                                    store: widget.store,
                                                    visible: _videoHovered ||
                                                        _playlistDrawerOpen ||
                                                        _menusOpen > 0,
                                                    showQueueControls: hasQueue,
                                                    onTogglePlaylist: () =>
                                                        setState(
                                                      () => _playlistDrawerOpen =
                                                          !_playlistDrawerOpen,
                                                    ),
                                                    playlistOpen:
                                                        _playlistDrawerOpen,
                                                    onMenuOpened: () =>
                                                        setState(
                                                            () => _menusOpen++),
                                                    onMenuClosed: () =>
                                                        setState(
                                                      () => _menusOpen =
                                                          (_menusOpen - 1)
                                                              .clamp(0, 99),
                                                    ),
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
                                                  ),
                                                ),
                                              if (_showingVideo &&
                                                  hasQueue &&
                                                  _playlistDrawerOpen)
                                                Positioned(
                                                  right: 0,
                                                  top: 0,
                                                  bottom: 0,
                                                  width: 360,
                                                  child: _PlaylistDrawer(
                                                    player: _player,
                                                    onClose: () => setState(
                                                      () =>
                                                          _playlistDrawerOpen =
                                                              false,
                                                    ),
                                                  ),
                                                ),
                                              // Title scrim along the top — same
                                              // visibility as the controls bar, so
                                              // the title is reachable in fullscreen.
                                              if (_showingVideo && hasMedia)
                                                Positioned(
                                                  left: 0,
                                                  right: 0,
                                                  top: 0,
                                                  child: _TitleOverlay(
                                                    player: _player,
                                                    visible: _videoHovered ||
                                                        _playlistDrawerOpen ||
                                                        _menusOpen > 0,
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
                                                  ),
                                                ),
                                              if (_osdMessage != null)
                                                PlaybackOsd(
                                                    message: _osdMessage!),
                                              if (_mainDragging)
                                                const Positioned.fill(
                                                  child: _MainDropOverlay(),
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
                              if (!hideChrome)
                                _StatusBar(
                                  player: _player,
                                  hostInfo: _hostInfo,
                                  serverError: _serverError,
                                  discoveryError: _discoveryError,
                                  phase: _server.phase,
                                ),
                            ],
                          ),
                          // Self-update dialogs/banners; renders nothing while idle.
                          UpdateGate(checker: _updateChecker),
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

  Widget _buildScreen() {
    return switch (_dest) {
      _Dest.cast => PairScreen(
          store: widget.store,
          deviceName: widget.store.deviceName,
          hostInfo: _hostInfo,
          port: _server.wssPort ?? kDefaultPort,
          phase: _server.phase,
          discoveryError: _discoveryError,
          tlsError: _server.tlsError,
          pendingRequest: _server.pendingPairingRequest,
          onDeny: _server.denyPairing,
        ),
      _Dest.sendToTv => SendToTvScreen(controller: _sender),
      _Dest.nowCasting => NowCastingScreen(controller: _sender),
      _Dest.history => HistoryScreen(
          store: widget.history,
          player: _player,
          onNavigateToNowPlaying: () => setState(() => _showingVideo = true),
        ),
      _Dest.favorites => FavoritesScreen(
          store: widget.history,
          player: _player,
          onNavigateToNowPlaying: () => setState(() => _showingVideo = true),
        ),
      _Dest.settings => SettingsScreen(
          server: _server,
          store: widget.store,
          player: _player,
          showStats: _showStats,
          updateChecker: _updateChecker,
          onNavigateToCast: () => setState(() => _dest = _Dest.cast),
          onSettingsChanged: () {
            _applyStillWatchingSettings();
            setState(() {
              if (!widget.store.enableHistory && _dest == _Dest.history) {
                _dest = _Dest.cast;
              }
            });
          },
          onQuit: () => unawaited(_tray.quit()),
        ),
    };
  }
}

// ─── Navigation sidebar ──────────────────────────────────────────────────────

class _NavSidebar extends StatelessWidget {
  const _NavSidebar({
    required this.dest,
    required this.showingVideo,
    required this.hasMedia,
    required this.playerState,
    required this.senderCasting,
    required this.enableHistory,
    required this.onDestSelect,
    required this.onShowVideo,
  });

  final _Dest dest;
  final bool showingVideo;
  final bool hasMedia;
  final String playerState;
  final bool senderCasting;
  final bool enableHistory;
  final ValueChanged<_Dest> onDestSelect;
  final VoidCallback onShowVideo;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
        child: Container(
          width: 196,
          decoration: BoxDecoration(
            // Subtle vertical gradient gives the sidebar a glassy, slightly
            // brighter top-edge highlight — like Apple's NSVisualEffectView.
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Colors.white.withValues(alpha: 0.08),
                Colors.black.withValues(alpha: 0.28),
              ],
            ),
            border: Border(
              right: BorderSide(color: Colors.white.withValues(alpha: 0.12)),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
                child: Row(
                  children: [
                    const Icon(Icons.cast_connected,
                        size: 16, color: Colors.tealAccent),
                    const SizedBox(width: 8),
                    const Text(
                      'PlayBridge',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.2,
                      ),
                    ),
                  ],
                ),
              ),
              // ── Receive: this computer plays what a phone casts to it ──
              const _NavSectionLabel('Receive'),
              if (hasMedia)
                _NavItem(
                  icon: playerState == 'playing'
                      ? Icons.play_circle
                      : Icons.pause_circle_outline,
                  label: 'Now Playing',
                  selected: showingVideo,
                  accent: true,
                  onTap: onShowVideo,
                ),
              _NavItem(
                icon: Icons.cast,
                label: 'Cast',
                selected: !showingVideo && dest == _Dest.cast,
                onTap: () => onDestSelect(_Dest.cast),
              ),
              if (enableHistory)
                _NavItem(
                  icon: Icons.history,
                  label: 'History',
                  selected: !showingVideo && dest == _Dest.history,
                  onTap: () => onDestSelect(_Dest.history),
                ),
              _NavItem(
                icon: Icons.star_border,
                label: 'Favorites',
                selected: !showingVideo && dest == _Dest.favorites,
                onTap: () => onDestSelect(_Dest.favorites),
              ),
              // ── Send: this computer casts to a TV ──
              const _NavSectionLabel('Send'),
              _NavItem(
                icon: Icons.connected_tv,
                label: 'Send to TV',
                selected: !showingVideo && dest == _Dest.sendToTv,
                onTap: () => onDestSelect(_Dest.sendToTv),
              ),
              // Only while something is casting — hidden when nothing is playing.
              if (senderCasting)
                _NavItem(
                  icon: Icons.cast_connected,
                  label: 'Now Playing',
                  selected: !showingVideo && dest == _Dest.nowCasting,
                  onTap: () => onDestSelect(_Dest.nowCasting),
                ),
              const Spacer(),
              Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                child: Divider(
                  height: 1,
                  color: Colors.white.withValues(alpha: 0.08),
                ),
              ),
              _NavItem(
                icon: Icons.settings,
                label: 'Settings',
                selected: !showingVideo && dest == _Dest.settings,
                onTap: () => onDestSelect(_Dest.settings),
              ),
              const SizedBox(height: 12),
            ],
          ),
        ),
      ),
    );
  }
}

/// Small uppercase section header in the sidebar (e.g. "Receive", "Send").
class _NavSectionLabel extends StatelessWidget {
  const _NavSectionLabel(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
      child: Text(
        label.toUpperCase(),
        style: TextStyle(
          fontSize: 10,
          letterSpacing: 1.0,
          fontWeight: FontWeight.w700,
          color: Colors.white.withValues(alpha: 0.38),
        ),
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
    this.accent = false,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    final fg =
        selected ? (accent ? Colors.tealAccent : Colors.white) : Colors.white54;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            decoration: selected
                ? BoxDecoration(
                    color: accent
                        ? Colors.tealAccent.withValues(alpha: 0.12)
                        : Colors.white.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(8),
                  )
                : null,
            child: Row(
              children: [
                Icon(icon, size: 18, color: fg),
                const SizedBox(width: 10),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 14,
                    color: fg,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ─── Status bar ──────────────────────────────────────────────────────────────

class _StatusBar extends StatelessWidget {
  const _StatusBar({
    required this.player,
    required this.hostInfo,
    required this.serverError,
    required this.discoveryError,
    required this.phase,
  });

  final PlayerController player;
  final String hostInfo;
  final String? serverError;
  final String? discoveryError;
  final PairingPhase phase;

  @override
  Widget build(BuildContext context) {
    // Self-listening for the live position readout (see _PlayerControlsBar).
    return ListenableBuilder(
      listenable: player,
      builder: (context, _) => _buildBar(context),
    );
  }

  Widget _buildBar(BuildContext context) {
    final pos = Duration(milliseconds: player.positionMs);
    final dur = Duration(milliseconds: player.durationMs);
    final phaseLabel = switch (phase) {
      PairingPhase.idle => 'waiting for phone',
      PairingPhase.awaitingApproval => 'awaiting approval…',
      PairingPhase.awaitingCode => 'pairing…',
      PairingPhase.authenticated => 'paired',
    };
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.35),
            border: Border(
              top: BorderSide(color: Colors.white.withValues(alpha: 0.08)),
            ),
          ),
          child: Row(
            children: [
              Icon(
                serverError != null
                    ? Icons.error_outline
                    : Icons.cast_connected,
                color:
                    serverError != null ? Colors.redAccent : Colors.greenAccent,
                size: 16,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  serverError != null
                      ? 'Server failed: $serverError'
                      : '$phaseLabel  ·  ${player.state}  ·  ${_engineLabel(player.engineType)}'
                          '${player.currentTitle != null ? '  ·  ${player.currentTitle}' : ''}'
                          '${discoveryError != null ? '  ·  mDNS: $discoveryError' : ''}',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12, color: Colors.white54),
                ),
              ),
              if (player.durationMs > 0)
                Text(
                  '${_fmt(pos)} / ${_fmt(dur)}',
                  style: const TextStyle(fontSize: 12, color: Colors.white38),
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _engineLabel(EngineType type) {
    return switch (type) {
      EngineType.mpvInternal => 'MPV',
    };
  }

  static String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}

// ─── Player controls bar ─────────────────────────────────────────────────────

/// Auto-hiding title scrim along the top of the video — mirrors the controls
/// bar visibility so the title is visible in fullscreen on hover.
class _TitleOverlay extends StatelessWidget {
  const _TitleOverlay({required this.player, required this.visible});

  final PlayerController player;
  final bool visible;

  @override
  Widget build(BuildContext context) {
    final idx = player.currentIndex;
    final item =
        (idx >= 0 && idx < player.queue.length) ? player.queue[idx] : null;
    final title = item?.title ?? player.currentTitle ?? '';
    if (title.isEmpty) return const SizedBox.shrink();

    final hasEpisode = item?.season != null && item?.episode != null;
    final subtitle = hasEpisode
        ? 'S${item!.season} · E${item.episode}'
            '${item.episodeTitle != null ? '  ${item.episodeTitle}' : ''}'
        : null;

    return IgnorePointer(
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: visible ? 1.0 : 0.0,
        child: Container(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 36),
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xB3000000), Color(0x00000000)],
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
              ),
              if (subtitle != null)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      color: Colors.white.withValues(alpha: 0.75),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PlayerControlsBar extends StatefulWidget {
  const _PlayerControlsBar({
    required this.player,
    required this.store,
    required this.visible,
    required this.showQueueControls,
    required this.onTogglePlaylist,
    required this.playlistOpen,
    required this.onMenuOpened,
    required this.onMenuClosed,
    required this.isFullScreen,
    required this.onToggleFullScreen,
    required this.onToggleProxy,
  });

  final PlayerController player;
  final PairingStore store;
  final bool visible;
  final bool showQueueControls;
  final VoidCallback onTogglePlaylist;
  final bool playlistOpen;
  final VoidCallback onMenuOpened;
  final VoidCallback onMenuClosed;
  final bool isFullScreen;
  final VoidCallback onToggleFullScreen;
  final Future<void> Function() onToggleProxy;

  @override
  State<_PlayerControlsBar> createState() => _PlayerControlsBarState();
}

class _PlayerControlsBarState extends State<_PlayerControlsBar> {
  double? _dragValue;

  @override
  Widget build(BuildContext context) {
    // Self-listening: the shell no longer rebuilds on player ticks, so the
    // scrubber/buttons subscribe to the player here, scoping the ~5Hz position
    // rebuilds to just this bar.
    return ListenableBuilder(
      listenable: widget.player,
      builder: (context, _) => _buildBar(context),
    );
  }

  Widget _buildBar(BuildContext context) {
    final p = widget.player;
    final hasMedia = p.queue.isNotEmpty &&
        p.currentIndex >= 0 &&
        p.currentIndex < p.queue.length;
    final dur = p.durationMs.toDouble();
    final pos =
        (_dragValue ?? p.positionMs.toDouble()).clamp(0.0, dur > 0 ? dur : 1.0);
    final hasDuration = dur > 0;

    return IgnorePointer(
      ignoring: !widget.visible,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: widget.visible ? 1.0 : 0.0,
        child: ClipRect(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 18, 12, 8),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.4),
                border: Border(
                  top: BorderSide(color: Colors.white.withValues(alpha: 0.1)),
                ),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Scrubber
                  Row(
                    children: [
                      SizedBox(
                        width: 56,
                        child: Text(
                          _fmt(Duration(milliseconds: pos.toInt())),
                          textAlign: TextAlign.right,
                          style: const TextStyle(
                              fontSize: 12, color: Colors.white70),
                        ),
                      ),
                      Expanded(
                        child: ExcludeFocus(
                          child: SliderTheme(
                            data: SliderTheme.of(context).copyWith(
                              trackHeight: 3,
                              thumbShape: const RoundSliderThumbShape(
                                  enabledThumbRadius: 6),
                              overlayShape: const RoundSliderOverlayShape(
                                  overlayRadius: 12),
                            ),
                            child: Slider(
                              min: 0,
                              max: hasDuration ? dur : 1,
                              value: pos,
                              onChanged: hasDuration
                                  ? (v) => setState(() => _dragValue = v)
                                  : null,
                              onChangeEnd: hasDuration
                                  ? (v) {
                                      p.seek(Duration(milliseconds: v.toInt()));
                                      setState(() => _dragValue = null);
                                    }
                                  : null,
                            ),
                          ),
                        ),
                      ),
                      SizedBox(
                        width: 56,
                        child: Text(
                          _fmt(Duration(milliseconds: p.durationMs)),
                          style: const TextStyle(
                              fontSize: 12, color: Colors.white70),
                        ),
                      ),
                    ],
                  ),
                  // Buttons
                  Row(
                    children: [
                      if (widget.showQueueControls)
                        IconButton(
                          tooltip: 'Previous',
                          icon: const Icon(Icons.skip_previous),
                          onPressed: p.hasPrevious ? () => p.previous() : null,
                        ),
                      IconButton(
                        tooltip: p.state == 'playing' ? 'Pause' : 'Play',
                        icon: Icon(
                          p.state == 'playing' ? Icons.pause : Icons.play_arrow,
                        ),
                        onPressed: () =>
                            p.state == 'playing' ? p.pause() : p.resume(),
                      ),
                      IconButton(
                        tooltip: 'Stop',
                        icon: const Icon(Icons.stop),
                        onPressed: () => p.stop(),
                      ),
                      if (widget.showQueueControls)
                        IconButton(
                          tooltip: 'Next',
                          icon: const Icon(Icons.skip_next),
                          onPressed: p.hasNext ? () => p.next() : null,
                        ),
                      const SizedBox(width: 12),
                      if (widget.showQueueControls)
                        Text(
                          '${p.currentIndex + 1} / ${p.queue.length}',
                          style: const TextStyle(color: Colors.white70),
                        ),
                      const Spacer(),
                      if (p.engineType == EngineType.mpvInternal) ...[
                        _AudioMenuButton(
                          player: p,
                          onOpened: widget.onMenuOpened,
                          onClosed: widget.onMenuClosed,
                        ),
                        _SubtitleMenuButton(
                          player: p,
                          onOpened: widget.onMenuOpened,
                          onClosed: widget.onMenuClosed,
                        ),
                      ],
                      IconButton(
                        tooltip: p.isCurrentItemProxied
                            ? 'Switch to direct playback'
                            : 'Route through proxy',
                        icon: Icon(
                          p.isCurrentItemProxied
                              ? Icons.shield
                              : Icons.shield_outlined,
                          color:
                              p.isCurrentItemProxied ? Colors.tealAccent : null,
                        ),
                        onPressed: hasMedia && !p.proxyToggleInProgress
                            ? widget.onToggleProxy
                            : null,
                      ),
                      if (widget.showQueueControls)
                        IconButton(
                          tooltip: widget.playlistOpen
                              ? 'Hide playlist'
                              : 'Show playlist',
                          icon: Icon(
                            widget.playlistOpen
                                ? Icons.playlist_remove
                                : Icons.playlist_play,
                          ),
                          onPressed: widget.onTogglePlaylist,
                        ),
                      IconButton(
                        tooltip: 'Play in external player (mpv/VLC)',
                        icon: const Icon(Icons.open_in_new),
                        onPressed: hasMedia
                            ? () async {
                                if (p.state == 'playing') {
                                  p.pause();
                                }
                                final currentItem = p.queue[p.currentIndex];
                                await _openInExternalPlayer(
                                    context, currentItem.url);
                              }
                            : null,
                      ),
                      IconButton(
                        tooltip: widget.isFullScreen
                            ? 'Exit fullscreen'
                            : 'Fullscreen',
                        icon: Icon(
                          widget.isFullScreen
                              ? Icons.fullscreen_exit
                              : Icons.fullscreen,
                        ),
                        onPressed: widget.onToggleFullScreen,
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static String _fmt(Duration d) {
    if (d.inMilliseconds <= 0) return '--:--';
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }

  Future<void> _openInExternalPlayer(BuildContext context, String url) async {
    String proxiedUrl = url;

    // Check if the URL is already a loopback address.
    // If not, force register a proxy session for it so headers are included.
    if (!url.contains('127.0.0.1') && !url.contains('localhost')) {
      final p = widget.player;
      final currentItem = p.currentIndex >= 0 && p.currentIndex < p.queue.length
          ? p.queue[p.currentIndex]
          : null;

      final Map<String, String> headers = currentItem?.headers ?? {};
      try {
        proxiedUrl = StreamProxyServer.instance.registerSession(url, headers);
      } catch (e) {
        stdout
            .writeln('[external-player] Failed to register proxy session: $e');
      }
    }

    showDialog(
      context: context,
      builder: (dialogCtx) {
        return AlertDialog(
          backgroundColor: const Color(0xFF161B22),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: const BorderSide(color: Colors.white10),
          ),
          title: const Row(
            children: [
              Icon(Icons.open_in_new, color: Colors.tealAccent),
              SizedBox(width: 10),
              Text(
                'Open in External Player',
                style: TextStyle(color: Colors.white, fontSize: 16),
              ),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Copy the proxied stream URL to play it in your local player:',
                style: TextStyle(color: Colors.white70, fontSize: 13),
              ),
              const SizedBox(height: 12),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black38,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: Colors.white10),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        proxiedUrl,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontFamily: 'monospace',
                          fontSize: 12,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton(
                      tooltip: 'Copy URL',
                      iconSize: 16,
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(),
                      icon: const Icon(Icons.copy, color: Colors.white54),
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: proxiedUrl));
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('URL copied to clipboard!'),
                            duration: Duration(seconds: 2),
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              const Text(
                'Terminal Commands:',
                style: TextStyle(
                  color: Colors.tealAccent,
                  fontWeight: FontWeight.bold,
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 8),
              _buildCommandRow(context, 'MPV', 'mpv "$proxiedUrl"'),
              const SizedBox(height: 6),
              _buildCommandRow(context, 'VLC', 'vlc "$proxiedUrl"'),
              const SizedBox(height: 14),
              const Text(
                'How to play:\n1. Open your terminal / command prompt.\n2. Copy and paste one of the command lines above, then press Enter.',
                style:
                    TextStyle(color: Colors.white54, fontSize: 11, height: 1.4),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogCtx).pop(),
              child:
                  const Text('Close', style: TextStyle(color: Colors.white70)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.teal,
                foregroundColor: Colors.white,
              ),
              onPressed: () async {
                Navigator.of(dialogCtx).pop();
                await _launchDirectly(proxiedUrl);
              },
              child: const Text('Launch Automatically'),
            ),
          ],
        );
      },
    );
  }

  Widget _buildCommandRow(BuildContext context, String app, String command) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.black12,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 40,
            child: Text(
              app,
              style: const TextStyle(
                color: Colors.white38,
                fontWeight: FontWeight.bold,
                fontSize: 11,
              ),
            ),
          ),
          Expanded(
            child: Text(
              command,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Colors.white70,
                fontFamily: 'monospace',
                fontSize: 11,
              ),
            ),
          ),
          const SizedBox(width: 6),
          IconButton(
            tooltip: 'Copy command',
            iconSize: 14,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
            icon: const Icon(Icons.copy, color: Colors.white30),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: command));
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text('$app command copied to clipboard!'),
                  duration: const Duration(seconds: 2),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Future<bool> _launchDirectly(String url) async {
    if (Platform.isMacOS) {
      try {
        await Process.start('open', ['-a', 'mpv', url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('open', ['-a', 'VLC', url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('open', [url]);
        return true;
      } catch (_) {}
    } else if (Platform.isWindows) {
      try {
        await Process.start('mpv.exe', [url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('vlc.exe', [url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('cmd.exe', ['/c', 'start', '', url]);
        return true;
      } catch (_) {}
    } else if (Platform.isLinux) {
      try {
        await Process.start('mpv', [url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('vlc', [url]);
        return true;
      } catch (_) {}
      try {
        await Process.start('xdg-open', [url]);
        return true;
      } catch (_) {}
    }
    return false;
  }
}

// ─── Audio track menu ─────────────────────────────────────────────────────────

class _AudioMenuButton extends StatelessWidget {
  const _AudioMenuButton({
    required this.player,
    required this.onOpened,
    required this.onClosed,
  });

  final PlayerController player;
  final VoidCallback onOpened;
  final VoidCallback onClosed;

  @override
  Widget build(BuildContext context) {
    final real = player.tracks.audio
        .where((t) => t.id != 'no' && t.id != 'auto')
        .toList(growable: false);
    return PopupMenuButton<AudioTrack>(
      tooltip: 'Audio track',
      icon: const Icon(Icons.audiotrack),
      enabled: real.length >= 2,
      onOpened: onOpened,
      onCanceled: onClosed,
      onSelected: (t) {
        onClosed();
        player.setAudioTrack(t);
      },
      itemBuilder: (context) {
        final current = player.track.audio;
        return [
          for (final t in real)
            CheckedPopupMenuItem<AudioTrack>(
              value: t,
              checked: t.id == current.id,
              child: Text(_label(t.language, t.title, 'Track ${t.id}')),
            ),
        ];
      },
    );
  }

  static String _label(String? lang, String? title, String fallback) {
    final parts = <String>[
      if (lang != null && lang.isNotEmpty) lang,
      if (title != null && title.isNotEmpty) title,
    ];
    return parts.isEmpty ? fallback : parts.join(' · ');
  }
}

/// Full-surface hint while media is dragged onto the main player.
class _MainDropOverlay extends StatelessWidget {
  const _MainDropOverlay();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black.withValues(alpha: 0.55),
      alignment: Alignment.center,
      child: Container(
        padding: const EdgeInsets.all(28),
        decoration: BoxDecoration(
          color: Colors.tealAccent.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: Colors.tealAccent.withValues(alpha: 0.6),
            width: 2,
          ),
        ),
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.file_download, size: 40, color: Colors.tealAccent),
            SizedBox(height: 12),
            Text(
              'Drop to play',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                color: Colors.white,
              ),
            ),
            SizedBox(height: 4),
            Text(
              'TV linked → cast · otherwise plays here',
              style: TextStyle(fontSize: 13, color: Colors.white70),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Subtitle track menu ──────────────────────────────────────────────────────

class _SubtitleMenuButton extends StatelessWidget {
  const _SubtitleMenuButton({
    required this.player,
    required this.onOpened,
    required this.onClosed,
  });

  final PlayerController player;
  final VoidCallback onOpened;
  final VoidCallback onClosed;

  @override
  Widget build(BuildContext context) {
    final real = player.tracks.subtitle
        .where((t) => t.id != 'no' && t.id != 'auto')
        .toList(growable: false);
    return PopupMenuButton<SubtitleTrack>(
      tooltip: 'Subtitles',
      icon: const Icon(Icons.subtitles),
      onOpened: onOpened,
      onCanceled: onClosed,
      onSelected: (t) {
        onClosed();
        player.setSubtitleTrack(t);
      },
      itemBuilder: (context) {
        final current = player.track.subtitle;
        final off = SubtitleTrack.no();
        return [
          CheckedPopupMenuItem<SubtitleTrack>(
            value: off,
            checked: current.id == 'no' || current.id.isEmpty,
            child: const Text('Off'),
          ),
          if (real.isNotEmpty) const PopupMenuDivider(),
          for (final t in real)
            CheckedPopupMenuItem<SubtitleTrack>(
              value: t,
              checked: t.id == current.id,
              child: Text(_label(t.language, t.title, 'Track ${t.id}')),
            ),
        ];
      },
    );
  }

  static String _label(String? lang, String? title, String fallback) {
    final parts = <String>[
      if (lang != null && lang.isNotEmpty) lang,
      if (title != null && title.isNotEmpty) title,
    ];
    return parts.isEmpty ? fallback : parts.join(' · ');
  }
}

// ─── Playlist drawer ──────────────────────────────────────────────────────────

class _PlaylistDrawer extends StatelessWidget {
  const _PlaylistDrawer({required this.player, required this.onClose});

  final PlayerController player;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
        child: Material(
          color: Colors.black.withValues(alpha: 0.45),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
                child: Row(
                  children: [
                    const Icon(Icons.playlist_play,
                        size: 20, color: Colors.white54),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Up next  ·  ${player.queue.length} item${player.queue.length == 1 ? '' : 's'}',
                        style: const TextStyle(
                            fontSize: 13, color: Colors.white70),
                      ),
                    ),
                    IconButton(
                      iconSize: 18,
                      icon: const Icon(Icons.close),
                      onPressed: onClose,
                    ),
                  ],
                ),
              ),
              const Divider(height: 1, color: Colors.white12),
              Expanded(
                child: ListView.builder(
                  itemCount: player.queue.length,
                  itemBuilder: (context, i) {
                    final item = player.queue[i];
                    final active = i == player.currentIndex;
                    return ListTile(
                      dense: true,
                      selected: active,
                      selectedTileColor: Colors.white10,
                      leading: SizedBox(
                        width: 28,
                        child: active
                            ? const Icon(Icons.equalizer,
                                size: 18, color: Colors.tealAccent)
                            : Text(
                                '${i + 1}',
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                    color: Colors.white54, fontSize: 12),
                              ),
                      ),
                      title: Text(
                        item.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          color: active ? Colors.tealAccent : Colors.white,
                          fontWeight:
                              active ? FontWeight.w600 : FontWeight.w400,
                        ),
                      ),
                      onTap: active ? null : () => player.jumpTo(i),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
