import 'package:material_ui/material_ui.dart';

import 'linked_playback.dart';

/// Shows website control in both the local player and the now-casting view.
class LinkedPlaybackBanner extends StatelessWidget {
  const LinkedPlaybackBanner({super.key, required this.coordinator});
  final LinkedPlaybackCoordinator coordinator;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: coordinator,
        builder: (context, _) {
          final origin = coordinator.controllerOrigin;
          if (origin == null) return const SizedBox.shrink();
          return Material(
            color: Colors.black87,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Row(children: [
                const Icon(Icons.link, size: 18),
                const SizedBox(width: 8),
                Expanded(
                    child: Text('Controlled by $origin',
                        maxLines: 1, overflow: TextOverflow.ellipsis)),
                TextButton(
                    onPressed: coordinator.unlink, child: const Text('Unlink')),
              ]),
            ),
          );
        },
      );
}

/// "Controlled by" inside the player's title row, so it fades with the
/// controls instead of staying over the video.
class LinkedPlaybackChip extends StatelessWidget {
  const LinkedPlaybackChip({super.key, required this.coordinator});
  final LinkedPlaybackCoordinator coordinator;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: coordinator,
        builder: (context, _) {
          final origin = coordinator.controllerOrigin;
          if (origin == null) return const SizedBox.shrink();
          return ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.only(left: 12, right: 4),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.link, size: 16, color: Colors.white70),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text('Controlled by $origin',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 13, color: Colors.white70)),
                  ),
                  TextButton(
                      onPressed: coordinator.unlink,
                      child: const Text('Unlink')),
                ]),
              ),
            ),
          );
        },
      );
}
