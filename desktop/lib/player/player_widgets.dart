import 'package:flutter/material.dart';

import '../ui/pb_theme.dart';
import '../ui/pb_widgets.dart';

/// Neutral shades the player mockups use that have no token in `PbColors`.
///
/// Video chrome stays neutral (never indigo) so it does not tint the picture.
class PlayerTones {
  const PlayerTones._();

  /// Text and icons on player surfaces.
  static const foreground = Color(0xFFFFFFFF);

  /// Quiet row/card fill on a panel ([PbColors.playerSurface]).
  static const row = Color(0xFF23222E);

  /// Unfilled part of a seek or volume track.
  static const track = Color(0xFF3A3848);

  /// Buffered-but-unplayed part of the seek track.
  static const buffered = Color(0xFF6E6A86);
}

/// Width of the right-hand side panels.
const double kPlayerPanelWidth = 360;

/// Look of a [PlayerIconButton] or [PlayerPill].
enum PlayerButtonTone {
  /// No fill; used for the row of controls on the video.
  plain,

  /// Dark grey fill.
  raised,

  /// White fill, dark glyph: the one main action on a surface.
  primary,
}

BoxDecoration _focusRing(BoxDecoration base, bool focused) => focused
    ? base.copyWith(border: Border.all(color: PbColors.accent, width: 2))
    : base;

/// Round 44px (or [size]) icon button for the player; [tooltip] doubles as the
/// accessible label. Unlike a Material button it never takes keyboard focus
/// away from the app's shortcuts when clicked.
class PlayerIconButton extends StatelessWidget {
  const PlayerIconButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.tone = PlayerButtonTone.plain,
    this.size = PbMetrics.controlHeight,
    this.iconSize = 22,
    this.active = false,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final PlayerButtonTone tone;
  final double size;
  final double iconSize;

  /// Tints the glyph with the accent colour (for example a bookmarked video).
  final bool active;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = switch (tone) {
      PlayerButtonTone.plain => (Colors.transparent, PlayerTones.foreground),
      PlayerButtonTone.raised => (
          PbColors.playerRaised,
          PlayerTones.foreground
        ),
      PlayerButtonTone.primary => (
          PlayerTones.foreground,
          PbColors.playerBackground
        ),
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
            decoration: _focusRing(
              BoxDecoration(
                color: hovered
                    ? (tone == PlayerButtonTone.primary
                        ? const Color(0xFFE6E6EC)
                        : Colors.white.withValues(alpha: 0.14))
                    : bg,
                shape: BoxShape.circle,
              ),
              focused,
            ),
            child: Icon(
              icon,
              size: iconSize,
              color: active ? PbColors.accent : fg,
            ),
          ),
        ),
      ),
    );
  }
}

/// Pill button for the player (44px, or 48px when [large]).
class PlayerPill extends StatelessWidget {
  const PlayerPill({
    super.key,
    required this.label,
    required this.onPressed,
    this.tone = PlayerButtonTone.raised,
    this.icon,
    this.large = false,
    this.autofocus = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final PlayerButtonTone tone;
  final IconData? icon;
  final bool large;
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    final (bg, fg, weight) = switch (tone) {
      PlayerButtonTone.primary => (
          PlayerTones.foreground,
          PbColors.playerBackground,
          FontWeight.w600
        ),
      PlayerButtonTone.raised => (
          PbColors.playerRaised,
          PlayerTones.foreground,
          FontWeight.w500
        ),
      PlayerButtonTone.plain => (
          Colors.transparent,
          PlayerTones.foreground,
          FontWeight.w500
        ),
    };
    final height = large ? 48.0 : PbMetrics.controlHeight;
    return PbPressable(
      onTap: onPressed,
      autofocus: autofocus,
      semanticLabel: label,
      builder: (context, hovered, focused) => Opacity(
        opacity: onPressed == null ? 0.4 : 1,
        child: Container(
          height: height,
          padding: EdgeInsets.symmetric(horizontal: large ? 24 : 18),
          decoration: _focusRing(
            BoxDecoration(
              color: hovered
                  ? Color.lerp(bg, Colors.white,
                      tone == PlayerButtonTone.primary ? 0.1 : 0.12)
                  : bg,
              borderRadius: BorderRadius.circular(height / 2),
            ),
            focused,
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (icon != null) ...[
                Icon(icon, size: 20, color: fg),
                const SizedBox(width: 8),
              ],
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: large ? 15 : 14,
                    fontWeight: weight,
                    color: fg,
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

/// Dark right-hand panel: title, close button and a scrolling body. Does not
/// grab keyboard focus, so Space, arrows and the other shortcuts keep working.
class PlayerPanelShell extends StatelessWidget {
  const PlayerPanelShell({
    super.key,
    required this.title,
    required this.onClose,
    required this.child,
    this.semanticLabel,
  });

  final String title;
  final VoidCallback onClose;
  final Widget child;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      container: true,
      label: semanticLabel ?? title,
      child: Container(
        width: kPlayerPanelWidth,
        color: PbColors.playerSurface,
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: PlayerTones.foreground,
                      ),
                    ),
                  ),
                ),
                PlayerIconButton(
                  icon: Icons.close,
                  iconSize: 20,
                  tooltip: 'Close',
                  onPressed: onClose,
                ),
              ],
            ),
            Expanded(child: child),
          ],
        ),
      ),
    );
  }
}

/// Small grey section title inside a panel.
class PlayerPanelHeading extends StatelessWidget {
  const PlayerPanelHeading(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Semantics(
          header: true,
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: PbColors.playerText,
            ),
          ),
        ),
      );
}

/// A 44px (48px when [tall]) list row: label on the left, a check mark when
/// [selected], or a quiet [value] on the right.
class PlayerOptionRow extends StatelessWidget {
  const PlayerOptionRow({
    super.key,
    required this.label,
    required this.onTap,
    this.selected = false,
    this.value,
    this.accent = false,
    this.tall = false,
  });

  final String label;
  final VoidCallback? onTap;
  final bool selected;

  /// Quiet text on the right (for example `On` or `Off`).
  final String? value;

  /// Draws the label in the accent colour (for an add/open action).
  final bool accent;
  final bool tall;

  @override
  Widget build(BuildContext context) {
    return PbPressable(
      onTap: onTap,
      selected: selected ? true : null,
      semanticLabel: value == null ? label : '$label, $value',
      builder: (context, hovered, focused) => Opacity(
        opacity: onTap == null ? 0.5 : 1,
        child: Container(
          height: tall ? 48 : 44,
          padding: const EdgeInsets.symmetric(horizontal: 14),
          decoration: _focusRing(
            BoxDecoration(
              color: selected
                  ? PbColors.playerRaised
                  : (hovered ? PlayerTones.row : Colors.transparent),
              borderRadius: BorderRadius.circular(10),
            ),
            focused,
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: selected
                        ? FontWeight.w600
                        : (accent ? FontWeight.w500 : FontWeight.w400),
                    color: accent ? PbColors.accent : PlayerTones.foreground,
                  ),
                ),
              ),
              if (selected)
                const Icon(Icons.check, size: 18, color: PlayerTones.foreground)
              else if (value != null)
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Text(
                    value!,
                    style: const TextStyle(
                      fontSize: 14,
                      color: PbColors.playerText,
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

/// One choice in a [PlayerChoiceGrid].
class PlayerChoice {
  const PlayerChoice(this.label, this.selected, this.onTap);

  final String label;
  final bool selected;
  final VoidCallback? onTap;
}

/// Equal-width grid of single-select buttons (speed, picture). The selected
/// one is white.
class PlayerChoiceGrid extends StatelessWidget {
  const PlayerChoiceGrid({
    super.key,
    required this.choices,
    required this.columns,
  });

  final List<PlayerChoice> choices;
  final int columns;

  @override
  Widget build(BuildContext context) {
    const gap = 6.0;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = (constraints.maxWidth - gap * (columns - 1)) / columns;
        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: [
            for (final c in choices)
              SizedBox(
                width: width,
                child: PbPressable(
                  onTap: c.onTap,
                  selected: c.selected,
                  semanticLabel: c.label,
                  builder: (context, hovered, focused) => Container(
                    height: 44,
                    alignment: Alignment.center,
                    decoration: _focusRing(
                      BoxDecoration(
                        color: c.selected
                            ? PlayerTones.foreground
                            : (hovered
                                ? const Color(0xFF2E2D3B)
                                : PlayerTones.row),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      focused,
                    ),
                    child: Text(
                      c.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight:
                            c.selected ? FontWeight.w600 : FontWeight.w400,
                        color: c.selected
                            ? PbColors.playerBackground
                            : PlayerTones.foreground,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
