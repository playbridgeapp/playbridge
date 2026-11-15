import 'package:flutter/material.dart';

import '../tv_discovery.dart';
import 'pb_theme.dart';

/// Small icon-and-name chip saying how a device is reached. PlayBridge devices
/// get the accent chip; Google Cast, Roku, DLNA and browser receivers get a
/// neutral one. Set [onSelected] when the chip sits on a selected row.
class ProtocolChip extends StatelessWidget {
  const ProtocolChip(this.protocol, {super.key, this.onSelected = false});

  final TvProtocol protocol;
  final bool onSelected;

  static IconData iconFor(TvProtocol protocol) => switch (protocol) {
        TvProtocol.playBridge => Icons.play_arrow_rounded,
        TvProtocol.googleCast => Icons.cast,
        TvProtocol.roku => Icons.tv,
        TvProtocol.dlna => Icons.lan_outlined,
        TvProtocol.webBrowser => Icons.language,
      };

  @override
  Widget build(BuildContext context) {
    final accent = protocol == TvProtocol.playBridge;
    final background = accent
        ? PbColors.accent
        : (onSelected ? PbColors.card : PbColors.raised);
    final foreground = accent
        ? PbColors.background
        : (onSelected ? PbColors.onSelectedSecondary : PbColors.textSecondary);
    return Semantics(
      label: protocol.label,
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.fromLTRB(6, 2, 8, 2),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(iconFor(protocol), size: 13, color: foreground),
            const SizedBox(width: 4),
            Text(
              protocol.label,
              style: TextStyle(
                fontSize: 12,
                height: 1.3,
                fontWeight: FontWeight.w600,
                color: foreground,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
