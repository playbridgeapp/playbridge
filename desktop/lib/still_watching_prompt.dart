import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class _ContinueWatchingIntent extends Intent {
  const _ContinueWatchingIntent();
}

class StillWatchingPrompt extends StatelessWidget {
  const StillWatchingPrompt({
    super.key,
    required this.remainingSeconds,
    required this.onContinue,
    this.title,
  });

  final int remainingSeconds;
  final String? title;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final minutes = remainingSeconds ~/ 60;
    final seconds = remainingSeconds % 60;
    return Shortcuts(
      shortcuts: const {
        SingleActivator(LogicalKeyboardKey.space): _ContinueWatchingIntent(),
        SingleActivator(LogicalKeyboardKey.escape): _ContinueWatchingIntent(),
        SingleActivator(LogicalKeyboardKey.arrowLeft):
            DirectionalFocusIntent(TraversalDirection.left),
        SingleActivator(LogicalKeyboardKey.arrowRight):
            DirectionalFocusIntent(TraversalDirection.right),
        SingleActivator(LogicalKeyboardKey.arrowUp):
            DirectionalFocusIntent(TraversalDirection.up),
        SingleActivator(LogicalKeyboardKey.arrowDown):
            DirectionalFocusIntent(TraversalDirection.down),
        SingleActivator(LogicalKeyboardKey.keyF): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.keyI): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.slash): DoNothingIntent(),
        SingleActivator(LogicalKeyboardKey.slash, shift: true):
            DoNothingIntent(),
      },
      child: Actions(
        actions: {
          _ContinueWatchingIntent: CallbackAction<_ContinueWatchingIntent>(
            onInvoke: (_) {
              onContinue();
              return null;
            },
          ),
        },
        child: FocusScope(
          autofocus: true,
          child: ColoredBox(
            color: Colors.black.withValues(alpha: 0.82),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: Card(
                  color: colors.surfaceContainerHigh,
                  surfaceTintColor: Colors.transparent,
                  elevation: 24,
                  child: Padding(
                    padding: const EdgeInsets.all(32),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.play_circle_outline,
                            size: 54, color: colors.primary),
                        const SizedBox(height: 18),
                        Text('Are you still watching?',
                            style: Theme.of(context)
                                .textTheme
                                .headlineSmall
                                ?.copyWith(color: colors.onSurface),
                            textAlign: TextAlign.center),
                        if (title != null && title!.isNotEmpty) ...[
                          const SizedBox(height: 8),
                          Text(title!,
                              style: TextStyle(color: colors.onSurfaceVariant),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              textAlign: TextAlign.center),
                        ],
                        const SizedBox(height: 12),
                        Text(
                          'Playback will stop in $minutes:${seconds.toString().padLeft(2, '0')}',
                          style: TextStyle(color: colors.onSurfaceVariant),
                        ),
                        const SizedBox(height: 24),
                        FilledButton.icon(
                          style: FilledButton.styleFrom(
                            backgroundColor: colors.primary,
                            foregroundColor: colors.onPrimary,
                          ),
                          autofocus: true,
                          onPressed: onContinue,
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('Continue watching'),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
