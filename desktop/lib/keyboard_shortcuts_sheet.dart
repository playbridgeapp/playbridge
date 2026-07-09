import 'package:flutter/material.dart';

/// Modal cheat sheet for desktop player shortcuts.
Future<void> showKeyboardShortcutsSheet(BuildContext context) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black54,
    builder: (ctx) => const _KeyboardShortcutsDialog(),
  );
}

class _KeyboardShortcutsDialog extends StatelessWidget {
  const _KeyboardShortcutsDialog();

  static const _rows = <(String, String)>[
    ('Space', 'Play / pause'),
    ('← / →', 'Seek −10s / +10s'),
    ('↑ / ↓', 'Volume up / down'),
    ('F', 'Toggle fullscreen'),
    ('I', 'Playback stats overlay'),
    ('?', 'This shortcuts list'),
    ('Click video', 'Play / pause'),
    ('Double-click video', 'Fullscreen'),
    ('Drop files / URLs', 'Play here (or cast if TV linked)'),
  ];

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: const Color(0xFF1A1A1A),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Icon(Icons.keyboard, size: 20, color: Colors.white54),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text(
                      'Keyboard & gestures',
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    onPressed: () => Navigator.of(context).pop(),
                    icon: const Icon(Icons.close, size: 18),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              for (final row in _rows)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      SizedBox(
                        width: 150,
                        child: Text(
                          row.$1,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: Colors.tealAccent.withValues(alpha: 0.9),
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ),
                      Expanded(
                        child: Text(
                          row.$2,
                          style: const TextStyle(
                            fontSize: 13,
                            color: Colors.white70,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Got it'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Intent for the `?` / help shortcut.
class ShowShortcutsIntent extends Intent {
  const ShowShortcutsIntent();
}

/// Intent for the `F` fullscreen shortcut.
class ToggleFullScreenIntent extends Intent {
  const ToggleFullScreenIntent();
}
