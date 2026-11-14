import 'package:flutter/material.dart';

import 'shell/playback_bar.dart' show formatPlaybackClock;
import 'tv_sender_controller.dart';
import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Below this width the queue moves under the controls instead of beside them.
const double _kQueueBesideMinWidth = 900;

/// The remote "playing on a TV" view for the desktop **sender**: which TV, the
/// title, an interactive seek bar, transport, volume, loop/engine controls and
/// the playlist currently on the TV with tap-to-jump.
class NowCastingScreen extends StatelessWidget {
  const NowCastingScreen({super.key, required this.controller, this.onBack});

  final TvSenderController controller;

  /// Leaves this view for Home; playback on the TV continues. No back button
  /// is shown when null.
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    final c = controller;
    if (!c.isCasting) {
      return _EmptyNowPlaying(errorMessage: c.lastPlaybackError);
    }
    final playlist = c.castItems;
    final showQueue = c.canPlaylistJump && playlist.length > 1;
    final main = _NowCastingMain(controller: c, onBack: onBack);
    final queue = showQueue ? _QueuePanel(controller: c) : null;

    return LayoutBuilder(
      builder: (context, box) {
        if (queue != null && box.maxWidth >= _kQueueBesideMinWidth) {
          return Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(24, 24, 40, 32),
                  child: main,
                ),
              ),
              Container(
                width: 340,
                color: PbColors.sidebar,
                child: queue,
              ),
            ],
          );
        }
        return ListView(
          padding: const EdgeInsets.fromLTRB(24, 24, 32, 32),
          children: [
            main,
            if (queue != null) ...[
              const SizedBox(height: 24),
              queue,
            ]
          ],
        );
      },
    );
  }
}

class _NowCastingMain extends StatefulWidget {
  const _NowCastingMain({required this.controller, this.onBack});

  final TvSenderController controller;
  final VoidCallback? onBack;

  @override
  State<_NowCastingMain> createState() => _NowCastingMainState();
}

class _NowCastingMainState extends State<_NowCastingMain> {
  double? _dragMs;

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final title = c.castingTitle ?? 'Now playing';
    final tvName = c.activeTv?.name ?? c.selectionName;
    final state = c.remoteState;
    final playing = state == 'playing';
    final headline = playing
        ? 'Playing on $tvName'
        : state == 'paused'
            ? 'Paused on $tvName'
            : 'Casting to $tvName';
    final queueLength = c.castItems.length;
    final subtitle = (queueLength > 1 && c.castIndex >= 0)
        ? '${c.castIndex + 1} of $queueLength in the queue'
        : c.activeProtocol.label;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 16,
          runSpacing: 8,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (widget.onBack != null) ...[
                  PbIconButton(
                    icon: Icons.chevron_left,
                    tooltip: 'Back to Home. Playback continues',
                    variant: PbButtonVariant.secondary,
                    iconSize: 24,
                    onPressed: widget.onBack,
                  ),
                  const SizedBox(width: 16),
                ],
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    color: playing ? PbColors.success : PbColors.warning,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    headline,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      color: PbColors.text,
                    ),
                  ),
                ),
              ],
            ),
            PbButton(
              label: 'Stop',
              variant: PbButtonVariant.secondary,
              onPressed: c.canStop ? c.stopCast : null,
            ),
          ],
        ),
        const SizedBox(height: 28),
        Text(
          title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 28,
            height: 1.25,
            fontWeight: FontWeight.w700,
            color: PbColors.text,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          subtitle,
          style: const TextStyle(color: PbColors.textSecondary),
        ),
        const SizedBox(height: 24),
        _seekBar(context, c),
        const SizedBox(height: 16),
        _controls(c, playing),
        if (c.canLoop || _hasEngineSwitch(c)) ...[
          const SizedBox(height: 24),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (c.canLoop)
                _Chip(
                  label: c.isLoopEnabled == true ? 'Repeat: on' : 'Repeat: off',
                  tooltip: c.isLoopEnabled == true ? 'Loop on' : 'Loop off',
                  onTap: () => c.setLoop(c.isLoopEnabled != true),
                ),
              if (_hasEngineSwitch(c))
                PopupMenuButton<String>(
                  tooltip: 'Switch player engine',
                  onSelected: c.switchPlayer,
                  itemBuilder: (context) => [
                    for (final eng in c.availablePlayerEngines)
                      PopupMenuItem(value: eng, child: Text(eng)),
                  ],
                  child: const _Chip(label: 'Player engine'),
                ),
            ],
          ),
        ],
      ],
    );
  }

  static bool _hasEngineSwitch(TvSenderController c) =>
      c.canSwitchEngine && c.availablePlayerEngines.length > 1;

  Widget _seekBar(BuildContext context, TvSenderController c) {
    final dur = c.remoteDurationMs.toDouble();
    if (dur <= 0) return const SizedBox.shrink();
    final pos =
        (_dragMs ?? c.remotePositionMs.toDouble()).clamp(0.0, dur).toDouble();
    return Column(
      children: [
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 6,
            activeTrackColor: PbColors.accent,
            inactiveTrackColor: PbColors.outlineSoft,
            thumbColor: Colors.white,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 16),
          ),
          child: Slider(
            min: 0,
            max: dur,
            value: pos,
            semanticFormatterCallback: (v) => formatPlaybackClock(v.toInt()),
            onChanged:
                c.canSeekAbsolute ? (v) => setState(() => _dragMs = v) : null,
            onChangeEnd: c.canSeekAbsolute
                ? (v) {
                    c.seekToMs(v.toInt());
                    setState(() => _dragMs = null);
                  }
                : null,
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(formatPlaybackClock(pos.toInt()),
                  style: const TextStyle(color: PbColors.textSecondary)),
              Text(formatPlaybackClock(c.remoteDurationMs),
                  style: const TextStyle(color: PbColors.textSecondary)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _controls(TvSenderController c, bool playing) {
    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 24,
      runSpacing: 12,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            PbIconButton(
              icon: Icons.replay_10,
              tooltip: 'Back 10 seconds',
              variant: PbButtonVariant.secondary,
              size: 48,
              iconSize: 22,
              onPressed: c.canSeekRelative ? c.seekBack : null,
            ),
            const SizedBox(width: 10),
            PbIconButton(
              icon: playing ? Icons.pause : Icons.play_arrow,
              tooltip: playing ? 'Pause' : 'Play',
              variant: PbButtonVariant.primary,
              size: 60,
              iconSize: 28,
              onPressed: c.canPlayPause ? c.playPause : null,
            ),
            const SizedBox(width: 10),
            PbIconButton(
              icon: Icons.forward_10,
              tooltip: 'Forward 10 seconds',
              variant: PbButtonVariant.secondary,
              size: 48,
              iconSize: 22,
              onPressed: c.canSeekRelative ? c.seekForward : null,
            ),
          ],
        ),
        if (c.hasVolume) _volume(c),
      ],
    );
  }

  Widget _volume(TvSenderController c) {
    final label = c.volumeLabel ?? 'Volume';
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (c.canToggleMute)
          PbIconButton(
            icon: c.isMuted == true ? Icons.volume_off : Icons.volume_up,
            tooltip: c.isMuted == true ? 'Unmute' : 'Mute',
            onPressed: c.toggleMute,
          ),
        Text(
          c.volumeStatus,
          style: const TextStyle(
            fontWeight: FontWeight.w500,
            color: PbColors.text,
          ),
        ),
        const SizedBox(width: 8),
        if (c.showVolumeSlider)
          SizedBox(
            width: 140,
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: 4,
                activeTrackColor: PbColors.text,
                inactiveTrackColor: PbColors.outlineSoft,
                thumbColor: Colors.white,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
              ),
              child: Slider(
                min: 0.0,
                max: 1.0,
                value: (c.volumeLevel ?? 1.0).clamp(0.0, 1.0),
                semanticFormatterCallback: (v) =>
                    '$label ${(v * 100).round()} percent',
                onChanged: c.setVolume,
              ),
            ),
          )
        else if (c.canAdjustVolume) ...[
          PbIconButton(
            icon: Icons.volume_down,
            tooltip: '$label down',
            onPressed: () => c.adjustVolume(-1),
          ),
          PbIconButton(
            icon: Icons.volume_up,
            tooltip: '$label up',
            onPressed: () => c.adjustVolume(1),
          ),
        ],
      ],
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.label, this.onTap, this.tooltip});

  final String label;
  final VoidCallback? onTap;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final chip = PbPressable(
      onTap: onTap,
      semanticLabel: tooltip ?? label,
      builder: (context, hovered, focused) => Container(
        height: PbMetrics.controlHeight,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: hovered ? PbColors.raised : PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
          border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
        ),
        child: Text(label, style: const TextStyle(color: PbColors.text)),
      ),
    );
    return chip;
  }
}

/// "Queue · N items": the playlist on the TV, tap a row to jump to it.
class _QueuePanel extends StatelessWidget {
  const _QueuePanel({required this.controller});

  final TvSenderController controller;

  @override
  Widget build(BuildContext context) {
    final items = controller.castItems;
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'Queue · ${items.length} items',
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: PbColors.text,
            ),
          ),
          const SizedBox(height: 12),
          for (final item in items)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: PbListRow(
                title: item.title,
                subtitle: item.index == controller.castIndex ? 'Playing' : null,
                selected: item.index == controller.castIndex,
                minHeight: 56,
                onTap: item.index == controller.castIndex
                    ? null
                    : () => controller.playlistJumpTo(item.index),
              ),
            ),
        ],
      ),
    );
  }
}

class _EmptyNowPlaying extends StatelessWidget {
  const _EmptyNowPlaying({this.errorMessage});

  final String? errorMessage;

  @override
  Widget build(BuildContext context) {
    if (errorMessage != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline, size: 44, color: PbColors.error),
              const SizedBox(height: 14),
              Text(
                errorMessage!,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 16,
                  color: PbColors.text,
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(height: 6),
              const Text(
                'Playback failed on the TV.',
                style: TextStyle(color: PbColors.textSecondary),
              ),
            ],
          ),
        ),
      );
    }
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.cast, size: 44, color: PbColors.outline),
          SizedBox(height: 14),
          Text(
            'Nothing is playing on a TV',
            style: TextStyle(fontSize: 16, color: PbColors.text),
          ),
          SizedBox(height: 6),
          Text(
            'Choose a TV on Home, then play something.',
            style: TextStyle(color: PbColors.textSecondary),
          ),
        ],
      ),
    );
  }
}
