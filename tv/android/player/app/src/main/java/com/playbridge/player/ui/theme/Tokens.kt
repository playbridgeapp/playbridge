package com.playbridge.player.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Themed colours for the redesign that the tv-material3 ColorScheme has no slot for
 * (surface containers, dim text, focus treatment, status). Read via [TvTheme.colors].
 * Dark, AMOLED and Light each provide their own values.
 */
data class TvColors(
    val surface: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val bright: Color,
    val outline: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceDim: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val tileBlue: Color,
    val onTileBlue: Color,
    val tilePlum: Color,
    val onTilePlum: Color,
    val focusFill: Color,
    val onFocusFill: Color,
    val focusRing: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

internal val DarkTvColors = TvColors(
    surface = Surface, containerLow = SurfaceContainerLow, container = SurfaceContainer,
    containerHigh = SurfaceContainerHigh, containerHighest = SurfaceContainerHighest, bright = SurfaceBright,
    outline = OutlineVariant, onSurface = OnSurface, onSurfaceMuted = OnSurfaceVariant, onSurfaceDim = OnSurfaceDim,
    accent = Primary, onAccent = OnPrimary, accentContainer = SecondaryContainer, onAccentContainer = OnPrimaryContainer,
    tileBlue = PrimaryContainer, onTileBlue = OnPrimaryContainer, tilePlum = TertiaryContainer, onTilePlum = OnTertiaryContainer,
    focusFill = PlayerFocusFill, onFocusFill = PlayerOnFocusFill, focusRing = PlayerFocusFill,
    success = StatusSuccess, warning = StatusWarning, error = ErrorColor,
)

internal val AmoledTvColors = DarkTvColors.copy(
    surface = AmoledSurface, containerLow = AmoledSurfaceContainerLow, container = AmoledSurfaceContainer,
    containerHigh = AmoledSurfaceContainerHigh, containerHighest = AmoledSurfaceContainerHighest, bright = AmoledSurfaceBright,
)

internal val LightTvColors = TvColors(
    surface = LightSurface, containerLow = LightSurfaceContainerLow, container = LightSurfaceContainer,
    containerHigh = LightSurfaceContainerHigh, containerHighest = LightSurfaceContainerHighest, bright = LightSurfaceBright,
    outline = LightOutlineVariant, onSurface = LightOnSurface, onSurfaceMuted = LightOnSurfaceVariant, onSurfaceDim = LightOnSurfaceDim,
    accent = LightPrimary, onAccent = LightOnPrimary, accentContainer = LightSecondaryContainer, onAccentContainer = LightOnSecondaryContainer,
    tileBlue = LightPrimaryContainer, onTileBlue = LightOnPrimaryContainer, tilePlum = LightTertiaryContainer, onTilePlum = LightOnTertiaryContainer,
    focusFill = LightOnSurface, onFocusFill = LightSurface, focusRing = LightOnSurface,
    success = LightStatusSuccess, warning = LightStatusWarning, error = LightError,
)

internal val LocalTvColors = staticCompositionLocalOf { DarkTvColors }

/** Entry point to the redesign tokens: `TvTheme.colors.container`, `TvTheme.player.panel`. */
object TvTheme {
    val colors: TvColors @Composable @ReadOnlyComposable get() = LocalTvColors.current
    val player: TvPlayerColors get() = TvPlayerColors
}

/** Neutral near-black player tones; identical in every theme because they sit over video. */
object TvPlayerColors {
    val background = PlayerBackground
    val panel = PlayerPanel
    val row = PlayerRow
    val control = PlayerControl
    val track = PlayerTrack
    val buffer = PlayerBuffer
    val divider = PlayerDivider
    val onSurface = PlayerOnSurface
    val onSurfaceMuted = PlayerOnSurfaceMuted
    val onSurfaceDim = PlayerOnSurfaceDim
    val focusFill = PlayerFocusFill
    val onFocusFill = PlayerOnFocusFill
    val focusRing = PlayerFocusFill
    /** Top / bottom video scrim opacity used behind the controls. */
    const val scrimTopAlpha = 0.72f
    const val scrimBottomAlpha = 0.8f
}

/** Corner radii. Pills and circles use [pill] / [circle]. */
object TvRadius {
    val bar = 3.dp
    val seekBar = 4.dp
    val chip = 17.dp
    val thumbSmall = 12.dp
    val row = 14.dp
    val tile = 14.dp
    val option = 16.dp
    val code = 18.dp
    val card = 24.dp
    val pill = RoundedCornerShape(percent = 50)
    val circle = CircleShape
    val rowShape = RoundedCornerShape(row)
    val tileShape = RoundedCornerShape(tile)
    val cardShape = RoundedCornerShape(card)
    val optionShape = RoundedCornerShape(option)
}

/** Spacing scale and fixed layout sizes, in dp at the 1280x720 mockup scale. */
object TvSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    /** Left/right screen gutter and top inset for full screens. */
    val screenGutter: Dp = 56.dp
    val screenTop: Dp = 36.dp
    /** Distance of corner cards from the screen edge. */
    val cornerInset: Dp = 48.dp
    val playerEdge: Dp = 40.dp
    val panelPaddingV: Dp = 40.dp
    val panelPaddingH: Dp = 36.dp
    val rowGap: Dp = 8.dp
}

/** Fixed component sizes. Focus never changes any of these. */
object TvSize {
    val pillHeight: Dp = 56.dp
    val pillHeightCompact: Dp = 48.dp
    val iconButtonSmall: Dp = 48.dp
    val iconButton: Dp = 60.dp
    val iconButtonLarge: Dp = 72.dp
    val listRow: Dp = 60.dp
    val settingsRow: Dp = 84.dp
    val navRow: Dp = 56.dp
    val chip: Dp = 34.dp
    val switchWidth: Dp = 56.dp
    val switchHeight: Dp = 32.dp
    val switchThumb: Dp = 24.dp
    val sidePanelWidth: Dp = 480.dp
    val cornerCardWidth: Dp = 480.dp
    val pairingCardWidth: Dp = 440.dp
    val bookmarkTileWidth: Dp = 280.dp
    val bookmarkTileHeight: Dp = 158.dp
    val recentTileWidth: Dp = 216.dp
    val recentTileHeight: Dp = 122.dp
    val seekBarHeight: Dp = 8.dp
    val controlLabelSlot: Dp = 220.dp
    val focusRingWidth: Dp = 4.dp
    val focusRingGap: Dp = 3.dp
}

/** Type scale from the mockups (Poppins). Sizes are sp at 1 mockup px = 1 sp. */
object TvText {
    private val f = PoppinsFontFamily
    /** Home page title, "Living Room TV". */
    val pageTitle = TextStyle(fontFamily = f, fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    /** Settings title. */
    val screenTitle = TextStyle(fontFamily = f, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    /** Side panel title and the player's media title. */
    val panelTitle = TextStyle(fontFamily = f, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
    /** Corner card headline, e.g. "Update available". */
    val cardHeadline = TextStyle(fontFamily = f, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    /** Corner card title, e.g. "Galaxy S24 wants to connect". */
    val cardTitle = TextStyle(fontFamily = f, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
    /** Section heading, e.g. "Bookmarks". */
    val sectionHeading = TextStyle(fontFamily = f, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    /** Settings row title. */
    val settingTitle = TextStyle(fontFamily = f, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    /** List/nav row label. */
    val row = TextStyle(fontFamily = f, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val rowSelected = row.copy(fontWeight = FontWeight.SemiBold)
    /** Button label (pill), control label slot. */
    val button = TextStyle(fontFamily = f, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    /** Row trailing value and chip-sized body, 17sp. */
    val value = TextStyle(fontFamily = f, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    /** Caption / secondary line, 16sp. */
    val caption = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Footnote, 15sp. */
    val small = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    /** Tile overlay text, 14sp. */
    val micro = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** Pairing code digits. */
    val code = TextStyle(fontFamily = f, fontSize = 76.sp, lineHeight = 84.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
    /** Subtitle sample in the player mockups. */
    val subtitleSample = TextStyle(fontFamily = f, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
}
