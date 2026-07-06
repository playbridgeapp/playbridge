package com.playbridge.sender.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.toHct

enum class AppTheme(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED"),
    LIGHT("Light");

    companion object {
        fun fromPrefs(context: Context): AppTheme {
            val stored = context
                .getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getString("app_theme", "DARK") ?: "DARK"
            return entries.find { it.name == stored } ?: DARK
        }
    }
}

/**
 * Process-wide, reactive holder for the active [AppTheme].
 *
 * The theme is a Compose [androidx.compose.runtime.MutableState], so changing it
 * recomposes every subtree reading it — the app re-themes instantly with no
 * `Activity.recreate()`. Recreating the Activity to apply a theme was the source of a
 * bug: it reset in-screen navigation (e.g. Settings → Appearance jumped back to the
 * Settings hub) and corrupted the shell's "return to" target so Back got stuck.
 */
object ThemeController {
    private val state = androidx.compose.runtime.mutableStateOf<AppTheme?>(null)

    /** Read the current theme in composition (subscribes); seeds from prefs on first use. */
    @Composable
    fun current(): AppTheme {
        val context = LocalContext.current
        val value = state.value
        if (value != null) return value
        return AppTheme.fromPrefs(context).also { state.value = it }
    }

    /** Persist and apply [theme] immediately (recomposes everything reading [current]). */
    fun set(context: Context, theme: AppTheme) {
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_theme", theme.name).apply()
        state.value = theme
    }

    /** Non-composable read for callers that just need the persisted value. */
    fun peek(context: Context): AppTheme = state.value ?: AppTheme.fromPrefs(context)
}

private val DarkColorScheme = darkColorScheme(
    background = Surface,
    surface = Surface,
    surfaceVariant = SurfaceContainerLow,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    onBackground = OnSurface,
    surfaceTint = Primary,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    inversePrimary = InversePrimary,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    scrim = ScrimColor
)

private val AmoledColorScheme = darkColorScheme(
    background = AmoledSurface,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceContainerLow,
    surfaceContainerLow = AmoledSurfaceContainerLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceContainerHigh,
    surfaceContainerHighest = AmoledSurfaceContainerHighest,
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = AmoledPrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    onBackground = OnSurface,
    surfaceTint = Primary,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    inversePrimary = InversePrimary,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    scrim = ScrimColor
)

private val LightColorScheme = lightColorScheme(
    background = LightSurface,
    surface = LightSurface,
    surfaceVariant = LightSurfaceContainerLow,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    onBackground = LightOnSurface,
    surfaceTint = LightPrimary,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    inversePrimary = LightInversePrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    scrim = ScrimColor
)

// Expressive rounded shape scale (mirrors ArchiveTune). Drives the corner radii
// of cards, sheets, chips, buttons across the app.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun staticSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.DARK   -> DarkColorScheme
    AppTheme.AMOLED -> AmoledColorScheme
    AppTheme.LIGHT  -> LightColorScheme
}

@Composable
fun PlayBridgeTheme(
    content: @Composable () -> Unit
) {
    // Reactive: changing the theme recomposes this and re-themes the whole tree.
    val theme = ThemeController.current()

    // Flip status-bar and nav-bar icons to dark on light theme so they're
    // visible against the light background. SideEffect runs after every
    // successful composition, keeping it in sync if the Activity is recreated.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowInsetsControllerCompat(window, view)
            val isLight = theme == AppTheme.LIGHT
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    ExpressiveThemeBody(staticSchemeFor(theme), content)
}

/**
 * Nested theme that re-seeds the whole Material 3 [ColorScheme] from a single
 * key colour (e.g. a poster/backdrop's dominant colour) via MaterialKolor, then
 * crossfades to it. Wrap any subtree — library detail, now-playing bar — that
 * should tint itself to the current artwork. A null [seedColor] leaves the
 * inherited (app) theme untouched.
 */
@Composable
fun DynamicColorTheme(
    seedColor: Color?,
    content: @Composable () -> Unit
) {
    val theme = ThemeController.current()
    val isDark = theme != AppTheme.LIGHT
    // IMPORTANT: content() must stay at ONE composition position regardless of
    // seedColor. Branching (e.g. early-returning content() when the seed is null)
    // moves it in/out of the theme wrapper, which disposes + recreates the child
    // subtree; if that child reports the seed colour, you get an infinite
    // dispose→re-extract→reseed flicker loop. So always render through the body,
    // falling back to the inherited app scheme when there's no seed.
    val inherited = MaterialTheme.colorScheme
    val target = remember(seedColor, theme, inherited) {
        if (seedColor == null) {
            inherited
        } else {
            val scheme = dynamicColorScheme(
                seedColor = seedColor,
                isDark = isDark,
                style = paletteStyleFor(seedColor),
            )
            if (theme == AppTheme.AMOLED) scheme.pureBlack(true) else scheme
        }
    }
    ExpressiveThemeBody(target, content)
}

@Composable
private fun ExpressiveThemeBody(
    targetColorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    // Expressive motion — the source of the fluid ripples, container morphs and
    // transition timing that give the app its "smooth" feel.
    val motionScheme = MotionScheme.expressive()

    // Crossfade every colour slot instead of snapping, so theme changes and
    // art-seeded dynamic colours animate smoothly.
    val colorScheme = animateColorScheme(targetColorScheme, motionScheme.defaultEffectsSpec())

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme,
        shapes = ExpressiveShapes,
        typography = AppTypography
    ) {
        // Force the app font on bare Text() calls too. Without a style they use
        // LocalTextStyle (TextStyle.Default → FontFamily.Default), which Samsung's
        // FlipFont overrides with the system font.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppFontFamily)
        ) {
            content()
        }
    }
}

// Chroma-aware palette style (ported from ArchiveTune): near-greyscale seeds get
// a muted scheme, saturated seeds get a full tonal spot.
private fun paletteStyleFor(seedColor: Color): PaletteStyle {
    val chroma = seedColor.toHct().chroma
    return when {
        chroma < 4.0  -> PaletteStyle.Monochrome
        chroma < 12.0 -> PaletteStyle.Neutral
        else          -> PaletteStyle.TonalSpot
    }
}

// AMOLED: force pure-black background/surface over the seeded scheme.
private fun ColorScheme.pureBlack(apply: Boolean): ColorScheme =
    if (apply) copy(surface = Color.Black, background = Color.Black) else this

// Runs every ColorScheme slot through animateColorAsState so palette changes
// crossfade. Ported from ArchiveTune.
@Composable
private fun animateColorScheme(
    target: ColorScheme,
    spec: FiniteAnimationSpec<Color>,
): ColorScheme = ColorScheme(
    primary = animateColorAsState(target.primary, spec, label = "primary").value,
    onPrimary = animateColorAsState(target.onPrimary, spec, label = "onPrimary").value,
    primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "primaryContainer").value,
    onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer").value,
    inversePrimary = animateColorAsState(target.inversePrimary, spec, label = "inversePrimary").value,
    secondary = animateColorAsState(target.secondary, spec, label = "secondary").value,
    onSecondary = animateColorAsState(target.onSecondary, spec, label = "onSecondary").value,
    secondaryContainer = animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer").value,
    onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer").value,
    tertiary = animateColorAsState(target.tertiary, spec, label = "tertiary").value,
    onTertiary = animateColorAsState(target.onTertiary, spec, label = "onTertiary").value,
    tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer").value,
    onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer").value,
    background = animateColorAsState(target.background, spec, label = "background").value,
    onBackground = animateColorAsState(target.onBackground, spec, label = "onBackground").value,
    surface = animateColorAsState(target.surface, spec, label = "surface").value,
    onSurface = animateColorAsState(target.onSurface, spec, label = "onSurface").value,
    surfaceVariant = animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant").value,
    onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant").value,
    surfaceTint = animateColorAsState(target.surfaceTint, spec, label = "surfaceTint").value,
    inverseSurface = animateColorAsState(target.inverseSurface, spec, label = "inverseSurface").value,
    inverseOnSurface = animateColorAsState(target.inverseOnSurface, spec, label = "inverseOnSurface").value,
    error = animateColorAsState(target.error, spec, label = "error").value,
    onError = animateColorAsState(target.onError, spec, label = "onError").value,
    errorContainer = animateColorAsState(target.errorContainer, spec, label = "errorContainer").value,
    onErrorContainer = animateColorAsState(target.onErrorContainer, spec, label = "onErrorContainer").value,
    outline = animateColorAsState(target.outline, spec, label = "outline").value,
    outlineVariant = animateColorAsState(target.outlineVariant, spec, label = "outlineVariant").value,
    scrim = animateColorAsState(target.scrim, spec, label = "scrim").value,
    surfaceBright = animateColorAsState(target.surfaceBright, spec, label = "surfaceBright").value,
    surfaceDim = animateColorAsState(target.surfaceDim, spec, label = "surfaceDim").value,
    surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer").value,
    surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow").value,
    surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, label = "surfaceContainerLowest").value,
    surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh").value,
    surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest").value,
)
