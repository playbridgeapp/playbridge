import 'package:flutter/material.dart';

import '../tv_sender_controller.dart';
import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// Collapsed "Advanced" block at the bottom of Devices.
///
/// Holds the switches the old Send to TV screen carried that the new design
/// does not place elsewhere yet. Currently one:
///
/// * "Route through Desktop proxy": `TvSenderController.castRouteThroughProxy`
///   / `setCastRouteThroughProxy`, persisted by `PairingStore`
///   (`castRouteThroughProxy`, a SharedPreferences bool) via the controller's
///   identity store.
class AdvancedSection extends StatefulWidget {
  const AdvancedSection({super.key, required this.controller});

  final TvSenderController controller;

  @override
  State<AdvancedSection> createState() => _AdvancedSectionState();
}

class _AdvancedSectionState extends State<AdvancedSection> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PbPressable(
          onTap: () => setState(() => _open = !_open),
          semanticLabel: 'Advanced',
          selected: _open,
          builder: (context, hovered, focused) => Container(
            height: PbMetrics.controlHeight,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: hovered ? PbColors.raised : Colors.transparent,
              borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
              border:
                  focused ? Border.all(color: PbColors.accent, width: 2) : null,
            ),
            child: Row(
              children: [
                Icon(
                  _open ? Icons.expand_more : Icons.chevron_right,
                  size: 20,
                  color: PbColors.textSecondary,
                ),
                const SizedBox(width: 8),
                const Text(
                  'Advanced',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: PbColors.textSecondary,
                  ),
                ),
              ],
            ),
          ),
        ),
        if (_open)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: ListenableBuilder(
              listenable: widget.controller,
              builder: (context, _) => PbSwitchRow(
                title: 'Send casts through this computer',
                subtitle: 'Routes casts through the Desktop proxy. Off sends '
                    'the original web address without browser headers.',
                value: widget.controller.castRouteThroughProxy,
                onChanged: widget.controller.setCastRouteThroughProxy,
              ),
            ),
          ),
      ],
    );
  }
}
