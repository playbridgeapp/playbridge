import 'package:flutter/material.dart';

import 'chapter_navigation.dart';
import 'media_kind.dart';
import 'playback_clock.dart';
import 'player_control_visibility.dart';
import 'player_engine.dart';
import 'player/player_widgets.dart';
import 'seek_hover_preview.dart';
import 'ui/pb_theme.dart';

/// One slim rounded bar shown while the desktop player is playing: play/pause,
/// stop, seek (when the title has a duration), audio, subtitles, and fullscreen.
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
    this.chapters = const [],
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

  /// Chapters of the current item: gaps on the seek track and hover captions.
  final List<ChapterInfo> chapters;

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

    return Container(
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
      decoration: BoxDecoration(
        color: PbColors.playerSurface.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(28),
      ),
      child: Row(
        children: [
          PlayerIconButton(
            tooltip: playing ? 'Pause' : 'Play',
            icon: playing ? Icons.pause : Icons.play_arrow,
            onPressed: _playEnabled ? onPlayPause : null,
          ),
          PlayerIconButton(
            tooltip: 'Stop',
            icon: Icons.stop,
            onPressed: onStop,
          ),
          if (showSeek) ...[
            const SizedBox(width: 8),
            SizedBox(
              width: 52,
              child: Text(
                formatControlElapsed(pos.toInt()),
                textAlign: TextAlign.right,
                style: const TextStyle(
                  fontSize: 13,
                  color: PbColors.playerText,
                ),
              ),
            ),
            Expanded(
              child: SeekHoverPreview(
                durationMs: durationMs,
                chapters: chapters,
                // Overlay radius 10 is wider than the thumb.
                trackInset: 10,
                enabled: seekEnabled,
                child: ExcludeFocus(
                  child: SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 4,
                      activeTrackColor: PbColors.accent,
                      inactiveTrackColor: PlayerTones.track,
                      thumbColor: PlayerTones.foreground,
                      trackShape: ChapterSliderTrackShape(
                        fractions: chapterFractions(chapters, durationMs),
                        gapColor: PbColors.playerSurface,
                      ),
                      thumbShape: const RoundSliderThumbShape(
                        enabledThumbRadius: 6,
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
            ),
            SizedBox(
              width: 60,
              child: Text(
                formatRemainingClock(pos.toInt(), durationMs),
                textAlign: TextAlign.right,
                style: const TextStyle(
                  fontSize: 13,
                  color: PbColors.playerText,
                ),
              ),
            ),
          ] else
            const Spacer(),
          if (showAudio) audioMenu!,
          if (showSubs) subtitleMenu!,
          PlayerIconButton(
            tooltip: isFullScreen ? 'Exit fullscreen' : 'Fullscreen',
            icon: isFullScreen ? Icons.fullscreen_exit : Icons.fullscreen,
            onPressed: onToggleFullScreen,
          ),
        ],
      ),
    );
  }
}
