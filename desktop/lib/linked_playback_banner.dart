import 'package:flutter/material.dart';

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
