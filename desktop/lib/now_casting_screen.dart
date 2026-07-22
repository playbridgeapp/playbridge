import 'package:flutter/material.dart';

import 'tv_sender_controller.dart';

/// The "Now Playing" tab for the desktop **sender**: shows the now-casting card
/// (transport + seek) and the playlist currently on the TV, with tap-to-jump.
class NowCastingScreen extends StatelessWidget {
  const NowCastingScreen({super.key, required this.controller});

  final TvSenderController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    if (!controller.isCasting) {
      return const _EmptyNowPlaying();
    }
    final playlist = controller.castItems;
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
      children: [
        const Text('Now Playing',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
        const SizedBox(height: 16),
        NowCastingCard(controller: controller),
        if (playlist.length > 1) ...[
          const SizedBox(height: 24),
          Row(
            children: [
              Icon(Icons.playlist_play,
                  size: 15, color: Colors.white.withValues(alpha: 0.5)),
              const SizedBox(width: 8),
              Text(
                'PLAYLIST  ·  ${playlist.length} ITEMS',
                style: TextStyle(
                  fontSize: 11,
                  letterSpacing: 0.8,
                  fontWeight: FontWeight.w600,
                  color: Colors.white.withValues(alpha: 0.5),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          for (final item in playlist)
            _PlaylistRow(
              index: item.index,
              title: item.title,
              active: item.index == controller.castIndex,
              onTap: () => controller.playlistJumpTo(item.index),
            ),
        ],
      ],
    );
  }
}

class _PlaylistRow extends StatelessWidget {
  const _PlaylistRow({
    required this.index,
    required this.title,
    required this.active,
    required this.onTap,
  });

  final int index;
  final String title;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      decoration: BoxDecoration(
        color: active
            ? Colors.tealAccent.withValues(alpha: 0.10)
            : Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: active
              ? Colors.tealAccent.withValues(alpha: 0.35)
              : Colors.white.withValues(alpha: 0.07),
        ),
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: active ? null : onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                SizedBox(
                  width: 24,
                  child: active
                      ? const Icon(Icons.equalizer,
                          size: 16, color: Colors.tealAccent)
                      : Text('${index + 1}',
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                              fontSize: 12, color: Colors.white54)),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: active ? Colors.tealAccent : Colors.white,
                      fontWeight: active ? FontWeight.w600 : FontWeight.w400,
                    ),
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

class _EmptyNowPlaying extends StatelessWidget {
  const _EmptyNowPlaying();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.cast,
              size: 44, color: Colors.white.withValues(alpha: 0.25)),
          const SizedBox(height: 14),
          Text('Nothing casting',
              style: TextStyle(
                  fontSize: 16, color: Colors.white.withValues(alpha: 0.6))),
          const SizedBox(height: 6),
          Text('Cast a file or stream from "Send to TV".',
              style: TextStyle(
                  fontSize: 13, color: Colors.white.withValues(alpha: 0.4))),
        ],
      ),
    );
  }
}

/// Now-casting card: title, an interactive seek slider (absolute seek via the
/// controller), and transport controls. Stateful to hold the seek drag value.
class NowCastingCard extends StatefulWidget {
  const NowCastingCard({super.key, required this.controller});

  final TvSenderController controller;

  @override
  State<NowCastingCard> createState() => _NowCastingCardState();
}

class _NowCastingCardState extends State<NowCastingCard> {
  double? _dragMs;

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final title = c.castingTitle ?? 'Now playing';
    final activeTv = c.activeTv;
    final receiverLabel = activeTv == null
        ? c.activeProtocol.label
        : '${activeTv.name} · ${activeTv.protocol.label}';
    final playing = c.remoteState == 'playing';
    final dur = c.remoteDurationMs.toDouble();
    final hasDuration = dur > 0;
    final pos = (_dragMs ?? c.remotePositionMs.toDouble())
        .clamp(0.0, hasDuration ? dur : 1.0);

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.tealAccent.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.tealAccent.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.cast_connected,
                  size: 18, color: Colors.tealAccent),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      receiverLabel,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.55),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (hasDuration) ...[
            const SizedBox(height: 6),
            Row(
              children: [
                Text(_fmt(pos.toInt()),
                    style:
                        const TextStyle(fontSize: 11, color: Colors.white54)),
                Expanded(
                  child: SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 3,
                      activeTrackColor: Colors.tealAccent,
                      inactiveTrackColor: Colors.white24,
                      thumbColor: Colors.tealAccent,
                      thumbShape:
                          const RoundSliderThumbShape(enabledThumbRadius: 6),
                      overlayShape:
                          const RoundSliderOverlayShape(overlayRadius: 12),
                    ),
                    child: Slider(
                      min: 0,
                      max: dur,
                      value: pos,
                      onChanged: (v) => setState(() => _dragMs = v),
                      onChangeEnd: (v) {
                        c.seekToMs(v.toInt());
                        setState(() => _dragMs = null);
                      },
                    ),
                  ),
                ),
                Text(_fmt(c.remoteDurationMs),
                    style:
                        const TextStyle(fontSize: 11, color: Colors.white54)),
              ],
            ),
          ],
          const SizedBox(height: 4),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton(
                tooltip: 'Back 10s',
                icon: const Icon(Icons.replay_10),
                onPressed: c.seekBack,
              ),
              IconButton(
                tooltip: playing ? 'Pause' : 'Play',
                iconSize: 30,
                icon: Icon(playing ? Icons.pause_circle : Icons.play_circle),
                onPressed: c.playPause,
              ),
              IconButton(
                tooltip: 'Forward 10s',
                icon: const Icon(Icons.forward_10),
                onPressed: c.seekForward,
              ),
              const SizedBox(width: 8),
              IconButton(
                tooltip: 'Stop',
                color: Colors.redAccent,
                icon: const Icon(Icons.stop_circle),
                onPressed: c.stopCast,
              ),
            ],
          ),
        ],
      ),
    );
  }

  static String _fmt(int ms) {
    if (ms <= 0) return '0:00';
    final d = Duration(milliseconds: ms);
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(h > 0 ? 2 : 1, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}
