import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'pb_theme.dart';

/// Builds the visual for a [PbPressable] given its hover/focus state.
typedef PbPressableBuilder = Widget Function(
    BuildContext context, bool hovered, bool focused);

/// Keyboard-focusable, hoverable tap target (Enter/Space activate); the base of every control here.
class PbPressable extends StatelessWidget {
  const PbPressable({
    super.key,
    required this.onTap,
    required this.builder,
    this.semanticLabel,
    this.autofocus = false,
    this.focusNode,
    this.toggled,
    this.selected,
  });

  /// Null disables the control (not focusable, no hover).
  final VoidCallback? onTap;
  final PbPressableBuilder builder;
  final String? semanticLabel;
  final bool autofocus;
  final FocusNode? focusNode;

  /// Reported to assistive tech as a switch state when non-null.
  final bool? toggled;

  /// Reported to assistive tech as the selected item when non-null.
  final bool? selected;

  @override
  Widget build(BuildContext context) {
    return _PressableBody(
      onTap: onTap,
      builder: builder,
      semanticLabel: semanticLabel,
      autofocus: autofocus,
      focusNode: focusNode,
      toggled: toggled,
      selected: selected,
    );
  }
}

class _PressableBody extends StatefulWidget {
  const _PressableBody({
    required this.onTap,
    required this.builder,
    required this.semanticLabel,
    required this.autofocus,
    required this.focusNode,
    required this.toggled,
    required this.selected,
  });

  final VoidCallback? onTap;
  final PbPressableBuilder builder;
  final String? semanticLabel;
  final bool autofocus;
  final FocusNode? focusNode;
  final bool? toggled;
  final bool? selected;

  @override
  State<_PressableBody> createState() => _PressableBodyState();
}

class _PressableBodyState extends State<_PressableBody> {
  bool _hovered = false;
  bool _focused = false;

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null;
    return Semantics(
      button: true,
      enabled: enabled,
      label: widget.semanticLabel,
      toggled: widget.toggled,
      selected: widget.selected,
      child: FocusableActionDetector(
        enabled: enabled,
        autofocus: widget.autofocus,
        focusNode: widget.focusNode,
        mouseCursor:
            enabled ? SystemMouseCursors.click : SystemMouseCursors.basic,
        onShowHoverHighlight: (v) => setState(() => _hovered = v),
        onShowFocusHighlight: (v) => setState(() => _focused = v),
        shortcuts: const {
          SingleActivator(LogicalKeyboardKey.enter): ActivateIntent(),
          SingleActivator(LogicalKeyboardKey.numpadEnter): ActivateIntent(),
          SingleActivator(LogicalKeyboardKey.space): ActivateIntent(),
        },
        actions: {
          ActivateIntent: CallbackAction<ActivateIntent>(
            onInvoke: (_) {
              widget.onTap?.call();
              return null;
            },
          ),
        },
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: widget.onTap,
          child: widget.builder(context, enabled && _hovered, _focused),
        ),
      ),
    );
  }
}

/// Visible keyboard-focus ring painted just outside a control.
BoxDecoration _ring(BoxDecoration base, bool focused) {
  if (!focused) return base;
  return base.copyWith(
    border: Border.all(color: PbColors.accent, width: 2),
  );
}

Color _lift(Color c, bool hovered, [double amount = 0.08]) =>
    hovered ? Color.lerp(c, Colors.white, amount)! : c;

/// Look of a [PbButton].
enum PbButtonVariant { primary, secondary, quiet }

/// 44px pill button: primary (light fill), secondary (raised fill) or quiet (text only), with an optional leading icon.
class PbButton extends StatelessWidget {
  const PbButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.variant = PbButtonVariant.primary,
    this.icon,
    this.autofocus = false,
  });

  final String label;

  /// Null renders the button disabled.
  final VoidCallback? onPressed;
  final PbButtonVariant variant;
  final IconData? icon;
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    final (bg, fg, weight) = switch (variant) {
      PbButtonVariant.primary => (
          PbColors.text,
          PbColors.background,
          FontWeight.w600
        ),
      PbButtonVariant.secondary => (
          PbColors.raised,
          PbColors.text,
          FontWeight.w500
        ),
      PbButtonVariant.quiet => (
          Colors.transparent,
          PbColors.textSecondary,
          FontWeight.w500
        ),
    };
    final disabled = onPressed == null;
    return PbPressable(
      onTap: onPressed,
      autofocus: autofocus,
      semanticLabel: label,
      builder: (context, hovered, focused) {
        final fill = variant == PbButtonVariant.quiet
            ? (hovered ? PbColors.raised : Colors.transparent)
            : _lift(bg, hovered);
        return Opacity(
          opacity: disabled ? 0.4 : 1,
          child: Container(
            height: PbMetrics.controlHeight,
            padding: EdgeInsets.symmetric(horizontal: icon == null ? 20 : 18),
            decoration: _ring(
              BoxDecoration(
                color: fill,
                borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
              ),
              focused,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (icon != null) ...[
                  Icon(icon, size: 18, color: fg),
                  const SizedBox(width: 8),
                ],
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: weight,
                      color: variant == PbButtonVariant.quiet && hovered
                          ? PbColors.text
                          : fg,
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// 44px circular icon button; [tooltip] doubles as the accessible label.
class PbIconButton extends StatelessWidget {
  const PbIconButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.variant = PbButtonVariant.quiet,
    this.size = PbMetrics.controlHeight,
    this.iconSize = 20,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final PbButtonVariant variant;
  final double size;
  final double iconSize;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = switch (variant) {
      PbButtonVariant.primary => (PbColors.text, PbColors.background),
      PbButtonVariant.secondary => (PbColors.raised, PbColors.text),
      PbButtonVariant.quiet => (Colors.transparent, PbColors.text),
    };
    return Tooltip(
      message: tooltip,
      child: PbPressable(
        onTap: onPressed,
        semanticLabel: tooltip,
        builder: (context, hovered, focused) => Opacity(
          opacity: onPressed == null ? 0.4 : 1,
          child: Container(
            width: size,
            height: size,
            alignment: Alignment.center,
            decoration: _ring(
              BoxDecoration(
                color: variant == PbButtonVariant.quiet
                    ? (hovered ? PbColors.raised : bg)
                    : _lift(bg, hovered),
                shape: BoxShape.circle,
              ),
              focused,
            ),
            child: Icon(icon, size: iconSize, color: fg),
          ),
        ),
      ),
    );
  }
}

/// Rounded row card with title, optional subtitle and trailing widget; [selected] uses the selected-blue fill. Tappable when [onTap] is set.
class PbListRow extends StatelessWidget {
  const PbListRow({
    super.key,
    required this.title,
    this.subtitle,
    this.trailing,
    this.selected = false,
    this.onTap,
    this.minHeight = 60,
  });

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final bool selected;
  final VoidCallback? onTap;
  final double minHeight;

  @override
  Widget build(BuildContext context) {
    Widget body(bool hovered, bool focused) => Container(
          constraints: BoxConstraints(minHeight: minHeight),
          padding: EdgeInsets.fromLTRB(16, 0, trailing == null ? 16 : 8, 0),
          decoration: _ring(
            BoxDecoration(
              color: selected
                  ? PbColors.selected
                  : (hovered ? PbColors.raised : PbColors.card),
              borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
            ),
            focused,
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
                      if (subtitle != null)
                        Text(
                          subtitle!,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 13,
                            color: selected
                                ? PbColors.onSelectedSecondary
                                : PbColors.textSecondary,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 16),
                trailing!,
              ],
            ],
          ),
        );
    if (onTap == null) return body(false, false);
    return PbPressable(
      onTap: onTap,
      selected: selected,
      builder: (context, hovered, focused) => body(hovered, focused),
    );
  }
}

/// Section title (16px semibold) with an optional trailing action on the right.
class PbSectionHeading extends StatelessWidget {
  const PbSectionHeading(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Semantics(
            header: true,
            child: Text(
              title,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: PbColors.text,
              ),
            ),
          ),
        ),
        if (trailing != null) trailing!,
      ],
    );
  }
}

/// Pill-shaped segmented tab control; the selected tab uses the selected-blue fill.
class PbSegmentedTabs extends StatelessWidget {
  const PbSegmentedTabs({
    super.key,
    required this.labels,
    required this.selectedIndex,
    required this.onChanged,
  });

  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: PbColors.card,
        borderRadius: BorderRadius.circular(26),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < labels.length; i++) ...[
            if (i > 0) const SizedBox(width: 4),
            PbPressable(
              onTap: () => onChanged(i),
              selected: i == selectedIndex,
              semanticLabel: labels[i],
              builder: (context, hovered, focused) {
                final isSel = i == selectedIndex;
                return Container(
                  height: PbMetrics.controlHeight,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  alignment: Alignment.center,
                  decoration: _ring(
                    BoxDecoration(
                      color: isSel
                          ? PbColors.selected
                          : (hovered ? PbColors.raised : Colors.transparent),
                      borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
                    ),
                    focused,
                  ),
                  child: Text(
                    labels[i],
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: isSel ? FontWeight.w600 : FontWeight.w500,
                      color:
                          isSel ? PbColors.onSelected : PbColors.textSecondary,
                    ),
                  ),
                );
              },
            ),
          ],
        ],
      ),
    );
  }
}

/// Title + optional subtitle column used by the settings rows.
class _SettingsText extends StatelessWidget {
  const _SettingsText(this.title, this.subtitle);

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    return Column(
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
    );
  }
}

BoxDecoration _settingsRowDecoration(bool hovered, bool focused) => _ring(
      BoxDecoration(
        color: hovered ? PbColors.raised : PbColors.card,
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
      focused,
    );

/// Settings row with a title, subtitle and an on/off switch; the whole row toggles.
class PbSwitchRow extends StatelessWidget {
  const PbSwitchRow({
    super.key,
    required this.title,
    this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String? subtitle;
  final bool value;

  /// Null disables the row.
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    final cb = onChanged;
    return PbPressable(
      onTap: cb == null ? null : () => cb(!value),
      toggled: value,
      semanticLabel: title,
      builder: (context, hovered, focused) => Opacity(
        opacity: cb == null ? 0.5 : 1,
        child: Container(
          constraints: const BoxConstraints(minHeight: 68),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
          decoration: _settingsRowDecoration(hovered, focused),
          child: Row(
            children: [
              Expanded(child: _SettingsText(title, subtitle)),
              const SizedBox(width: 24),
              AnimatedContainer(
                duration: const Duration(milliseconds: 120),
                width: 48,
                height: 28,
                padding: const EdgeInsets.all(4),
                alignment: value ? Alignment.centerRight : Alignment.centerLeft,
                decoration: BoxDecoration(
                  color: value ? PbColors.accent : PbColors.outlineSoft,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Container(
                  width: 20,
                  height: 20,
                  decoration: BoxDecoration(
                    color: value ? PbColors.background : PbColors.text,
                    shape: BoxShape.circle,
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

/// Settings row with a title, subtitle and the current value in accent colour; tapping it opens a choice or editor.
class PbValueRow extends StatelessWidget {
  const PbValueRow({
    super.key,
    required this.title,
    this.subtitle,
    required this.value,
    this.onTap,
  });

  final String title;
  final String? subtitle;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return PbPressable(
      onTap: onTap,
      semanticLabel: '$title, $value',
      builder: (context, hovered, focused) => Container(
        constraints: const BoxConstraints(minHeight: 68),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
        decoration: _settingsRowDecoration(hovered, focused),
        child: Row(
          children: [
            Expanded(child: _SettingsText(title, subtitle)),
            const SizedBox(width: 24),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 320),
              child: Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  color: PbColors.accent,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Bottom-right notice card (max 420px wide) with title, message, optional [child] and action buttons; place it in a bottom-right aligned overlay.
class PbCornerCard extends StatelessWidget {
  const PbCornerCard({
    super.key,
    required this.title,
    this.message,
    this.child,
    this.actions = const [],
    this.leading,
  });

  final String title;
  final String? message;
  final Widget? child;

  /// Usually [PbButton]s, laid out in a wrapping row.
  final List<Widget> actions;

  /// Optional icon shown before the title.
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420),
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: PbColors.card,
          borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
          border: Border.all(color: PbColors.outline),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                if (leading != null) ...[leading!, const SizedBox(width: 10)],
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: PbColors.text,
                    ),
                  ),
                ),
              ],
            ),
            if (message != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  message!,
                  style: const TextStyle(color: PbColors.textSecondary),
                ),
              ),
            if (child != null)
              Padding(padding: const EdgeInsets.only(top: 10), child: child),
            if (actions.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Wrap(spacing: 8, runSpacing: 8, children: actions),
              ),
          ],
        ),
      ),
    );
  }
}
