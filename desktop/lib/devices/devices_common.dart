import '../tv_discovery.dart';
import '../ui/protocol_chip.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ui/pb_theme.dart';

/// Card-style row used by the Devices lists: a title, a one-line state and
/// trailing buttons. [selected] uses the selected-blue fill (the connected TV).
class DeviceRow extends StatelessWidget {
  const DeviceRow({
    super.key,
    required this.title,
    required this.subtitle,
    this.protocol,
    this.warning,
    this.subtitleColor,
    this.selected = false,
    this.actions = const [],
    this.minHeight = 60,
  });

  final String title;
  final String subtitle;

  /// Shown as a chip before [subtitle].
  final TvProtocol? protocol;

  /// Extra phrase appended to [subtitle] in the warning colour.
  final String? warning;

  /// Overrides the default subtitle colour (for example success green).
  final Color? subtitleColor;
  final bool selected;
  final List<Widget> actions;
  final double minHeight;

  @override
  Widget build(BuildContext context) {
    final base = subtitleColor ??
        (selected ? PbColors.onSelectedSecondary : PbColors.textSecondary);
    return Container(
      constraints: BoxConstraints(minHeight: minHeight),
      padding: EdgeInsets.fromLTRB(16, 0, actions.isEmpty ? 16 : 8, 0),
      decoration: BoxDecoration(
        color: selected ? PbColors.selected : PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      child: Row(
        children: [
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: selected ? PbColors.onSelected : PbColors.text,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      if (protocol != null) ...[
                        ProtocolChip(protocol!, onSelected: selected),
                        const SizedBox(width: 8),
                      ],
                      Flexible(
                        child: Text.rich(
                          TextSpan(
                            text: subtitle,
                            children: [
                              if (warning != null)
                                TextSpan(
                                  text: ' · $warning',
                                  style:
                                      const TextStyle(color: PbColors.warning),
                                ),
                            ],
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(fontSize: 13, color: base),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          for (final action in actions) ...[
            const SizedBox(width: 8),
            action,
          ],
        ],
      ),
    );
  }
}

/// Small coloured status dot.
class StatusDot extends StatelessWidget {
  const StatusDot(this.color, {super.key});

  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      );
}

/// One-line guidance in a card, optionally with a spinner while searching.
class DevicesHint extends StatelessWidget {
  const DevicesHint(this.text, {super.key, this.busy = false});

  final String text;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      child: Row(
        children: [
          if (busy) ...[
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
          ],
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 13,
                color: PbColors.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Numeric code field (digits only, at most [maxLength]) styled as in the
/// design: dark fill, outlined, large tracked digits.
class CodeField extends StatelessWidget {
  const CodeField({
    super.key,
    required this.controller,
    this.focusNode,
    this.autofocus = false,
    this.enabled = true,
    this.hint,
    this.fontSize = 22,
    this.height = 52,
    this.borderWidth = 1,
    this.borderColor = PbColors.outline,
    this.maxLength = 6,
    this.onChanged,
    this.onSubmitted,
  });

  final TextEditingController controller;
  final FocusNode? focusNode;
  final bool autofocus;
  final bool enabled;
  final String? hint;
  final double fontSize;
  final double height;
  final double borderWidth;
  final Color borderColor;
  final int maxLength;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(height > 56 ? 14 : 12);
    OutlineInputBorder border(Color c, double w) => OutlineInputBorder(
          borderRadius: radius,
          borderSide: BorderSide(color: c, width: w),
        );
    return SizedBox(
      height: height,
      child: TextField(
        controller: controller,
        focusNode: focusNode,
        autofocus: autofocus,
        enabled: enabled,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        maxLength: maxLength,
        onChanged: onChanged,
        onSubmitted: onSubmitted,
        style: TextStyle(
          fontSize: fontSize,
          fontWeight: fontSize > 26 ? FontWeight.w700 : FontWeight.w600,
          letterSpacing: fontSize * 0.2,
          color: PbColors.onSelected,
        ),
        decoration: InputDecoration(
          counterText: '',
          hintText: hint,
          hintStyle: TextStyle(
            fontSize: fontSize * 0.7,
            letterSpacing: 0,
            fontWeight: FontWeight.w400,
            color: PbColors.textSecondary,
          ),
          filled: true,
          fillColor: PbColors.background,
          contentPadding: const EdgeInsets.symmetric(horizontal: 18),
          border: border(borderColor, borderWidth),
          enabledBorder: border(borderColor, borderWidth),
          disabledBorder: border(PbColors.outlineSoft, 1),
          focusedBorder: border(PbColors.accent, 2),
        ),
      ),
    );
  }
}
