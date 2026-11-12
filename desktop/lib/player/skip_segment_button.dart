import 'package:flutter/material.dart';

import '../player_controller.dart';
import 'player_widgets.dart';
import '../skip_segment.dart';

class SkipSegmentButton extends StatelessWidget {
  const SkipSegmentButton({
    super.key,
    required this.player,
    required this.segments,
    required this.lastSkipped,
    required this.autoPrefs,
    required this.onSkip,
  });

  final PlayerController player;
  final List<SkipSegment> segments;
  final SkipSegment? lastSkipped;
  final SkipAutoPrefs autoPrefs;
  final ValueChanged<SkipSegment> onSkip;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: player,
      builder: (context, _) {
        final active = activeSkipSegment(
          segments: segments,
          positionMs: player.positionMs,
          lastSkipped: lastSkipped,
        );
        if (active == null) return const SizedBox.shrink();
        if (autoPrefs.enabledFor(active.type)) {
          WidgetsBinding.instance.addPostFrameCallback((_) => onSkip(active));
          return const SizedBox.shrink();
        }
        return PlayerPill(
          label: skipButtonLabel(active.type),
          tone: PlayerButtonTone.primary,
          large: true,
          onPressed: () => onSkip(active),
        );
      },
    );
  }
}
