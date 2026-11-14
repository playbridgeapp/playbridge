import 'dart:async';

import 'package:flutter/material.dart';

import '../chapter_navigation.dart';
import '../media_kind.dart';
import '../playback_clock.dart';
import '../playback_speed.dart';
import '../player_chrome_policy.dart';
import '../player_control_visibility.dart';
import '../player_controller.dart';
import '../player_engine.dart';
import '../player_mini_bar.dart';
import '../seek_hover_preview.dart';
import '../ui/pb_theme.dart';
import 'player_panels.dart';
import 'player_widgets.dart';

/// Height of the full control bar. Side panels stop above it.
const double kPlayerFullBarHeight = 120;

/// Bottom controls of the local player.
///
/// Full layout (paused, pinned or not started): seek bar with chapter marks and
/// hover preview, then one row of controls. Mini layout (playing): the slim
/// [PlayerMiniBar]. Hidden: invisible and not clickable.
class PlayerControlsBar extends StatefulWidget {
  const PlayerControlsBar({
    super.key,
    required this.player,
    required this.mode,
    required this.showQueueControls,
    required this.openPanel,
    required this.onTogglePanel,
    required this.onScrubbing,
    required this.isFullScreen,
    required this.onToggleFullScreen,
    this.onBookmark,
    this.bookmarked = false,
    this.onMoveToTv,
  });

  final PlayerController player;
  final PlayerChromeMode mode;
  final bool showQueueControls;

  /// The side panel currently open, if any; its button is drawn pressed.
  final PlayerPanel? openPanel;
  final ValueChanged<PlayerPanel> onTogglePanel;
  final ValueChanged<bool> onScrubbing;
  final bool isFullScreen;
  final VoidCallback onToggleFullScreen;

  /// Shows a bookmark button when set (it needs the Bookmarks feature).
  final VoidCallback? onBookmark;
  final bool bookmarked;

  /// Shows "Move to a TV" when set (it needs the shared play target).
  final VoidCallback? onMoveToTv;

  @override
  State<PlayerControlsBar> createState() => _PlayerControlsBarState();
}

class _PlayerControlsBarState extends State<PlayerControlsBar> {
  double? _dragValue;

  /// Layout locked for the duration of a scrub so the slider under the pointer
  /// is not replaced when scrubbing pins chrome to full.
  PlayerChromeMode? _scrubLayout;

  /// Volume to return to when un-muting.
  double _volumeBeforeMute = 1.0;

  void _beginSeek(double value) {
    _scrubLayout ??= widget.mode == PlayerChromeMode.full
        ? PlayerChromeMode.full
        : PlayerChromeMode.mini;
    if (_dragValue == null) widget.onScrubbing(true);
    setState(() => _dragValue = value);
  }

  void _endSeek(double value) {
    widget.player.seek(Duration(milliseconds: value.toInt()));
    setState(() {
      _dragValue = null;
      _scrubLayout = null;
    });
    widget.onScrubbing(false);
  }

  void _seekBy(int deltaMs) {
    final p = widget.player;
    final target = (p.positionMs + deltaMs).clamp(0, p.durationMs);
    unawaited(p.seek(Duration(milliseconds: target)));
  }

  void _toggleMute() {
    final p = widget.player;
    if (p.volume > 0) {
      _volumeBeforeMute = p.volume;
      unawaited(p.setVolume(0));
    } else {
      unawaited(p.setVolume(_volumeBeforeMute > 0 ? _volumeBeforeMute : 1.0));
    }
  }

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
    final mediaKind = p.currentMediaKind ?? MediaKind.video;
    final isImage = mediaKind == MediaKind.image;
    final layout = _scrubLayout ??
        (widget.mode == PlayerChromeMode.full
            ? PlayerChromeMode.full
            : PlayerChromeMode.mini);
    final showFull = layout == PlayerChromeMode.full;
    final interactive =
        widget.mode != PlayerChromeMode.hidden || _scrubLayout != null;
    final mpv = p.engineType == EngineType.mpvInternal;
    final realAudio = realTrackCount(p.tracks?.audio);
    final realSubs = realTrackCount(p.tracks?.subtitle);

    if (!showFull) {
      final tracks = playerHasTrackPanel(p);
      return IgnorePointer(
        ignoring: !interactive,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 150),
          opacity: interactive ? 1.0 : 0.0,
          child: Padding(
            // Sits 8px above the window edge so subtitles clear it.
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: PlayerMiniBar(
              mediaKind: mediaKind,
              realAudioCount: realAudio,
              realSubtitleCount: realSubs,
              playing: p.state == 'playing',
              hasDuration: hasDuration,
              positionMs: pos.toInt(),
              durationMs: p.durationMs,
              isFullScreen: widget.isFullScreen,
              onPlayPause: () => p.state == 'playing' ? p.pause() : p.resume(),
              onStop: () => p.stop(),
              onToggleFullScreen: widget.onToggleFullScreen,
              onSeekChanged: _beginSeek,
              onSeekEnd: _endSeek,
              chapters: p.chapters,
              audioMenu: tracks
                  ? PlayerIconButton(
                      icon: Icons.audiotrack,
                      tooltip: 'Audio track',
                      size: 32,
                      iconSize: 18,
                      onPressed: () =>
                          widget.onTogglePanel(PlayerPanel.subtitles),
                    )
                  : null,
              subtitleMenu: tracks
                  ? PlayerIconButton(
                      icon: Icons.subtitles,
                      tooltip: 'Subtitles',
                      size: 32,
                      iconSize: 18,
                      onPressed: () =>
                          widget.onTogglePanel(PlayerPanel.subtitles),
                    )
                  : null,
            ),
          ),
        ),
      );
    }

    final playing = p.state == 'playing';
    final canSeek = hasDuration && !isImage;
    final showVolume = mpv && !isImage;
    final volume = p.volume.clamp(0.0, 1.0);
    final rate = p.playbackRate;

    PlayerButtonTone toneFor(PlayerPanel panel) => widget.openPanel == panel
        ? PlayerButtonTone.raised
        : PlayerButtonTone.plain;

    return Container(
      height: kPlayerFullBarHeight,
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 20),
      color: PbColors.playerBackground.withValues(alpha: 0.8),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          // Seek bar / slideshow progress.
          if (!isImage || hasDuration)
            SizedBox(
              height: 24,
              child: SeekHoverPreview(
                durationMs: p.durationMs,
                chapters: p.chapters,
                // Overlay radius 12 is wider than the thumb.
                trackInset: 12,
                enabled: canSeek,
                child: ExcludeFocus(
                  child: SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      trackHeight: 6,
                      activeTrackColor: PbColors.accent,
                      secondaryActiveTrackColor: PlayerTones.buffered,
                      inactiveTrackColor: PlayerTones.track,
                      thumbColor: PlayerTones.foreground,
                      overlayColor: Colors.white.withValues(alpha: 0.12),
                      trackShape: ChapterSliderTrackShape(
                        fractions: chapterFractions(p.chapters, p.durationMs),
                        gapColor: PbColors.playerBackground,
                      ),
                      thumbShape:
                          const RoundSliderThumbShape(enabledThumbRadius: 8),
                      overlayShape:
                          const RoundSliderOverlayShape(overlayRadius: 12),
                    ),
                    child: Slider(
                      min: 0,
                      max: hasDuration ? dur : 1,
                      value: pos,
                      secondaryTrackValue: _bufferedValue(p, dur),
                      onChanged: canSeek ? _beginSeek : null,
                      onChangeEnd: canSeek ? _endSeek : null,
                    ),
                  ),
                ),
              ),
            )
          else
            const SizedBox(height: 24),
          const SizedBox(height: 8),
          SizedBox(
            height: 48,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final wide = constraints.maxWidth >= 1000;
                return Row(
                  children: [
                    Expanded(
                      child: SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        physics: const NeverScrollableScrollPhysics(),
                        child: Row(
                          children: [
                            if (widget.showQueueControls)
                              PlayerIconButton(
                                tooltip: 'Previous',
                                icon: Icons.skip_previous,
                                onPressed:
                                    p.hasPrevious ? () => p.previous() : null,
                              ),
                            PlayerIconButton(
                              tooltip: playing ? 'Pause' : 'Play',
                              icon: playing ? Icons.pause : Icons.play_arrow,
                              tone: PlayerButtonTone.primary,
                              size: 48,
                              iconSize: 26,
                              onPressed: !isImage || hasDuration
                                  ? () => playing ? p.pause() : p.resume()
                                  : null,
                            ),
                            if (widget.showQueueControls)
                              PlayerIconButton(
                                tooltip: 'Next',
                                icon: Icons.skip_next,
                                onPressed: p.hasNext ? () => p.next() : null,
                              ),
                            PlayerIconButton(
                              tooltip: 'Stop',
                              icon: Icons.stop,
                              onPressed: () => p.stop(),
                            ),
                            if (canSeek) ...[
                              PlayerIconButton(
                                tooltip: 'Back 10 seconds',
                                icon: Icons.replay_10,
                                onPressed: () => _seekBy(-10000),
                              ),
                              PlayerIconButton(
                                tooltip: 'Forward 10 seconds',
                                icon: Icons.forward_10,
                                onPressed: () => _seekBy(10000),
                              ),
                            ],
                            if (showVolume) ...[
                              const SizedBox(width: 8),
                              PlayerIconButton(
                                tooltip: volume > 0 ? 'Mute' : 'Unmute',
                                icon: volume <= 0
                                    ? Icons.volume_off
                                    : (volume < 0.5
                                        ? Icons.volume_down
                                        : Icons.volume_up),
                                onPressed: _toggleMute,
                              ),
                              if (wide)
                                SizedBox(
                                  width: 96,
                                  height: 24,
                                  child: Semantics(
                                    label: 'Volume',
                                    value: '${(volume * 100).round()} percent',
                                    child: ExcludeFocus(
                                      child: SliderTheme(
                                        data: SliderTheme.of(context).copyWith(
                                          trackHeight: 4,
                                          activeTrackColor:
                                              PlayerTones.foreground,
                                          inactiveTrackColor: PlayerTones.track,
                                          thumbColor: PlayerTones.foreground,
                                          overlayShape:
                                              SliderComponentShape.noOverlay,
                                          thumbShape:
                                              const RoundSliderThumbShape(
                                                  enabledThumbRadius: 6),
                                        ),
                                        child: Slider(
                                          value: volume,
                                          onChanged: (v) =>
                                              unawaited(p.setVolume(v)),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                            ],
                            if (hasDuration) ...[
                              const SizedBox(width: 12),
                              Text(
                                '${formatControlElapsed(pos.toInt())} / '
                                '${formatPlaybackClock(p.durationMs)}',
                                style: const TextStyle(
                                  fontSize: 14,
                                  color: PbColors.playerText,
                                ),
                              ),
                            ],
                            if (mpv && !isImage && (rate - 1.0).abs() > 0.001)
                              Padding(
                                padding: const EdgeInsets.only(left: 12),
                                child: Text(
                                  playbackSpeedChip(rate),
                                  style: const TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: PbColors.accent,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 16),
                    if (widget.onBookmark != null)
                      PlayerIconButton(
                        tooltip:
                            widget.bookmarked ? 'Remove bookmark' : 'Bookmark',
                        icon: widget.bookmarked
                            ? Icons.bookmark
                            : Icons.bookmark_border,
                        active: widget.bookmarked,
                        onPressed: widget.onBookmark,
                      ),
                    if (hasMedia && playerHasTrackPanel(p))
                      PlayerIconButton(
                        tooltip: 'Subtitles and audio',
                        icon: Icons.subtitles_outlined,
                        tone: toneFor(PlayerPanel.subtitles),
                        onPressed: () =>
                            widget.onTogglePanel(PlayerPanel.subtitles),
                      ),
                    if (widget.showQueueControls)
                      PlayerIconButton(
                        tooltip:
                            'Queue, ${p.currentIndex + 1} of ${p.queue.length}',
                        icon: Icons.playlist_play,
                        tone: toneFor(PlayerPanel.queue),
                        onPressed: () =>
                            widget.onTogglePanel(PlayerPanel.queue),
                      ),
                    PlayerIconButton(
                      tooltip: 'More',
                      icon: Icons.more_horiz,
                      tone: toneFor(PlayerPanel.more),
                      onPressed: () => widget.onTogglePanel(PlayerPanel.more),
                    ),
                    if (widget.onMoveToTv != null) ...[
                      const SizedBox(width: 8),
                      if (wide)
                        PlayerPill(
                          label: 'Move to a TV',
                          icon: Icons.cast,
                          onPressed: widget.onMoveToTv,
                        )
                      else
                        PlayerIconButton(
                          tooltip: 'Move to a TV',
                          icon: Icons.cast,
                          tone: PlayerButtonTone.raised,
                          onPressed: widget.onMoveToTv,
                        ),
                    ],
                    PlayerIconButton(
                      tooltip: widget.isFullScreen
                          ? 'Exit fullscreen'
                          : 'Fullscreen',
                      icon: widget.isFullScreen
                          ? Icons.fullscreen_exit
                          : Icons.fullscreen,
                      onPressed: widget.onToggleFullScreen,
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  /// Buffered position for the seek bar's lighter segment, or null if unknown.
  static double? _bufferedValue(PlayerController p, double dur) {
    final b = p.bufferedMs;
    if (b == null || dur <= 0) return null;
    return b.toDouble().clamp(0.0, dur);
  }
}
