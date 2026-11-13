import 'package:flutter/material.dart';

import 'ui/pb_theme.dart';
import 'ui/pb_widgets.dart';

/// Modal cheat sheet for desktop player shortcuts.
Future<void> showKeyboardShortcutsSheet(BuildContext context) {
  return showDialog<void>(
    context: context,
    barrierColor: PbColors.scrim,
    builder: (ctx) => const KeyboardShortcutsDialog(),
  );
}

/// Every keyboard shortcut registered in `main.dart` (Space, F, I, Z, G, H,
/// [, ], arrows, PgUp, PgDn, ?), plus the mouse and drag-and-drop gestures.
/// Keep in sync with the `Shortcuts` map there; do not list unregistered keys.
const List<(String, String)> kKeyboardShortcuts = [
  ('Play or pause', 'Space'),
  ('Full screen', 'F'),
  ('Back or forward 10 seconds', '\u2190 \u2192'),
  ('Hold to move 50 seconds', 'hold \u2190 \u2192'),
  ('Volume', '\u2191 \u2193'),
  ('Previous or next chapter', 'PgUp PgDn'),
  ('Slower or faster', '[ ]'),
  ('Fit, zoom or fill the picture', 'Z'),
  ('Subtitles earlier or later', 'G H'),
  ('Bookmark this video', 'B'),
  ('Playback stats', 'I'),
  ('This list', '?'),
];

const List<String> kShortcutGestures = [
  'Click the video to play or pause. Double-click for full screen.',
  'Drop files or links on the window to play them here or send them to the '
      'connected TV.',
];

class KeyboardShortcutsDialog extends StatelessWidget {
  const KeyboardShortcutsDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: PbColors.card,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: const BorderSide(color: PbColors.outline),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Semantics(
                      header: true,
                      child: const Text(
                        'Keyboard and mouse',
                        style: TextStyle(
                            fontSize: 22, fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
                  PbIconButton(
                    icon: Icons.close,
                    tooltip: 'Close',
                    variant: PbButtonVariant.secondary,
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              LayoutBuilder(
                builder: (context, c) {
                  final twoColumns = c.maxWidth >= 560;
                  final rows = [
                    for (final r in kKeyboardShortcuts)
                      _ShortcutRow(label: r.$1, keys: r.$2),
                  ];
                  if (!twoColumns) {
                    return Column(children: rows);
                  }
                  final half = (rows.length + 1) ~/ 2;
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: Column(children: rows.sublist(0, half))),
                      const SizedBox(width: 32),
                      Expanded(child: Column(children: rows.sublist(half))),
                    ],
                  );
                },
              ),
              const SizedBox(height: 20),
              for (final g in kShortcutGestures)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(g,
                      style: const TextStyle(color: PbColors.textSecondary)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ShortcutRow extends StatelessWidget {
  const _ShortcutRow({required this.label, required this.keys});

  final String label;
  final String keys;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 40),
        child: Row(
          children: [
            Expanded(child: Text(label)),
            const SizedBox(width: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: PbColors.background,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                keys,
                style:
                    const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
              ),
            ),
          ],
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
