import 'dart:async';
import 'dart:io';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';

import 'discovery.dart';
import 'favorites_screen.dart';
import 'history_screen.dart';
import 'history_store.dart';
import 'pairing_store.dart';
import 'pair_screen.dart';
import 'player_controller.dart';
import 'player_engine.dart';
import 'playback_surface.dart';
import 'server.dart';
import 'settings_screen.dart';
import 'shader_background.dart';
import 'tray_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();

  windowManager.waitUntilReadyToShow(const WindowOptions(), () async {
    await windowManager.setPreventClose(true);
  });

  final results = await Future.wait([
    PairingStore.load(),
    HistoryStore.load(),
  ]);
  runApp(ReceiverApp(
    store: results[0] as PairingStore,
    history: results[1] as HistoryStore,
  ));
}

// Keyboard shortcuts
class PlayPauseIntent extends Intent { const PlayPauseIntent(); }
class SeekForwardIntent extends Intent { const SeekForwardIntent(); }
class SeekBackwardIntent extends Intent { const SeekBackwardIntent(); }
class VolumeUpIntent extends Intent { const VolumeUpIntent(); }
class VolumeDownIntent extends Intent { const VolumeDownIntent(); }

// Navigation destinations — nowPlaying is a mode, not a persistent screen.
enum _Dest { cast, history, favorites, settings }

class ReceiverApp extends StatefulWidget {
  const ReceiverApp({super.key, required this.store, required this.history});

  final PairingStore store;
  final HistoryStore history;

  @override
  State<ReceiverApp> createState() => _ReceiverAppState();
}

class _ReceiverAppState extends State<ReceiverApp> with WindowListener {
  late final PlayerController _player;
  late final ReceiverServer _server;
  late final DiscoveryPublisher _discovery;
  late final TrayController _tray;

  bool _hadMedia = false;
  String? _lastTrackedUrl;

  String _hostInfo = 'starting…';
  String? _serverError;
  String? _discoveryError;

  _Dest _dest = _Dest.cast;
  bool _showingVideo = false;
  bool _isFullScreen = false;

  // Controls visibility for the video overlay
  bool _videoHovered = false;
  int _menusOpen = 0;
  bool _playlistDrawerOpen = false;
  Timer? _hideTimer;
  static const _autoHide = Duration(seconds: 2);

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
    _player = PlayerController(initialEngine: widget.store.engineType);
    _server = ReceiverServer(player: _player, store: widget.store);
    _discovery = DiscoveryPublisher(
      serviceName: widget.store.deviceName,
      port: kDefaultPort,
      deviceId: widget.store.deviceId,
    );
    _tray = TrayController(player: _player, server: _server, store: widget.store);

    windowManager.addListener(this);
    _player.addListener(_handlePlayerChange);
    _player.playRequests.addListener(_handlePlayRequest);

    _bootServer();
    _bootDiscovery();
    _resolveHost();
    _initTrayAndWindow();
  }

  Future<void> _initTrayAndWindow() async {
    await _tray.init();
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
    await windowManager.show();
    await windowManager.focus();
  }

  void _handlePlayRequest() {
    unawaited(_revealWindow(fullScreen: true));
    if (!_showingVideo) {
      setState(() => _showingVideo = true);
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
      setState(() => _showingVideo = false);
    }

    _hadMedia = hasMedia;

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
      await windowManager.setFullScreen(true);
    }
  }

  @override
  void onWindowClose() async {
    final prevented = await windowManager.isPreventClose();
    if (prevented) await windowManager.hide();
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

  Future<void> _bootServer() async {
    try {
      await _server.start();
    } catch (e) {
      setState(() => _serverError = '$e');
    }
  }

  Future<void> _bootDiscovery() async {
    try {
      await _discovery.start();
    } catch (e) {
      setState(() => _discoveryError = '$e');
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
      setState(() => _hostInfo = addrs.isEmpty ? 'no LAN address' : addrs.join(', '));
    } catch (_) {
      setState(() => _hostInfo = 'unknown');
    }
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    windowManager.removeListener(this);
    _player.removeListener(_handlePlayerChange);
    _player.playRequests.removeListener(_handlePlayRequest);
    _tray.dispose();
    _discovery.stop();
    _server.stop();
    _player.dispose();
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
    ),
    home: Shortcuts(
      shortcuts: {
        const SingleActivator(LogicalKeyboardKey.space): const PlayPauseIntent(),
        const SingleActivator(LogicalKeyboardKey.arrowRight): const SeekForwardIntent(),
        const SingleActivator(LogicalKeyboardKey.arrowLeft): const SeekBackwardIntent(),
        const SingleActivator(LogicalKeyboardKey.arrowUp): const VolumeUpIntent(),
        const SingleActivator(LogicalKeyboardKey.arrowDown): const VolumeDownIntent(),
      },
      child: Actions(
        actions: {
          PlayPauseIntent: CallbackAction<PlayPauseIntent>(
            onInvoke: (_) => _player.state == 'playing' ? _player.pause() : _player.resume(),
          ),
          SeekForwardIntent: CallbackAction<SeekForwardIntent>(
            onInvoke: (_) {
              _player.seek(Duration(milliseconds: _player.positionMs + 10000));
              return null;
            },
          ),
          SeekBackwardIntent: CallbackAction<SeekBackwardIntent>(
            onInvoke: (_) {
              _player.seek(Duration(milliseconds: _player.positionMs - 10000));
              return null;
            },
          ),
          VolumeUpIntent: CallbackAction<VolumeUpIntent>(
            onInvoke: (_) {
              _player.setVolume((_player.volume + 0.05).clamp(0.0, 1.0));
              return null;
            },
          ),
          VolumeDownIntent: CallbackAction<VolumeDownIntent>(
            onInvoke: (_) {
              _player.setVolume((_player.volume - 0.05).clamp(0.0, 1.0));
              return null;
            },
          ),
        },
        child: Focus(
          autofocus: true,
          child: Scaffold(
            backgroundColor: Colors.transparent,
            body: AnimatedBuilder(
              animation: Listenable.merge([_server, _player]),
              builder: (context, _) {
            final hasMedia = _player.queue.isNotEmpty;
            final hasQueue = _player.queue.length > 1;
            const titleBarHeight = 28.0;
            // Hide every piece of chrome (title bar, sidebar, status bar)
            // when the user is watching video in full-screen — the video
            // should fill the entire monitor, not be framed by panels.
            final hideChrome = _isFullScreen && _showingVideo && hasMedia;

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
                            filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
                            child: DragToMoveArea(
                              child: Container(
                                decoration: BoxDecoration(
                                  gradient: LinearGradient(
                                    begin: Alignment.topCenter,
                                    end: Alignment.bottomCenter,
                                    colors: [
                                      Colors.white.withValues(alpha: 0.10),
                                      Colors.black.withValues(alpha: 0.20),
                                    ],
                                  ),
                                  border: Border(
                                    bottom: BorderSide(
                                      color: Colors.white.withValues(alpha: 0.08),
                                    ),
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    const SizedBox(width: 78),
                                    Expanded(
                                      child: Container(color: Colors.transparent),
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
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (!hideChrome)
                            _NavSidebar(
                              dest: _dest,
                              showingVideo: _showingVideo,
                              hasMedia: hasMedia,
                              playerState: _player.state,
                              onDestSelect: (d) => setState(() {
                                _dest = d;
                                _showingVideo = false;
                              }),
                              onShowVideo: () => setState(() => _showingVideo = true),
                            ),
                          Expanded(
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
                                      offstage: !_showingVideo || !hasMedia,
                                      child: Container(
                                        color: Colors.black,
                                        child: PlaybackSurface(
                                          controller: _player,
                                          controlsVisible: _videoHovered || _playlistDrawerOpen || _menusOpen > 0,
                                        ),
                                      ),
                                    ),
                                  ),                                  if (!_showingVideo)
                                    Positioned.fill(child: _buildScreen()),
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
                                            _menusOpen > 0,                                        showQueueControls: hasQueue,
                                        onTogglePlaylist: () => setState(
                                          () => _playlistDrawerOpen = !_playlistDrawerOpen,
                                        ),
                                        playlistOpen: _playlistDrawerOpen,
                                        onMenuOpened: () => setState(() => _menusOpen++),
                                        onMenuClosed: () => setState(
                                          () => _menusOpen = (_menusOpen - 1).clamp(0, 99),
                                        ),
                                        isFullScreen: _isFullScreen,
                                        onToggleFullScreen: _toggleFullScreen,
                                      ),
                                    ),
                                  if (_showingVideo && hasQueue && _playlistDrawerOpen)
                                    Positioned(
                                      right: 0,
                                      top: 0,
                                      bottom: 0,
                                      width: 360,
                                      child: _PlaylistDrawer(
                                        player: _player,
                                        onClose: () => setState(
                                          () => _playlistDrawerOpen = false,
                                        ),
                                      ),
                                    ),
                                ],
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
              ],
            );
          },
        ),
      ),
    ),
  ),
),
);
  }

  Widget _buildScreen() {
    return switch (_dest) {
      _Dest.cast => PairScreen(
          store: widget.store,
          deviceName: widget.store.deviceName,
          hostInfo: _hostInfo,
          port: kDefaultPort,
          phase: _server.phase,
          discoveryError: _discoveryError,
          pendingRequest: _server.pendingPairingRequest,
          onAllow: _server.approvePairing,
          onDeny: _server.denyPairing,
        ),
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
          onNavigateToCast: () => setState(() => _dest = _Dest.cast),
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
    required this.onDestSelect,
    required this.onShowVideo,
  });

  final _Dest dest;
  final bool showingVideo;
  final bool hasMedia;
  final String playerState;
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
                    const Icon(Icons.cast_connected, size: 16, color: Colors.tealAccent),
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

              if (hasMedia) ...[
                _NavItem(
                  icon: playerState == 'playing'
                      ? Icons.play_circle
                      : Icons.pause_circle_outline,
                  label: 'Now Playing',
                  selected: showingVideo,
                  accent: true,
                  onTap: onShowVideo,
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  child: Divider(
                    height: 1,
                    color: Colors.white.withValues(alpha: 0.08),
                  ),
                ),
              ],

              _NavItem(
                icon: Icons.cast,
                label: 'Cast',
                selected: !showingVideo && dest == _Dest.cast,
                onTap: () => onDestSelect(_Dest.cast),
              ),
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

                      const Spacer(),

              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
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
    final fg = selected
        ? (accent ? Colors.tealAccent : Colors.white)
        : Colors.white54;

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
    final pos = Duration(milliseconds: player.positionMs);
    final dur = Duration(milliseconds: player.durationMs);
    final phaseLabel = switch (phase) {
      PairingPhase.idle => 'waiting for phone',
      PairingPhase.awaitingApproval => 'awaiting approval…',
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
                serverError != null ? Icons.error_outline : Icons.cast_connected,
                color: serverError != null ? Colors.redAccent : Colors.greenAccent,
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
      EngineType.mpvExternal => 'MPV (EXT)',
      EngineType.vlcExternal => 'VLC (EXT)',
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

  @override
  State<_PlayerControlsBar> createState() => _PlayerControlsBarState();
}

class _PlayerControlsBarState extends State<_PlayerControlsBar> {
  double? _dragValue;

  @override
  Widget build(BuildContext context) {
    final p = widget.player;
    final dur = p.durationMs.toDouble();
    final pos = (_dragValue ?? p.positionMs.toDouble()).clamp(0.0, dur > 0 ? dur : 1.0);
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
                          style: const TextStyle(fontSize: 12, color: Colors.white70),
                        ),
                      ),
                      Expanded(
                        child: ExcludeFocus(
                          child: SliderTheme(
                          data: SliderTheme.of(context).copyWith(
                            trackHeight: 3,
                            thumbShape:
                                const RoundSliderThumbShape(enabledThumbRadius: 6),
                            overlayShape:
                                const RoundSliderOverlayShape(overlayRadius: 12),
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
                          style: const TextStyle(fontSize: 12, color: Colors.white70),
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
                        tooltip: 'Swap engine',
                        icon: const Icon(Icons.swap_horiz),
                        onPressed: () async {
                          final next = switch (p.engineType) {
                            EngineType.mpvInternal => EngineType.mpvExternal,
                            EngineType.mpvExternal => EngineType.vlcExternal,
                            EngineType.vlcExternal => EngineType.mpvInternal,
                          };
                          await widget.store.setEngineType(next);
                          await p.switchEngine(next);
                        },
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
                    const Icon(Icons.playlist_play, size: 20, color: Colors.white54),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Up next  ·  ${player.queue.length} item${player.queue.length == 1 ? '' : 's'}',
                        style: const TextStyle(fontSize: 13, color: Colors.white70),
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
