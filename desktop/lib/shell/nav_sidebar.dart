import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';
import 'receiver_status.dart';

/// Persistent destinations shown in the sidebar.
enum NavDest { home, bookmarks, devices, together, settings }

/// App sidebar: brand mark, main destinations, receiver status block and Settings.
class NavSidebar extends StatelessWidget {
  const NavSidebar({
    super.key,
    required this.selected,
    required this.onSelect,
    required this.status,
    this.screenMirroring = false,
  });

  /// Highlighted destination, or null while the player or screen mirror is shown.
  final NavDest? selected;
  final ValueChanged<NavDest> onSelect;
  final ReceiverStatusInfo status;

  /// Shows the (non-interactive) Screen Mirror entry while a phone mirrors.
  final bool screenMirroring;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: PbMetrics.sidebarWidth,
      color: PbColors.sidebar,
      padding: const EdgeInsets.fromLTRB(12, 20, 12, 16),
      child: FocusTraversalGroup(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(12, 0, 12, 16),
              child: Row(
                children: [
                  _BrandMark(),
                  SizedBox(width: 10),
                  Flexible(
                    child: Text(
                      'PlayBridge',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style:
                          TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                    ),
                  ),
                ],
              ),
            ),
            _item('Home', NavDest.home),
            _item('Bookmarks', NavDest.bookmarks),
            _item('Devices', NavDest.devices),
            _item('Watch Together', NavDest.together),
            if (screenMirroring)
              NavItem(
                label: 'Screen Mirror',
                icon: Icons.screen_share,
                selected: true,
                onTap: () {},
              ),
            const Expanded(child: SizedBox(height: 16)),
            ReceiverStatusBlock(status: status),
            const SizedBox(height: 4),
            _item('Settings', NavDest.settings),
          ],
        ),
      ),
    );
  }

  Widget _item(String label, NavDest dest) => NavItem(
        label: label,
        selected: selected == dest,
        onTap: () => onSelect(dest),
      );
}

/// One 44px sidebar entry; selected uses the selected-blue fill and white semibold text.
class NavItem extends StatelessWidget {
  const NavItem({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
    this.icon,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: PbPressable(
        onTap: onTap,
        selected: selected,
        semanticLabel: label,
        builder: (context, hovered, focused) => Container(
          height: PbMetrics.controlHeight,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            color: selected
                ? PbColors.selected
                : (hovered ? PbColors.raised : Colors.transparent),
            borderRadius: BorderRadius.circular(PbMetrics.navRadius),
            border:
                focused ? Border.all(color: PbColors.accent, width: 2) : null,
          ),
          child: Row(
            children: [
              if (icon != null) ...[
                Icon(
                  icon,
                  size: 18,
                  color:
                      selected ? PbColors.onSelected : PbColors.textSecondary,
                ),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                    color:
                        selected ? PbColors.onSelected : PbColors.textSecondary,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Rounded accent square with a play triangle, the PlayBridge mark.
class _BrandMark extends StatelessWidget {
  const _BrandMark();

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: CustomPaint(size: const Size(22, 22), painter: _BrandPainter()),
    );
  }
}

class _BrandPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 32;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, size.width, size.height),
        Radius.circular(9 * s),
      ),
      Paint()..color = PbColors.accent,
    );
    final path = Path()
      ..moveTo(12 * s, 9.5 * s)
      ..lineTo(12 * s, 22.5 * s)
      ..lineTo(23 * s, 16 * s)
      ..close();
    canvas.drawPath(path, Paint()..color = PbColors.background);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
