import 'package:flutter/material.dart';

import '../player_controller.dart';
import '../tv_sender_controller.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// "6:42" or "1:02:03"; "0:00" for zero or unknown.
String formatPlaybackClock(int ms) {
  if (ms <= 0) return '0:00';
  final d = Duration(milliseconds: ms);
  final h = d.inHours;
  final m = d.inMinutes.remainder(60).toString().padLeft(h > 0 ? 2 : 1, '0');
  final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
  return h > 0 ? '$h:$m:$s' : '$m:$s';
}

/// The bar across the bottom of the shell while something plays here or on a
/// TV: thin progress line, title, where it plays, transport and "Open controls".
class PlaybackBar extends StatelessWidget {
  const PlaybackBar({
    super.key,
    required this.title,
    required this.playingOn,
    required this.positionMs,
    required this.durationMs,
    required this.playing,
    required this.onOpen,
    this.onPlayPause,
    this.onBack,
    this.onForward,
  });

  final String title;

  /// Where it plays, for example "Playing on Living Room TV".
  final String playingOn;
  final int positionMs;

  /// Zero or negative for live or unknown length (no times, no progress).
  final int durationMs;
  final bool playing;

  /// Opens the full view (local player or the remote view).
  final VoidCallback onOpen;
  final VoidCallback? onPlayPause;
  final VoidCallback? onBack;
  final VoidCallback? onForward;

  @override
  Widget build(BuildContext context) {
    final hasDuration = durationMs > 0;
    final fraction =
        hasDuration ? (positionMs / durationMs).clamp(0.0, 1.0) : 0.0;
    final where = hasDuration
        ? '$playingOn · ${formatPlaybackClock(positionMs)} / '
            '${formatPlaybackClock(durationMs)}'
        : playingOn;
    return Semantics(
      container: true,
      label: 'Playback',
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: 3,
            child: Stack(children: [
              const Positioned.fill(
                  child: ColoredBox(color: PbColors.outlineSoft)),
              Positioned.fill(
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: fraction,
                  child: const ColoredBox(color: PbColors.accent),
                ),
              ),
            ]),
          ),
          Container(
            color: PbColors.card,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            child: Row(
              children: [
                Expanded(
                  child: PbPressable(
                    onTap: onOpen,
                    semanticLabel: 'Open controls for $title',
                    builder: (context, hovered, focused) => Container(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(8),
                        border: focused
                            ? Border.all(color: PbColors.accent, width: 2)
                            : null,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: hovered ? PbColors.accent : PbColors.text,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            where,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 13,
                              color: PbColors.textSecondary,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                PbIconButton(
                  icon: Icons.replay_10,
                  tooltip: 'Back 10 seconds',
                  variant: PbButtonVariant.quiet,
                  onPressed: onBack,
                ),
                const SizedBox(width: 8),
                PbIconButton(
                  icon: playing ? Icons.pause : Icons.play_arrow,
                  tooltip: playing ? 'Pause' : 'Play',
                  onPressed: onPlayPause,
                ),
                const SizedBox(width: 8),
                PbIconButton(
                  icon: Icons.forward_10,
                  tooltip: 'Forward 10 seconds',
                  variant: PbButtonVariant.quiet,
                  onPressed: onForward,
                ),
                const SizedBox(width: 16),
                PbButton(
                  label: 'Open controls',
                  variant: PbButtonVariant.secondary,
                  onPressed: onOpen,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// [PlaybackBar] bound to the local player. Listens to the player itself so the
/// shell does not rebuild on every position tick.
class LocalPlaybackBar extends StatelessWidget {
  const LocalPlaybackBar({
    super.key,
    required this.player,
    required this.onOpen,
  });

  final PlayerController player;
  final VoidCallback onOpen;

  void _seekBy(int deltaMs) {
    final dur = player.durationMs;
    var target = player.positionMs + deltaMs;
    if (target < 0) target = 0;
    if (dur > 0 && target > dur) target = dur;
    player.seek(Duration(milliseconds: target));
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: player,
      builder: (context, _) {
        final state = player.state;
        final playing = state == 'playing';
        final canToggle = playing || state == 'paused' || state == 'buffering';
        final seekable = player.durationMs > 0;
        return PlaybackBar(
          title: player.currentTitle ?? 'Playing',
          playingOn: 'Playing on this computer',
          positionMs: player.positionMs,
          durationMs: player.durationMs,
          playing: playing,
          onOpen: onOpen,
          onPlayPause: canToggle
              ? () => playing ? player.pause() : player.resume()
              : null,
          onBack: seekable ? () => _seekBy(-10000) : null,
          onForward: seekable ? () => _seekBy(10000) : null,
        );
      },
    );
  }
}

/// [PlaybackBar] bound to what the connected TV is playing, using only the
/// controller's own transport methods and capability flags.
class RemotePlaybackBar extends StatelessWidget {
  const RemotePlaybackBar({
    super.key,
    required this.sender,
    required this.onOpen,
  });

  final TvSenderController sender;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: sender,
      builder: (context, _) {
        final tv = sender.activeTv?.name ?? sender.selectionName;
        return PlaybackBar(
          title: sender.castingTitle ?? 'Now playing',
          playingOn: 'Playing on $tv',
          positionMs: sender.remotePositionMs,
          durationMs: sender.remoteDurationMs,
          playing: sender.remoteState == 'playing',
          onOpen: onOpen,
          onPlayPause: sender.canPlayPause ? sender.playPause : null,
          onBack: sender.canSeekRelative ? sender.seekBack : null,
          onForward: sender.canSeekRelative ? sender.seekForward : null,
        );
      },
    );
  }
}
