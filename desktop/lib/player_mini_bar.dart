import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import 'chapter_navigation.dart';
import 'media_kind.dart';
import 'playback_clock.dart';
import 'player_control_visibility.dart';

/// One thin row shown while the desktop player is playing: play/pause, stop,
/// seek (when the title has a duration), audio, subtitles, and fullscreen.
///
/// Audio appears only with two or more real tracks. Subtitles appear only when
/// at least one real subtitle track exists. Images follow the full bar and
/// omit both track menus. Live playback (no duration) omits the seek bar and
/// the clocks; the full bar has no LIVE label to carry over.
class PlayerMiniBar extends StatelessWidget {
  const PlayerMiniBar({
    super.key,
    required this.mediaKind,
    required this.realAudioCount,
    required this.realSubtitleCount,
    required this.playing,
    required this.hasDuration,
    required this.positionMs,
    required this.durationMs,
    required this.isFullScreen,
    required this.onPlayPause,
    required this.onStop,
    required this.onToggleFullScreen,
    this.onSeekChanged,
    this.onSeekEnd,
    this.audioMenu,
    this.subtitleMenu,
    this.chapterFractions = const [],
  });

  final MediaKind mediaKind;
  final int realAudioCount;
  final int realSubtitleCount;
  final bool playing;
  final bool hasDuration;
  final int positionMs;
  final int durationMs;
  final bool isFullScreen;
  final VoidCallback onPlayPause;
  final VoidCallback onStop;
  final VoidCallback onToggleFullScreen;
  final ValueChanged<double>? onSeekChanged;
  final ValueChanged<double>? onSeekEnd;
  final Widget? audioMenu;
  final Widget? subtitleMenu;

  /// Chapter starts (0..1) drawn as gaps on the seek track.
  final List<double> chapterFractions;

  bool get _playEnabled => mediaKind != MediaKind.image || hasDuration;

  @override
  Widget build(BuildContext context) {
    final vis = PlayerControlVisibility.resolve(
      realAudioCount: realAudioCount,
      realSubtitleCount: realSubtitleCount,
      queueLength: 1,
      isLinux: false,
      hasMedia: true,
      isVideo: mediaKind == MediaKind.video,
      isImage: mediaKind == MediaKind.image,
    );
    // The full bar also drops track menus for still images (`!isImage`).
    final showTracks = mediaKind != MediaKind.image;
    final showAudio = showTracks && vis.audioMenu && audioMenu != null;
    final showSubs = showTracks && vis.subtitleMenu && subtitleMenu != null;
    final showSeek = hasDuration;
    final seekEnabled = showSeek && mediaKind != MediaKind.image;
    final dur = durationMs.toDouble();
    final max = dur > 0 ? dur : 1.0;
    final pos = positionMs.toDouble().clamp(0.0, max);

    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Colors.black.withValues(alpha: 0.05),
                Colors.black.withValues(alpha: 0.55),
              ],
            ),
            border: Border(
              top: BorderSide(color: Colors.white.withValues(alpha: 0.10)),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 0, 4, 2),
            child: Row(
              children: [
                _MiniIconButton(
                  tooltip: playing ? 'Pause' : 'Play',
                  icon: playing ? Icons.pause : Icons.play_arrow,
                  onPressed: _playEnabled ? onPlayPause : null,
                ),
                _MiniIconButton(
                  tooltip: 'Stop',
                  icon: Icons.stop,
                  onPressed: onStop,
                ),
                if (showSeek) ...[
                  SizedBox(
                    width: 56,
                    child: Text(
                      formatControlElapsed(pos.toInt()),
                      textAlign: TextAlign.right,
                      style: const TextStyle(
                        fontSize: 11,
                        color: Colors.white70,
                      ),
                    ),
                  ),
                  Expanded(
                    child: ExcludeFocus(
                      child: SliderTheme(
                        data: SliderTheme.of(context).copyWith(
                          trackHeight: 2,
                          trackShape: ChapterSliderTrackShape(
                            fractions: chapterFractions,
                          ),
                          thumbShape: const RoundSliderThumbShape(
                            enabledThumbRadius: 5,
                          ),
                          overlayShape: const RoundSliderOverlayShape(
                            overlayRadius: 10,
                          ),
                        ),
                        child: Slider(
                          min: 0,
                          max: max,
                          value: pos,
                          onChanged: seekEnabled ? onSeekChanged : null,
                          onChangeEnd: seekEnabled ? onSeekEnd : null,
                        ),
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 56,
                    child: Text(
                      formatRemainingClock(pos.toInt(), durationMs),
                      style: const TextStyle(
                        fontSize: 11,
                        color: Colors.white70,
                      ),
                    ),
                  ),
                ] else
                  const Spacer(),
                if (showAudio) audioMenu!,
                if (showSubs) subtitleMenu!,
                _MiniIconButton(
                  tooltip: isFullScreen ? 'Exit fullscreen' : 'Fullscreen',
                  icon: isFullScreen ? Icons.fullscreen_exit : Icons.fullscreen,
                  onPressed: onToggleFullScreen,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MiniIconButton extends StatelessWidget {
  const _MiniIconButton({
    required this.tooltip,
    required this.icon,
    required this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: tooltip,
      onPressed: onPressed,
      icon: Icon(icon),
      iconSize: 18,
      visualDensity: VisualDensity.compact,
      padding: const EdgeInsets.all(4),
      constraints: const BoxConstraints.tightFor(width: 32, height: 32),
    );
  }
}
