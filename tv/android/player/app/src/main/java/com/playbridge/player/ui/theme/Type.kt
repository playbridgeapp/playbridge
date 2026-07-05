package com.playbridge.player.ui.theme

import androidx.tv.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.playbridge.player.R

// Bundled Poppins (matches the phone app). Reading the font from the APK pins the
// typeface instead of downloading it at runtime; a single static weight is
// registered and Compose synthesizes heavier weights.
val PoppinsFontFamily = FontFamily(Font(R.font.poppins))

// Apply Poppins to every tv-material3 text style. Untuned slots inherit the
// default size/line-height and only swap the family; the display / headline /
// title / body / label styles keep the TV-scaled (≈1.25×) sizes used for the
// 10-foot UI.
private val default = Typography()

val AppTypography = Typography(
    displayLarge = default.displayLarge.copy(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 60.sp, // 48 * 1.25
        letterSpacing = (-0.02).em
    ),
    displayMedium = default.displayMedium.copy(fontFamily = PoppinsFontFamily),
    displaySmall = default.displaySmall.copy(fontFamily = PoppinsFontFamily),
    headlineLarge = default.headlineLarge.copy(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp, // 32 * 1.25
        letterSpacing = (-0.02).em
    ),
    headlineMedium = default.headlineMedium.copy(fontFamily = PoppinsFontFamily),
    headlineSmall = default.headlineSmall.copy(fontFamily = PoppinsFontFamily),
    titleLarge = default.titleLarge.copy(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.5.sp // 22 * 1.25
    ),
    titleMedium = default.titleMedium.copy(fontFamily = PoppinsFontFamily),
    titleSmall = default.titleSmall.copy(fontFamily = PoppinsFontFamily),
    bodyLarge = default.bodyLarge.copy(fontFamily = PoppinsFontFamily),
    bodyMedium = default.bodyMedium.copy(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.5.sp, // 14 * 1.25
        lineHeight = 25.sp // 20 * 1.25
    ),
    bodySmall = default.bodySmall.copy(fontFamily = PoppinsFontFamily),
    labelLarge = default.labelLarge.copy(fontFamily = PoppinsFontFamily),
    labelMedium = default.labelMedium.copy(fontFamily = PoppinsFontFamily),
    labelSmall = default.labelSmall.copy(
        fontFamily = PoppinsFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.75.sp, // 11 * 1.25
        letterSpacing = 0.06.em
    )
)
