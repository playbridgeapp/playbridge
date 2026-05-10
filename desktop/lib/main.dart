import 'dart:async';
import 'dart:io';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:window_manager/window_manager.dart';

import 'auto_launch.dart';
import 'discovery.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'server.dart';
import 'shader_background.dart';
import 'tray_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();

  // setPreventClose must run after the native window exists; calling it from
  // main() before the first frame is silently ignored on some platforms.
  windowManager.waitUntilReadyToShow(const WindowOptions(), () async {
    await windowManager.setPreventClose(true);
  });

  final store = await PairingStore.load();
  runApp(ReceiverApp(store: store));
}

class ReceiverApp extends StatefulWidget {
  const ReceiverApp({super.key, required this.store});

  final PairingStore store;

  @override
  State<ReceiverApp> createState() => _ReceiverAppState();
}

class _ReceiverAppState extends State<ReceiverApp> with WindowListener {
  late final PlayerController _player;
  late final VideoController _video;
  late final ReceiverServer _server;
  late final DiscoveryPublisher _discovery;
  late final TrayController _tray;
  bool _hadMedia = false;

  String _hostInfo = 'starting...';
  String? _serverError;
  String? _discoveryError;
  bool _playlistDrawerOpen = false;
  bool _videoHovered = false;
  int _menusOpen = 0;
  Timer? _hideTimer;
  static const _autoHide = Duration(seconds: 2);

  // When true the pairing overlay is shown regardless of playback state,
  // letting the user get back to it from the player controls settings button.
  bool _forcePairing = false;

  void _markActive() {
    if (!_videoHovered) setState(() => _videoHovered = true);
    _hideTimer?.cancel();
    _hideTimer = Timer(_autoHide, () {
      if (!mounted) return;
      // Don't hide while a menu/drawer is keeping the bar pinned.
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
    _player = PlayerController();
    _video = VideoController(_player.player);
    _server = ReceiverServer(player: _player, store: widget.store);
    _discovery = DiscoveryPublisher(
      serviceName: widget.store.deviceName,
      port: kDefaultPort,
      deviceId: widget.store.deviceId,
    );
    _tray = TrayController(player: _player, server: _server, store: widget.store);

    windowManager.addListener(this);
    _player.addListener(_handlePlayerChange);

    _bootServer();
    _bootDiscovery();
    _resolveHost();
    _initTrayAndWindow();
  }

  Future<void> _initTrayAndWindow() async {
    await _tray.init();
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
    // Always show the window on launch so the user has a chance to see the
    // pairing screen or the player. Auto-hide only happens when the user
    // explicitly clicks the close button (onWindowClose below).
    await windowManager.show();
    await windowManager.focus();
  }

  /// Show the window when playback starts (rising edge: queue empty → not).
  void _handlePlayerChange() {
    final hasMedia = _player.queue.isNotEmpty;
    if (hasMedia && !_hadMedia) {
      unawaited(_revealWindow());
    }
    _hadMedia = hasMedia;
  }

  Future<void> _revealWindow() async {
    await windowManager.show();
    await windowManager.focus();
  }

  void _openSettings(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (_) => _SettingsDialog(
        server: _server,
        store: widget.store,
        onShowPairing: () {
          Navigator.of(context).pop();
          setState(() => _forcePairing = true);
        },
      ),
    );
  }

  @override
  void onWindowClose() async {
    // User clicked the close button — hide instead of quit.
    final prevented = await windowManager.isPreventClose();
    if (prevented) {
      await windowManager.hide();
    }
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
        // Transparent everywhere so the native NSVisualEffectView shows through.
        scaffoldBackgroundColor: Colors.transparent,
        canvasColor: Colors.transparent,
      ),
      home: Scaffold(
        backgroundColor: Colors.transparent,
        body: AnimatedBuilder(
          animation: Listenable.merge([_server, _player]),
          builder: (context, _) {
            // Show the pairing screen until a client authenticates — but
            // never cover an active playback session. If the phone disconnects
            // mid-video, the user keeps watching; pairing is reachable via the
            // tray menu when they want it.
            final hasActiveMedia = _player.queue.isNotEmpty;
            final showPairing = (_server.phase != PairingPhase.authenticated &&
                    !hasActiveMedia) ||
                _forcePairing;
            final hasQueue = _player.queue.length > 1;
            final hasMedia = _player.queue.isNotEmpty;
            const titleBarHeight = 28.0;
            return Stack(
              children: [
                // Shader background only when no video is rendering — saves
                // GPU when the mpv texture covers the whole window anyway.
                if (!hasMedia) const Positioned.fill(child: AuroraBackground()),
                Column(
                  children: [
                    // Transparent draggable strip across the top — leaves room
                    // for the macOS traffic lights, which sit above this region
                    // and stay clickable because the native window draws them.
                    SizedBox(
                      height: titleBarHeight,
                      child: DragToMoveArea(
                        child: Row(
                          children: [
                            // Reserve ~78px on the left for traffic lights so
                            // the user can't drag from there (macOS handles it).
                            const SizedBox(width: 78),
                            Expanded(child: Container(color: Colors.transparent)),
                          ],
                        ),
                      ),
                    ),
                    Expanded(
                      child: MouseRegion(
                        onEnter: (_) => _markActive(),
                        onExit: (_) => _markInactive(),
                        onHover: (_) => _markActive(),
                        child: Stack(
                          children: [
                            // Transparent when nothing's playing so the native
                            // window blur shows through; the mpv texture is
                            // opaque on its own once a video is loaded.
                            Container(
                              color: hasMedia ? Colors.black : Colors.transparent,
                              child: Video(
                                controller: _video,
                                controls: NoVideoControls,
                                fill: Colors.transparent,
                              ),
                            ),
                            if (hasMedia && !showPairing)
                              Positioned(
                                left: 0,
                                right: 0,
                                bottom: 0,
                                child: _PlayerControlsBar(
                                  player: _player,
                                  visible: _videoHovered || _playlistDrawerOpen || _menusOpen > 0,
                                  showQueueControls: hasQueue,
                                  onTogglePlaylist: () => setState(
                                    () => _playlistDrawerOpen = !_playlistDrawerOpen,
                                  ),
                                  playlistOpen: _playlistDrawerOpen,
                                  onMenuOpened: () => setState(() => _menusOpen++),
                                  onMenuClosed: () => setState(() => _menusOpen = (_menusOpen - 1).clamp(0, 99)),
                                  onSettings: () => _openSettings(context),
                                ),
                              ),
                            if (hasQueue && _playlistDrawerOpen && !showPairing)
                              Positioned(
                                right: 0,
                                top: 0,
                                bottom: 0,
                                width: 360,
                                child: _PlaylistDrawer(
                                  player: _player,
                                  onClose: () => setState(() => _playlistDrawerOpen = false),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                    _StatusBar(
                      player: _player,
                      hostInfo: _hostInfo,
                      serverError: _serverError,
                      discoveryError: _discoveryError,
                      phase: _server.phase,
                    ),
                  ],
                ),
                if (showPairing)
                  PairingOverlay(
                    pin: widget.store.pin,
                    deviceName: widget.store.deviceName,
                    hostInfo: _hostInfo,
                    port: kDefaultPort,
                    phase: _server.phase,
                    discoveryError: _discoveryError,
                    onDismiss: _forcePairing
                        ? () => setState(() => _forcePairing = false)
                        : null,
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class PairingOverlay extends StatelessWidget {
  const PairingOverlay({
    super.key,
    required this.pin,
    required this.deviceName,
    required this.hostInfo,
    required this.port,
    required this.phase,
    required this.discoveryError,
    this.onDismiss,
  });

  final String pin;
  final String deviceName;
  final String hostInfo;
  final int port;
  final PairingPhase phase;
  final String? discoveryError;
  /// Non-null when the overlay was opened manually from settings — shows an
  /// X button so the user can return to the player without disconnecting.
  final VoidCallback? onDismiss;

  @override
  Widget build(BuildContext context) {
    final highlight = phase == PairingPhase.awaitingPin;
    return BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
      child: Container(
        color: Colors.black.withValues(alpha: 0.28),
        alignment: Alignment.center,
        child: Stack(
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Padding(
                padding: const EdgeInsets.all(40),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Icon(
                      highlight ? Icons.phonelink_ring : Icons.cast,
                      size: 56,
                      color: highlight ? Colors.tealAccent : Colors.white70,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      highlight ? 'Phone is pairing…' : 'Pair with PlayBridge',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'On the phone, pick "$deviceName" then enter this PIN:',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white70),
                    ),
                    const SizedBox(height: 28),
                    _PinDisplay(pin: pin),
                    const SizedBox(height: 28),
                    _InfoLine(label: 'Device', value: deviceName),
                    _InfoLine(label: 'Address', value: '$hostInfo : $port'),
                    _InfoLine(
                      label: 'Discovery',
                      value: discoveryError == null ? '_playbridge._tcp.' : 'failed: $discoveryError',
                      error: discoveryError != null,
                    ),
                  ],
                ),
              ),
            ),
            if (onDismiss != null)
              Positioned(
                top: 12,
                right: 12,
                child: IconButton(
                  tooltip: 'Back to player',
                  icon: const Icon(Icons.close),
                  onPressed: onDismiss,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _PinDisplay extends StatelessWidget {
  const _PinDisplay({required this.pin});
  final String pin;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final ch in pin.split(''))
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
                child: Container(
                  width: 64,
                  height: 80,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.06),
                    border: Border.all(color: Colors.white.withValues(alpha: 0.18)),
                  ),
                  child: Text(
                    ch,
                    style: const TextStyle(
                      fontSize: 44,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 2,
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _InfoLine extends StatelessWidget {
  const _InfoLine({required this.label, required this.value, this.error = false});
  final String label;
  final String value;
  final bool error;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 90,
            child: Text(
              label,
              style: const TextStyle(color: Colors.white54, fontSize: 13),
            ),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: 13,
              color: error ? Colors.redAccent : Colors.white,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }
}

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
      PairingPhase.awaitingPin => 'pairing…',
      PairingPhase.authenticated => 'paired',
    };
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
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
                size: 18,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  serverError != null
                      ? 'Server failed: $serverError'
                      : ':$kDefaultPort  ·  $hostInfo  ·  $phaseLabel  ·  ${player.state}'
                          '${player.currentTitle != null ? '  ·  ${player.currentTitle}' : ''}'
                          '${discoveryError != null ? '  ·  mDNS: $discoveryError' : ''}',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 13),
                ),
              ),
              if (player.durationMs > 0)
                Text(
                  '${_fmt(pos)} / ${_fmt(dur)}',
                  style: const TextStyle(fontSize: 12, color: Colors.white70),
                ),
            ],
          ),
        ),
      ),
    );
  }

  static String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}

class _PlayerControlsBar extends StatefulWidget {
  const _PlayerControlsBar({
    required this.player,
    required this.visible,
    required this.showQueueControls,
    required this.onTogglePlaylist,
    required this.playlistOpen,
    required this.onMenuOpened,
    required this.onMenuClosed,
    required this.onSettings,
  });

  final PlayerController player;
  final bool visible;
  final bool showQueueControls;
  final VoidCallback onTogglePlaylist;
  final bool playlistOpen;
  final VoidCallback onMenuOpened;
  final VoidCallback onMenuClosed;
  final VoidCallback onSettings;

  @override
  State<_PlayerControlsBar> createState() => _PlayerControlsBarState();
}

class _PlayerControlsBarState extends State<_PlayerControlsBar> {
  // While the user is dragging the scrubber, we don't want to fight them with
  // updates from the player's position stream. Track the in-flight value here.
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
        opacity: widget.visible ? 1 : 0.0,
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
              // Scrubber row: position · slider · duration
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
                    child: SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        trackHeight: 3,
                        thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                        overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
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
                  SizedBox(
                    width: 56,
                    child: Text(
                      _fmt(Duration(milliseconds: p.durationMs)),
                      style: const TextStyle(fontSize: 12, color: Colors.white70),
                    ),
                  ),
                ],
              ),
              // Buttons row
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
                    icon: Icon(p.state == 'playing' ? Icons.pause : Icons.play_arrow),
                    onPressed: () => p.state == 'playing' ? p.pause() : p.resume(),
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
                  if (widget.showQueueControls)
                    IconButton(
                      tooltip: widget.playlistOpen ? 'Hide playlist' : 'Show playlist',
                      icon: Icon(
                        widget.playlistOpen
                            ? Icons.playlist_remove
                            : Icons.playlist_play,
                      ),
                      onPressed: widget.onTogglePlaylist,
                    ),
                  IconButton(
                    tooltip: 'Settings',
                    icon: const Icon(Icons.settings),
                    onPressed: widget.onSettings,
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
    final tracks = player.tracks.audio;
    // Filter out the synthetic 'no'/'auto' entries that mpv exposes; only show
    // them as menu items when there's at least one real track.
    final real = tracks
        .where((t) => t.id != 'no' && t.id != 'auto')
        .toList(growable: false);
    final disabled = real.length < 2;
    return PopupMenuButton<AudioTrack>(
      tooltip: 'Audio track',
      icon: const Icon(Icons.audiotrack),
      enabled: !disabled,
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
              child: Text(_audioLabel(t)),
            ),
        ];
      },
    );
  }

  String _audioLabel(AudioTrack t) {
    final parts = <String>[];
    if (t.language != null && t.language!.isNotEmpty) parts.add(t.language!);
    if (t.title != null && t.title!.isNotEmpty) parts.add(t.title!);
    if (parts.isEmpty) parts.add('Track ${t.id}');
    return parts.join(' · ');
  }
}

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
    final tracks = player.tracks.subtitle;
    final real = tracks
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
              child: Text(_subLabel(t)),
            ),
        ];
      },
    );
  }

  String _subLabel(SubtitleTrack t) {
    final parts = <String>[];
    if (t.language != null && t.language!.isNotEmpty) parts.add(t.language!);
    if (t.title != null && t.title!.isNotEmpty) parts.add(t.title!);
    if (parts.isEmpty) parts.add('Track ${t.id}');
    return parts.join(' · ');
  }
}

class _SettingsDialog extends StatefulWidget {
  const _SettingsDialog({
    required this.server,
    required this.store,
    required this.onShowPairing,
  });

  final ReceiverServer server;
  final PairingStore store;
  final VoidCallback onShowPairing;

  @override
  State<_SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends State<_SettingsDialog> {
  bool? _autoLaunchEnabled;
  bool _isSandboxed = false;
  AutoLaunch? _autoLaunch;

  @override
  void initState() {
    super.initState();
    _loadAutoLaunch();
  }

  Future<void> _loadAutoLaunch() async {
    final sandboxed = await AutoLaunch.isLikelySandboxed();
    if (!Platform.isWindows) {
      final execPath = await AutoLaunch.resolveExecutablePath();
      final al = AutoLaunch(
        bundleId: 'com.playbridge.desktop',
        executablePath: execPath,
      );
      final enabled = await al.isEnabled();
      if (mounted) {
        setState(() {
          _autoLaunch = al;
          _autoLaunchEnabled = enabled;
          _isSandboxed = sandboxed;
        });
      }
    } else if (mounted) {
      setState(() => _isSandboxed = false);
    }
  }

  Future<void> _toggleAutoLaunch(bool value) async {
    final al = _autoLaunch;
    if (al == null) return;
    if (value) {
      await al.enable();
    } else {
      await al.disable();
    }
    setState(() => _autoLaunchEnabled = value);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.server,
      builder: (context, _) {
        final authed = widget.server.authedClientCount;
        final total = widget.server.connectedClientCount;
        return Dialog(
          backgroundColor: Colors.transparent,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
              child: Container(
                width: 480,
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.55),
                  border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // Header
                    Padding(
                      padding: const EdgeInsets.fromLTRB(24, 20, 8, 4),
                      child: Row(
                        children: [
                          const Icon(Icons.settings, size: 22, color: Colors.white70),
                          const SizedBox(width: 10),
                          Text(
                            'Settings',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const Spacer(),
                          IconButton(
                            iconSize: 18,
                            icon: const Icon(Icons.close),
                            onPressed: () => Navigator.of(context).pop(),
                          ),
                        ],
                      ),
                    ),
                    const Divider(height: 1, color: Colors.white10),
                    // Pairing section
                    _SettingsSection(label: 'Pairing'),
                    _SettingsTile(
                      icon: Icons.phonelink,
                      title: 'Device name',
                      trailing: Text(
                        widget.store.deviceName,
                        style: const TextStyle(color: Colors.white54, fontSize: 13),
                      ),
                    ),
                    _SettingsTile(
                      icon: Icons.pin,
                      title: 'Show pairing PIN',
                      subtitle: 'Return to the pairing screen',
                      onTap: widget.onShowPairing,
                    ),
                    _SettingsTile(
                      icon: Icons.devices,
                      title: 'Connected clients',
                      trailing: Text(
                        authed > 0 ? '$authed authenticated ($total total)' : 'none',
                        style: TextStyle(
                          color: authed > 0 ? Colors.tealAccent : Colors.white38,
                          fontSize: 13,
                        ),
                      ),
                    ),
                    if (authed > 0)
                      _SettingsTile(
                        icon: Icons.logout,
                        title: 'Disconnect all clients',
                        subtitle: 'Forces re-authentication on next connection',
                        onTap: () async {
                          await widget.server.kickAll();
                          if (context.mounted) Navigator.of(context).pop();
                        },
                        danger: true,
                      ),
                    // System section
                    _SettingsSection(label: 'System'),
                    if (!Platform.isWindows)
                      _SettingsTile(
                        icon: Icons.launch,
                        title: 'Launch at login',
                        subtitle: _isSandboxed
                            ? 'Not available in sandboxed builds'
                            : null,
                        trailing: _autoLaunchEnabled == null
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : Switch(
                                value: _autoLaunchEnabled!,
                                onChanged:
                                    _isSandboxed ? null : _toggleAutoLaunch,
                              ),
                      ),
                    // About section
                    _SettingsSection(label: 'About'),
                    _SettingsTile(
                      icon: Icons.info_outline,
                      title: 'PlayBridge Desktop',
                      trailing: const Text(
                        'v1.0.0  ·  port 8765',
                        style: TextStyle(color: Colors.white38, fontSize: 12),
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _SettingsSection extends StatelessWidget {
  const _SettingsSection({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 4),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Text(
          label.toUpperCase(),
          style: const TextStyle(
            fontSize: 11,
            letterSpacing: 1.2,
            color: Colors.white38,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  const _SettingsTile({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.danger = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final color = danger ? Colors.redAccent : Colors.white;
    return ListTile(
      dense: true,
      leading: Icon(icon, size: 20, color: danger ? Colors.redAccent : Colors.white70),
      title: Text(title, style: TextStyle(color: color, fontSize: 14)),
      subtitle: subtitle != null
          ? Text(subtitle!, style: const TextStyle(color: Colors.white38, fontSize: 12))
          : null,
      trailing: trailing,
      onTap: onTap,
    );
  }
}

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
                const Icon(Icons.playlist_play, size: 20, color: Colors.white70),
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
                        ? const Icon(Icons.equalizer, size: 18, color: Colors.tealAccent)
                        : Text(
                            '${i + 1}',
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: Colors.white54, fontSize: 12),
                          ),
                  ),
                  title: Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: active ? Colors.tealAccent : Colors.white,
                      fontWeight: active ? FontWeight.w600 : FontWeight.w400,
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
