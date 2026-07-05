package com.playbridge.player.ui.theme

import android.content.Context
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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

private val DarkColorScheme = darkColorScheme(
    background = Surface,
    surface = Surface,
    surfaceVariant = SurfaceContainerHigh,
    primary = Primary,
    onPrimary = OnPrimary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant
)

private val AmoledColorScheme = darkColorScheme(
    background = AmoledSurface,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceContainerHigh,
    primary = Primary,
    onPrimary = OnPrimary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    background = LightSurface,
    surface = LightSurface,
    surfaceVariant = LightSurfaceContainerHigh,
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant
)

// Expressive rounded shape scale (mirrors the phone app). tv-material3 components
// that read the theme's shapes (Cards, Surfaces via ShapeDefaults) pick this up.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PlayBridgeTVTheme(
    theme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val colorScheme = when (theme) {
        AppTheme.DARK   -> DarkColorScheme
        AppTheme.AMOLED -> AmoledColorScheme
        AppTheme.LIGHT  -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = AppTypography,
        content = content
    )
}
