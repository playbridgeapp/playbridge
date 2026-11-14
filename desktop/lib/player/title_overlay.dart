import 'package:flutter/material.dart';

import '../player_controller.dart';
import '../ui/pb_theme.dart';
import 'player_widgets.dart';

/// Auto-hiding title along the top of the video: an optional back button, the
/// title, and a line with the episode and the place in the queue. Mirrors the
/// controls bar visibility so the title shows in fullscreen on hover.
class TitleOverlay extends StatelessWidget {
  const TitleOverlay({
    super.key,
    required this.player,
    required this.visible,
    this.onBack,
  });

  final PlayerController player;
  final bool visible;

  /// Shows a back button ("Back to Home. Playback continues") when set.
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: player,
      builder: (context, _) => _build(context),
    );
  }

  Widget _build(BuildContext context) {
    final idx = player.currentIndex;
    final item =
        (idx >= 0 && idx < player.queue.length) ? player.queue[idx] : null;
    final title = item?.title ?? player.currentTitle ?? '';
    if (title.isEmpty && onBack == null) return const SizedBox.shrink();

    final parts = <String>[
      if (item?.season != null && item?.episode != null)
        'S${item!.season} · E${item.episode}'
            '${item.episodeTitle != null ? '  ${item.episodeTitle}' : ''}',
      if (player.queue.length > 1)
        '${idx + 1} of ${player.queue.length} in the queue',
    ];
    final subtitle = parts.isEmpty ? null : parts.join(' · ');

    return AnimatedOpacity(
      duration: const Duration(milliseconds: 150),
      opacity: visible ? 1.0 : 0.0,
      child: Stack(
        children: [
          // Scrim only; it must not swallow clicks meant for the video.
          Positioned.fill(
            child: IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      PbColors.playerBackground.withValues(alpha: 0.8),
                      PbColors.playerBackground.withValues(alpha: 0),
                    ],
                  ),
                ),
              ),
            ),
          ),
          IgnorePointer(
            ignoring: !visible,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
              child: Row(
                children: [
                  if (onBack != null) ...[
                    PlayerIconButton(
                      tooltip: 'Back to Home. Playback continues',
                      icon: Icons.arrow_back_ios_new,
                      iconSize: 18,
                      tone: PlayerButtonTone.raised,
                      onPressed: onBack,
                    ),
                    const SizedBox(width: 16),
                  ],
                  Expanded(
                    child: IgnorePointer(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w600,
                              color: PlayerTones.foreground,
                            ),
                          ),
                          if (subtitle != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text(
                                subtitle,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: PbColors.playerText,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
