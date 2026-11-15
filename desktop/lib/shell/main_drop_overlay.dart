import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';

/// Where a drop will play.
enum DropZone { here, tv }

/// Below this width the two drop halves are stacked instead of side by side.
const double kDropSplitMinWidth = 720;

/// Which half of a drop surface of [size] the pointer at [position] is over.
DropZone dropZoneAt(Offset position, Size size) {
  final sideBySide = size.width >= kDropSplitMinWidth;
  final inFirstHalf =
      sideBySide ? position.dx < size.width / 2 : position.dy < size.height / 2;
  return inFirstHalf ? DropZone.here : DropZone.tv;
}

/// Full-surface hint while media is dragged over the shell. With a TV as the
/// destination it splits in two ("Play here" / "Send to TV") and highlights the
/// half under the pointer; otherwise the whole surface is one "Play here" target.
class MainDropOverlay extends StatelessWidget {
  const MainDropOverlay({
    super.key,
    required this.computerName,
    this.tvName,
    this.pointer,
  });

  /// This computer's name, shown under "Play here".
  final String computerName;

  /// Name of the selected TV, or null when playback is local.
  final String? tvName;

  /// Pointer position inside the overlay, when known.
  final Offset? pointer;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: PbColors.background.withValues(alpha: 0.94),
      child: LayoutBuilder(
        builder: (context, box) {
          final tv = tvName;
          final active = (tv != null && pointer != null)
              ? dropZoneAt(pointer!, box.biggest)
              : DropZone.here;
          final here = _Zone(
            icon: Icons.desktop_windows_outlined,
            title: 'Play here',
            subtitle: 'On this computer, $computerName',
            highlighted: tv == null || active == DropZone.here,
            alone: tv == null,
          );
          return Stack(
            children: [
              Positioned.fill(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(32, 96, 32, 32),
                  child: tv == null
                      ? here
                      : _split(
                          box.maxWidth >= kDropSplitMinWidth,
                          here,
                          _Zone(
                            icon: Icons.tv,
                            title: 'Send to $tv',
                            subtitle: 'Release to start playing on the TV',
                            highlighted: active == DropZone.tv,
                          ),
                        ),
                ),
              ),
              const Positioned(
                left: 0,
                right: 0,
                top: 32,
                child: Column(
                  children: [
                    Text(
                      'Drop to play',
                      style: TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w700,
                        color: PbColors.text,
                      ),
                    ),
                    SizedBox(height: 4),
                    Text(
                      'Several files become a queue.',
                      style: TextStyle(
                        fontSize: 15,
                        color: PbColors.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _split(bool sideBySide, Widget first, Widget second) {
    const gap = SizedBox(width: 20, height: 20);
    final children = [Expanded(child: first), gap, Expanded(child: second)];
    return sideBySide
        ? Row(
            crossAxisAlignment: CrossAxisAlignment.stretch, children: children)
        : Column(
            crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }
}

class _Zone extends StatelessWidget {
  const _Zone({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.highlighted,
    this.alone = false,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool highlighted;

  /// The only target on screen: drawn highlighted with the dashed-style outline.
  final bool alone;

  @override
  Widget build(BuildContext context) {
    final strong = highlighted && !alone;
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: strong ? PbColors.selected : PbColors.sidebar,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: highlighted && !alone ? PbColors.accent : PbColors.outline,
          width: strong ? 3 : 2,
        ),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            icon,
            size: 56,
            color: strong ? PbColors.onSelected : PbColors.textSecondary,
          ),
          const SizedBox(height: 12),
          Text(
            title,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w600,
              color: strong ? PbColors.onSelected : PbColors.text,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 15,
              color: strong
                  ? PbColors.onSelectedSecondary
                  : PbColors.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}
