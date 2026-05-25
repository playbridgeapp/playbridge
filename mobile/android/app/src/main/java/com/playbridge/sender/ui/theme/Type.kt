package com.playbridge.sender.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.playbridge.sender.R

// Bundled (not downloadable) so it can't fall back to FontFamily.Default — that
// fallback is what Samsung's FlipFont silently swaps for the user's system font
// ("my chocochoco"). Reading the font from the APK pins a specific typeface that
// FlipFont can't touch. space_grotesk.ttf is a variable font; each weight reuses
// the same file (Compose derives the wght axis from the FontWeight on API 26+).
val AppFontFamily = FontFamily(
    Font(R.font.space_grotesk, weight = FontWeight.Normal),
    Font(R.font.space_grotesk, weight = FontWeight.Medium),
    Font(R.font.space_grotesk, weight = FontWeight.SemiBold),
    Font(R.font.space_grotesk, weight = FontWeight.Bold)
)

// Apply the single app font to every Material 3 text style. Any style left
// unspecified falls back to FontFamily.Default. The five tuned styles keep their
// custom size / weight / tracking.
private val default = Typography()

val AppTypography = Typography(
    displayLarge = default.displayLarge.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-0.02).em
    ),
    displayMedium = default.displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = default.displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = default.headlineLarge.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).em
    ),
    headlineMedium = default.headlineMedium.copy(fontFamily = AppFontFamily),
    headlineSmall = default.headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = default.titleLarge.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleMedium = default.titleMedium.copy(fontFamily = AppFontFamily),
    titleSmall = default.titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = default.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = default.bodyMedium.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = default.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = default.labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = default.labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = default.labelSmall.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.06.em
    )
)
