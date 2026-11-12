import 'package:flutter/material.dart';

/// Design tokens for the PlayBridge desktop redesign.
///
/// Every colour used by app chrome should come from here rather than being
/// written inline, so the palette can be changed in one place.
class PbColors {
  const PbColors._();

  // Surfaces.
  static const background = Color(0xFF0D072E);
  static const sidebar = Color(0xFF120C37);
  static const card = Color(0xFF181241);
  static const raised = Color(0xFF241D54);
  static const selected = Color(0xFF2E3480);

  // Accent and text.
  static const accent = Color(0xFF9EA7FF);
  static const text = Color(0xFFE7E2FF);
  static const textSecondary = Color(0xFFB0A8D8);
  static const onSelectedSecondary = Color(0xFFDFE1FF);
  static const onSelected = Color(0xFFFFFFFF);

  // Outlines.
  static const outline = Color(0xFF5C56A0);
  static const outlineSoft = Color(0xFF3D3770);

  // Status and placeholders.
  static const success = Color(0xFF7FE0B0);
  static const error = Color(0xFFFF8A80);
  static const warning = Color(0xFFFFC46B);
  static const thumbnail = Color(0xFF343C8C);

  // Player surfaces are neutral, not indigo, so they never tint video.
  static const playerBackground = Color(0xFF0B0B10);
  static const playerSurface = Color(0xFF17161F);
  static const playerRaised = Color(0xFF2A2836);
  static const playerText = Color(0xFFC9C6DA);

  /// Scrim drawn over video or content behind a modal hint.
  static const scrim = Color(0x8C000000);
}

/// Shared sizes and radii.
class PbMetrics {
  const PbMetrics._();

  /// Height of every pill button, nav item and tab.
  static const controlHeight = 44.0;
  static const pillRadius = 22.0;
  static const cardRadius = 16.0;
  static const rowRadius = 12.0;
  static const navRadius = 10.0;
  static const sidebarWidth = 208.0;
}

/// Brand font, shared with the Android apps.
const String kPbFontFamily = 'Poppins';

/// The one app-wide [ThemeData], built from [PbColors].
ThemeData buildPbTheme() {
  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    fontFamily: kPbFontFamily,
  );
  const scheme = ColorScheme(
    brightness: Brightness.dark,
    primary: PbColors.accent,
    onPrimary: PbColors.background,
    secondary: PbColors.accent,
    onSecondary: PbColors.background,
    error: PbColors.error,
    onError: PbColors.background,
    surface: PbColors.card,
    onSurface: PbColors.text,
    onSurfaceVariant: PbColors.textSecondary,
    surfaceContainerLowest: PbColors.background,
    surfaceContainerLow: PbColors.sidebar,
    surfaceContainer: PbColors.card,
    surfaceContainerHigh: PbColors.raised,
    surfaceContainerHighest: PbColors.raised,
    outline: PbColors.outline,
    outlineVariant: PbColors.outlineSoft,
    primaryContainer: PbColors.selected,
    onPrimaryContainer: PbColors.onSelected,
    secondaryContainer: PbColors.selected,
    onSecondaryContainer: PbColors.onSelected,
  );
  final text = base.textTheme.apply(
    fontFamily: kPbFontFamily,
    bodyColor: PbColors.text,
    displayColor: PbColors.text,
  );
  final pill = RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(PbMetrics.pillRadius),
  );
  const pillSize = Size(0, PbMetrics.controlHeight);
  return base.copyWith(
    colorScheme: scheme,
    scaffoldBackgroundColor: PbColors.background,
    canvasColor: PbColors.background,
    textTheme: text,
    primaryTextTheme: base.primaryTextTheme.apply(fontFamily: kPbFontFamily),
    cardColor: PbColors.card,
    dividerColor: PbColors.outlineSoft,
    dividerTheme: const DividerThemeData(
      color: PbColors.outlineSoft,
      space: 1,
      thickness: 1,
    ),
    focusColor: PbColors.accent.withValues(alpha: 0.24),
    hoverColor: PbColors.accent.withValues(alpha: 0.10),
    highlightColor: PbColors.accent.withValues(alpha: 0.12),
    splashColor: PbColors.accent.withValues(alpha: 0.12),
    iconTheme: const IconThemeData(color: PbColors.text),
    cardTheme: CardTheme(
      color: PbColors.card,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: PbColors.text,
        foregroundColor: PbColors.background,
        minimumSize: pillSize,
        shape: pill,
        textStyle: const TextStyle(fontWeight: FontWeight.w600),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: PbColors.raised,
        foregroundColor: PbColors.text,
        elevation: 0,
        minimumSize: pillSize,
        shape: pill,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: PbColors.text,
        side: const BorderSide(color: PbColors.outline),
        minimumSize: pillSize,
        shape: pill,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: PbColors.textSecondary,
        minimumSize: pillSize,
        shape: pill,
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? PbColors.background
            : PbColors.text,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? PbColors.accent
            : PbColors.outlineSoft,
      ),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? PbColors.accent
            : Colors.transparent,
      ),
      checkColor: const WidgetStatePropertyAll(PbColors.background),
      side: const BorderSide(color: PbColors.outline, width: 1.5),
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? PbColors.accent
            : PbColors.outline,
      ),
    ),
    sliderTheme: const SliderThemeData(
      activeTrackColor: PbColors.accent,
      inactiveTrackColor: PbColors.outlineSoft,
      thumbColor: PbColors.accent,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: PbColors.accent,
      linearTrackColor: PbColors.outlineSoft,
    ),
    dialogTheme: DialogTheme(
      backgroundColor: PbColors.card,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PbMetrics.cardRadius),
      ),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: PbColors.raised,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: PbColors.card,
      surfaceTintColor: Colors.transparent,
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: PbColors.raised,
      contentTextStyle: const TextStyle(
        fontFamily: kPbFontFamily,
        color: PbColors.text,
      ),
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
      ),
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(
        color: PbColors.raised,
        borderRadius: BorderRadius.circular(8),
      ),
      textStyle: const TextStyle(
        fontFamily: kPbFontFamily,
        fontSize: 12,
        color: PbColors.text,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: PbColors.raised,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
        borderSide: const BorderSide(color: PbColors.outlineSoft),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
        borderSide: const BorderSide(color: PbColors.outlineSoft),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(PbMetrics.rowRadius),
        borderSide: const BorderSide(color: PbColors.accent, width: 2),
      ),
    ),
    scrollbarTheme: ScrollbarThemeData(
      thumbColor: WidgetStatePropertyAll(
        PbColors.outline.withValues(alpha: 0.6),
      ),
    ),
  );
}
