import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import 'discovery.dart';
import 'pairing_store.dart';
import 'player_controller.dart';
import 'server.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  final store = await PairingStore.load();
  runApp(ReceiverApp(store: store));
}

class ReceiverApp extends StatefulWidget {
  const ReceiverApp({super.key, required this.store});

  final PairingStore store;

  @override
  State<ReceiverApp> createState() => _ReceiverAppState();
}

class _ReceiverAppState extends State<ReceiverApp> {
  late final PlayerController _player;
  late final VideoController _video;
  late final ReceiverServer _server;
  late final DiscoveryPublisher _discovery;

  String _hostInfo = 'starting...';
  String? _serverError;
  String? _discoveryError;
  bool _playlistDrawerOpen = false;
  bool _videoHovered = false;
  int _menusOpen = 0;
  Timer? _hideTimer;
  static const _autoHide = Duration(seconds: 2);

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
    _bootServer();
    _bootDiscovery();
    _resolveHost();
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
      theme: ThemeData.dark(useMaterial3: true),
      home: Scaffold(
        body: AnimatedBuilder(
          animation: Listenable.merge([_server, _player]),
          builder: (context, _) {
            // Show the pairing screen until a client authenticates,
            // OR whenever the phone explicitly asks (request_pairing).
            final showPairing = _server.phase != PairingPhase.authenticated;
            final hasQueue = _player.queue.length > 1;
            final hasMedia = _player.queue.isNotEmpty;
            return Stack(
              children: [
                Column(
                  children: [
                    Expanded(
                      child: MouseRegion(
                        onEnter: (_) => _markActive(),
                        onExit: (_) => _markInactive(),
                        onHover: (_) => _markActive(),
                        child: Stack(
                          children: [
                            Container(
                              color: Colors.black,
                              child: Video(
                                controller: _video,
                                controls: NoVideoControls,
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
  });

  final String pin;
  final String deviceName;
  final String hostInfo;
  final int port;
  final PairingPhase phase;
  final String? discoveryError;

  @override
  Widget build(BuildContext context) {
    final highlight = phase == PairingPhase.awaitingPin;
    return Container(
      color: Colors.black.withValues(alpha: 0.96),
      alignment: Alignment.center,
      child: ConstrainedBox(
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
            child: Container(
              width: 64,
              height: 80,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: Colors.white10,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white24),
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: Colors.black87,
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
  });

  final PlayerController player;
  final bool visible;
  final bool showQueueControls;
  final VoidCallback onTogglePlaylist;
  final bool playlistOpen;
  final VoidCallback onMenuOpened;
  final VoidCallback onMenuClosed;

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
        child: Container(
          padding: const EdgeInsets.fromLTRB(12, 24, 12, 8),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.bottomCenter,
              end: Alignment.topCenter,
              colors: [
                Colors.black.withValues(alpha: 0.9),
                Colors.black.withValues(alpha: 0.0),
              ],
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
                ],
              ),
            ],
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

class _PlaylistDrawer extends StatelessWidget {
  const _PlaylistDrawer({required this.player, required this.onClose});

  final PlayerController player;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.92),
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
    );
  }
}
