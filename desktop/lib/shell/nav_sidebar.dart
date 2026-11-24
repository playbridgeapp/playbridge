import 'dart:io';
import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

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
      padding: EdgeInsets.fromLTRB(12, Platform.isMacOS ? 20 : 0, 12, 16),
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

/// Rounded PlayBridge icon mark matching the app icon.
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
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, size.width, size.height),
        Radius.circular(size.width * 0.22),
      ),
      Paint()..color = const Color(0xFFE6F0FF),
    );

    final s = size.width / 1024;
    final cx = size.width / 2;
    final cy = size.height / 2;
    canvas.save();
    canvas.translate(cx, cy);
    canvas.rotate(-13 * math.pi / 180);
    canvas.translate(-cx, -cy);

    final stroke = Paint()
      ..color = const Color(0xFF1A3FA6)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 170 * s
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    final stem = Path()
      ..moveTo(332 * s, 772 * s)
      ..lineTo(332 * s, 252 * s);
    final bowl = Path()
      ..moveTo(332 * s, 252 * s)
      ..lineTo(552 * s, 252 * s)
      ..cubicTo(692 * s, 252 * s, 772 * s, 332 * s, 772 * s, 422 * s)
      ..cubicTo(772 * s, 512 * s, 692 * s, 582 * s, 552 * s, 582 * s)
      ..lineTo(332 * s, 582 * s);

    canvas.drawPath(stem, stroke);
    canvas.drawPath(bowl, stroke);
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
