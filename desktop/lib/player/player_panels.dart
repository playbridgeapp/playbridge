import 'dart:async';
import 'dart:io' show Platform;

import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart' show SubtitleTrack;

import '../engines/mpv_engine.dart';
import '../keyboard_shortcuts_sheet.dart';
import '../media_kind.dart';
import '../player_control_visibility.dart';
import '../player_controller.dart';
import '../player_engine.dart';
import '../subtitle_delay.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import '../video_scaling.dart';
import '../playback_speed.dart';
import 'external_player_dialog.dart';
import 'player_widgets.dart';

/// Which right-hand panel is open on the player.
enum PlayerPanel { subtitles, queue, more }

/// Speed label for the grid: `1×`, `0.75×`, `1.25×`.
String playerSpeedLabel(double rate) {
  final snapped = nearestPlaybackSpeed(rate);
  final text = snapped == snapped.roundToDouble()
      ? snapped.toStringAsFixed(0)
      : snapped.toString();
  return '$text×';
}

String _trackLabel(dynamic t) {
  final String? lang = t.language as String?;
  final String? title = t.title as String?;
  final parts = <String>[
    if (lang != null && lang.isNotEmpty) lang,
    if (title != null && title.isNotEmpty) title,
  ];
  return parts.isEmpty ? 'Track ${t.id}' : parts.join(' · ');
}

List<dynamic> _realTracks(dynamic list) {
  if (list is! Iterable) return const [];
  return [
    for (final t in list)
      if (t.id != 'no' && t.id != 'auto') t,
  ];
}

/// Whether the Subtitles and audio button has anything to open.
///
/// Video always offers it (a subtitle file can be added). Music keeps the old
/// rule: only when there is a subtitle track or a choice of audio tracks.
bool playerHasTrackPanel(PlayerController p) {
  if (p.engineType != EngineType.mpvInternal) return false;
  final kind = p.currentMediaKind ?? MediaKind.video;
  if (kind == MediaKind.image) return false;
  if (kind == MediaKind.video) return true;
  return realTrackCount(p.tracks?.subtitle) >= 1 ||
      realTrackCount(p.tracks?.audio) > 1;
}

/// Subtitles, subtitle timing and audio tracks (replaces the old subtitle and
/// audio popup menus and the subtitle-delay part of the settings dialog).
class SubtitlesPanel extends StatelessWidget {
  const SubtitlesPanel({
    super.key,
    required this.player,
    required this.onClose,
    this.pickSubtitleFile,
  });

  final PlayerController player;
  final VoidCallback onClose;

  /// Asks the viewer for a subtitle file; null result means cancelled. Tests
  /// replace this so no native dialog opens.
  final Future<String?> Function()? pickSubtitleFile;

  static Future<String?> _defaultPick() async {
    const group = XTypeGroup(
      label: 'Subtitles',
      extensions: ['srt', 'vtt', 'ass', 'ssa', 'sub'],
    );
    final file = await openFile(acceptedTypeGroups: [group]);
    return file?.path;
  }

  Future<void> _addFile(BuildContext context) async {
    final path = await (pickSubtitleFile ?? _defaultPick)();
    if (path == null || path.isEmpty) return;
    final name = path.split(RegExp(r'[\\/]')).last;
    final ok = await player.addExternalSubtitleFile(path, name);
    if (!context.mounted || ok) return;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      const SnackBar(content: Text('Could not add that subtitle file')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PlayerPanelShell(
      title: 'Subtitles and audio',
      onClose: onClose,
      child: ListenableBuilder(
        listenable: player,
        builder: (context, _) {
          final subs = _realTracks(player.tracks?.subtitle);
          final audio = _realTracks(player.tracks?.audio);
          final dynamic current = player.track;
          final String currentSub = (current?.subtitle?.id as String?) ?? 'no';
          final String currentAudio = (current?.audio?.id as String?) ?? '';
          final subOn = player.subtitleActive;
          final delay = player.subtitleDelayMs;
          return ListView(
            padding: const EdgeInsets.only(top: 16, bottom: 20),
            children: [
              const PlayerPanelHeading('Subtitles'),
              PlayerOptionRow(
                label: 'Off',
                selected: currentSub == 'no' || currentSub.isEmpty,
                onTap: () => unawaited(player.setSubtitleTrack(
                  SubtitleTrack.no(),
                )),
              ),
              for (final t in subs)
                PlayerOptionRow(
                  label: _trackLabel(t),
                  selected: t.id == currentSub,
                  onTap: () => unawaited(player.setSubtitleTrack(t)),
                ),
              PlayerOptionRow(
                label: 'Add a subtitle file…',
                accent: true,
                onTap: () => unawaited(_addFile(context)),
              ),
              if (subOn) ...[
                const SizedBox(height: 16),
                _SubtitleTiming(player: player, delay: delay),
              ],
              if (audio.length > 1) ...[
                const SizedBox(height: 20),
                const PlayerPanelHeading('Audio'),
                for (final t in audio)
                  PlayerOptionRow(
                    label: _trackLabel(t),
                    selected: t.id == currentAudio,
                    onTap: () => unawaited(player.setAudioTrack(t)),
                  ),
              ],
            ],
          );
        },
      ),
    );
  }
}

class _SubtitleTiming extends StatelessWidget {
  const _SubtitleTiming({required this.player, required this.delay});

  final PlayerController player;
  final int delay;

  @override
  Widget build(BuildContext context) {
    Widget step(String label, int ms) => Expanded(
          child: PlayerPill(
            label: label,
            onPressed: () => unawaited(player.adjustSubtitleDelayMs(ms)),
          ),
        );
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: PlayerTones.row,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Subtitle timing',
                  style: TextStyle(
                    fontWeight: FontWeight.w500,
                    color: PlayerTones.foreground,
                  ),
                ),
              ),
              Text(
                delay == 0
                    ? 'In step with the audio'
                    : subtitleDelayLabel(delay),
                style: TextStyle(
                  fontSize: 13,
                  color: delay == 0 ? PbColors.playerText : PbColors.accent,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              step('Earlier', -subtitleDelayFineMs),
              const SizedBox(width: 8),
              step('Later', subtitleDelayFineMs),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              step('Earlier by 1 s', -subtitleDelayCoarseMs),
              const SizedBox(width: 8),
              step('Later by 1 s', subtitleDelayCoarseMs),
            ],
          ),
          if (delay != 0) ...[
            const SizedBox(height: 8),
            PlayerPill(
              label: 'Reset timing',
              tone: PlayerButtonTone.plain,
              onPressed: () => unawaited(player.resetSubtitleDelay()),
            ),
          ],
        ],
      ),
    );
  }
}

/// The queue: jump to an item, move or remove upcoming items, clear what is
/// next (replaces the playlist drawer).
class QueuePanel extends StatefulWidget {
  const QueuePanel({super.key, required this.player, required this.onClose});

  final PlayerController player;
  final VoidCallback onClose;

  @override
  State<QueuePanel> createState() => _QueuePanelState();
}

class _QueuePanelState extends State<QueuePanel> {
  late final ScrollController _scroll;

  @override
  void initState() {
    super.initState();
    // Open with the playing item in view (rows are about 64px tall).
    final offset = (widget.player.currentIndex - 2) * 64.0;
    _scroll = ScrollController(initialScrollOffset: offset < 0 ? 0 : offset);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  String? _status(PlayerController p, int i) {
    final item = p.queue[i];
    if (i == p.currentIndex) {
      final dur = p.durationMs;
      final left = dur - p.positionMs;
      if (dur > 0 && left > 0 && p.currentMediaKind != MediaKind.image) {
        final mins = (left / 60000).ceil();
        return 'Playing · $mins min left';
      }
      return 'Playing';
    }
    if (item.season != null && item.episode != null) {
      return 'S${item.season} · E${item.episode}'
          '${item.episodeTitle != null ? '  ${item.episodeTitle}' : ''}';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.player;
    return ListenableBuilder(
      listenable: p,
      builder: (context, _) {
        final count = p.queue.length;
        final ids = p.queueItemIds;
        final upcoming = [
          for (var i = p.currentIndex + 1; i < ids.length; i++) ids[i],
        ];
        return PlayerPanelShell(
          title: 'Queue · $count item${count == 1 ? '' : 's'}',
          semanticLabel: 'Queue',
          onClose: widget.onClose,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: ListView.builder(
                  controller: _scroll,
                  padding: const EdgeInsets.only(top: 16),
                  itemCount: count,
                  itemBuilder: (context, i) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: _QueueRow(
                      key: ValueKey(i < ids.length ? ids[i] : i),
                      index: i,
                      title: p.queue[i].title,
                      status: _status(p, i),
                      active: i == p.currentIndex,
                      onTap: () => unawaited(p.jumpTo(i)),
                      onMoveUp: i > 0 && i != p.currentIndex && i < ids.length
                          ? () => unawaited(
                                p.moveQueueItem(ids[i], ids[i - 1]),
                              )
                          : null,
                      onRemove: i != p.currentIndex && i < ids.length
                          ? () => unawaited(p.removeQueueItems({ids[i]}))
                          : null,
                    ),
                  ),
                ),
              ),
              if (upcoming.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: PlayerPill(
                      label: "Clear what's next",
                      onPressed: () => unawaited(
                        p.removeQueueItems(upcoming.toSet()),
                      ),
                    ),
                  ),
                )
              else
                const SizedBox(height: 16),
            ],
          ),
        );
      },
    );
  }
}

class _QueueRow extends StatefulWidget {
  const _QueueRow({
    super.key,
    required this.index,
    required this.title,
    required this.status,
    required this.active,
    required this.onTap,
    required this.onMoveUp,
    required this.onRemove,
  });

  final int index;
  final String title;
  final String? status;
  final bool active;
  final VoidCallback onTap;
  final VoidCallback? onMoveUp;
  final VoidCallback? onRemove;

  @override
  State<_QueueRow> createState() => _QueueRowState();
}

class _QueueRowState extends State<_QueueRow> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final showActions =
        _hovered && (widget.onMoveUp != null || widget.onRemove != null);
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: Container(
        decoration: BoxDecoration(
          color: widget.active
              ? PbColors.playerRaised
              : (_hovered ? PlayerTones.row : Colors.transparent),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          children: [
            Expanded(
              child: PbPressable(
                onTap: widget.active ? null : widget.onTap,
                selected: widget.active,
                semanticLabel: widget.title,
                builder: (context, hovered, focused) => Container(
                  constraints: const BoxConstraints(minHeight: 60),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: focused
                      ? BoxDecoration(
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: PbColors.accent, width: 2),
                        )
                      : null,
                  child: Row(
                    children: [
                      SizedBox(
                        width: 20,
                        child: widget.active
                            ? const Icon(Icons.equalizer,
                                size: 18, color: PbColors.accent)
                            : Text(
                                '${widget.index + 1}',
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                  color: PbColors.playerText,
                                  fontSize: 14,
                                ),
                              ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              widget.title,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: widget.active
                                    ? FontWeight.w600
                                    : FontWeight.w400,
                                color: PlayerTones.foreground,
                              ),
                            ),
                            if (widget.status != null)
                              Text(
                                widget.status!,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: PbColors.playerText,
                                ),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            if (showActions) ...[
              if (widget.onMoveUp != null)
                PlayerIconButton(
                  icon: Icons.arrow_upward,
                  iconSize: 18,
                  tooltip: 'Move ${widget.title} up',
                  onPressed: widget.onMoveUp,
                ),
              if (widget.onRemove != null)
                PlayerIconButton(
                  icon: Icons.close,
                  iconSize: 18,
                  tooltip: 'Remove ${widget.title}',
                  onPressed: widget.onRemove,
                ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Speed, picture, other players, shortcuts, troubleshooting and stop
/// (replaces the speed and renderer popups, the playback settings dialog, the
/// proxy and external-player buttons).
class MorePanel extends StatelessWidget {
  const MorePanel({
    super.key,
    required this.player,
    required this.onClose,
    required this.onToggleProxy,
    this.showStats,
  });

  final PlayerController player;
  final VoidCallback onClose;
  final Future<void> Function() onToggleProxy;

  /// The app's playback-stats switch; the row is hidden when null.
  final ValueNotifier<bool>? showStats;

  @override
  Widget build(BuildContext context) {
    return PlayerPanelShell(
      title: 'More',
      onClose: onClose,
      child: ListenableBuilder(
        listenable:
            Listenable.merge([player, if (showStats != null) showStats]),
        builder: (context, _) {
          final p = player;
          final kind = p.currentMediaKind ?? MediaKind.video;
          final hasMedia = p.queue.isNotEmpty &&
              p.currentIndex >= 0 &&
              p.currentIndex < p.queue.length;
          final vis = PlayerControlVisibility.resolve(
            realAudioCount: realTrackCount(p.tracks?.audio),
            realSubtitleCount: realTrackCount(p.tracks?.subtitle),
            queueLength: p.queue.length,
            isLinux: Platform.isLinux,
            hasMedia: hasMedia,
            isVideo: kind == MediaKind.video,
            isImage: kind == MediaKind.image,
          );
          final mpv = p.engineType == EngineType.mpvInternal;
          final showSpeed = mpv && vis.speedMenu;
          final showPicture = mpv && vis.playbackSettings;
          final statsAvailable = showStats != null &&
              kind == MediaKind.video &&
              p.engine is MpvEngine;
          final troubleshooting =
              (mpv && vis.videoRenderer) || vis.proxyToggle || statsAvailable;
          final hardware = p.hardwareVideoOutput;
          final scaling = p.videoScaling;
          return ListView(
            padding: const EdgeInsets.only(top: 16, bottom: 20),
            children: [
              if (showSpeed) ...[
                const PlayerPanelHeading('Speed'),
                PlayerChoiceGrid(
                  columns: 4,
                  choices: [
                    for (final rate in playbackSpeeds)
                      PlayerChoice(
                        playerSpeedLabel(rate),
                        rate == p.playbackRate,
                        () => unawaited(p.setPlaybackRate(rate)),
                      ),
                  ],
                ),
                const SizedBox(height: 20),
              ],
              if (showPicture) ...[
                const PlayerPanelHeading('Picture'),
                PlayerChoiceGrid(
                  columns: 3,
                  choices: [
                    for (final mode in videoScalingModes)
                      PlayerChoice(
                        videoScalingLabel(mode),
                        mode == scaling,
                        () => unawaited(p.setVideoScaling(mode)),
                      ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 8, left: 2),
                  child: Text(
                    videoScalingDescription(scaling),
                    style: const TextStyle(
                      fontSize: 13,
                      color: PbColors.playerText,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
              ],
              if (mpv && vis.externalPlayer)
                PlayerOptionRow(
                  tall: true,
                  label: 'Open in another player',
                  value: 'mpv, VLC',
                  onTap: () {
                    if (p.state == 'playing') unawaited(p.pause());
                    unawaited(
                        openInExternalPlayer(context, p.queue[p.currentIndex]));
                  },
                ),
              PlayerOptionRow(
                tall: true,
                label: 'Keyboard shortcuts',
                value: '?',
                onTap: () => unawaited(showKeyboardShortcutsSheet(context)),
              ),
              if (troubleshooting) ...[
                const SizedBox(height: 16),
                const PlayerPanelHeading("If this video won't play smoothly"),
                if (mpv && vis.videoRenderer)
                  PlayerOptionRow(
                    tall: true,
                    label: 'Use software rendering',
                    value: hardware ? 'Off' : 'On',
                    onTap: p.videoOutputChanging
                        ? null
                        : () => unawaited(p.setHardwareVideoOutput(!hardware)),
                  ),
                if (vis.proxyToggle)
                  PlayerOptionRow(
                    tall: true,
                    label: 'Load through this computer',
                    value: p.isCurrentItemProxied ? 'On' : 'Off',
                    onTap: p.proxyToggleInProgress
                        ? null
                        : () => unawaited(onToggleProxy()),
                  ),
                if (statsAvailable)
                  PlayerOptionRow(
                    tall: true,
                    label: 'Show playback stats',
                    value: showStats!.value ? 'On' : 'Off',
                    onTap: () => showStats!.value = !showStats!.value,
                  ),
              ],
            ],
          );
        },
      ),
    );
  }
}
