import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// Vertical stack of settings rows with the 6px gap the mockups use.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < children.length; i++) ...[
          if (i > 0) const SizedBox(height: 6),
          children[i],
        ],
      ],
    );
  }
}

/// Section heading with the space above it that separates groups of rows.
class SettingsSectionHeading extends StatelessWidget {
  const SettingsSectionHeading(this.title, {super.key});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 4),
      child: PbSectionHeading(title),
    );
  }
}

/// Muted explanatory sentence above a group of rows.
class SettingsIntro extends StatelessWidget {
  const SettingsIntro(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Text(text, style: const TextStyle(color: PbColors.textSecondary)),
    );
  }
}

BoxDecoration _rowDecoration(bool hovered, bool focused) => BoxDecoration(
      color: hovered ? PbColors.raised : PbColors.card,
      borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
    );

/// Tappable settings row that performs an action (open a screen, quit, …)
/// rather than showing a value. [danger] tints the title with the error colour.
class SettingsActionRow extends StatelessWidget {
  const SettingsActionRow({
    super.key,
    required this.title,
    this.subtitle,
    this.trailing,
    this.danger = false,
    required this.onTap,
  });

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final bool danger;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return PbPressable(
      onTap: onTap,
      semanticLabel: title,
      builder: (context, hovered, focused) => Opacity(
        opacity: onTap == null ? 0.5 : 1,
        child: Container(
          constraints: const BoxConstraints(minHeight: 56),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
          decoration: _rowDecoration(hovered && onTap != null, focused),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        color: danger ? PbColors.error : PbColors.text,
                      ),
                    ),
                    if (subtitle != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(
                          subtitle!,
                          style: const TextStyle(
                            fontSize: 13,
                            color: PbColors.textSecondary,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 24),
                trailing!,
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// One small on/off chip for [SettingsChipsRow].
class SettingsChip {
  const SettingsChip(this.label, this.value, this.onChanged);

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;
}

/// Row with a title, subtitle and a set of independent on/off chips.
class SettingsChipsRow extends StatelessWidget {
  const SettingsChipsRow({
    super.key,
    required this.title,
    this.subtitle,
    required this.chips,
  });

  final String title;
  final String? subtitle;
  final List<SettingsChip> chips;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(minHeight: 68),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      child: Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        runSpacing: 10,
        spacing: 24,
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                    color: PbColors.text,
                  ),
                ),
                if (subtitle != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      subtitle!,
                      style: const TextStyle(
                        fontSize: 13,
                        color: PbColors.textSecondary,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < chips.length; i++) ...[
                if (i > 0) const SizedBox(width: 6),
                _ChipButton(chip: chips[i]),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _ChipButton extends StatelessWidget {
  const _ChipButton({required this.chip});

  final SettingsChip chip;

  @override
  Widget build(BuildContext context) {
    return PbPressable(
      onTap: () => chip.onChanged(!chip.value),
      toggled: chip.value,
      semanticLabel: chip.label,
      builder: (context, hovered, focused) => Container(
        height: PbMetrics.controlHeight,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: chip.value
              ? PbColors.selected
              : (hovered ? PbColors.raised : PbColors.background),
          borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
          border: focused ? Border.all(color: PbColors.accent, width: 2) : null,
        ),
        child: Text(
          chip.label,
          style: TextStyle(
            fontSize: 14,
            fontWeight: chip.value ? FontWeight.w600 : FontWeight.w500,
            color: chip.value ? PbColors.onSelected : PbColors.text,
          ),
        ),
      ),
    );
  }
}

/// Opens a small dialog listing [options]; returns the picked value or null.
Future<T?> showSettingsChoiceDialog<T>(
  BuildContext context, {
  required String title,
  String? message,
  required List<(T, String)> options,
  required T selected,
}) {
  return showDialog<T>(
    context: context,
    barrierColor: PbColors.scrim,
    builder: (ctx) => Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: PbColors.text,
                ),
              ),
              if (message != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    message,
                    style: const TextStyle(color: PbColors.textSecondary),
                  ),
                ),
              const SizedBox(height: 12),
              Flexible(
                child: SingleChildScrollView(
                  child: SettingsGroup(
                    children: [
                      for (final o in options)
                        PbListRow(
                          title: o.$2,
                          minHeight: 52,
                          selected: o.$1 == selected,
                          trailing: o.$1 == selected
                              ? const Padding(
                                  padding: EdgeInsets.only(right: 8),
                                  child: Icon(Icons.check,
                                      size: 20, color: PbColors.onSelected),
                                )
                              : null,
                          onTap: () => Navigator.of(ctx).pop(o.$1),
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: PbButton(
                  label: 'Cancel',
                  variant: PbButtonVariant.quiet,
                  onPressed: () => Navigator.of(ctx).pop(),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
