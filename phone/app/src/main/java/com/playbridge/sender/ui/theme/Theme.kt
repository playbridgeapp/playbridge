package com.playbridge.sender.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
    surfaceVariant = SurfaceContainerLow,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    primary = Primary,
    onPrimary = OnPrimary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outlineVariant = OutlineVariant
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
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outlineVariant = OutlineVariant
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
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outlineVariant = LightOutlineVariant
)

@Composable
fun PlayBridgeTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val theme = remember { AppTheme.fromPrefs(context) }
    val colorScheme = when (theme) {
        AppTheme.DARK   -> DarkColorScheme
        AppTheme.AMOLED -> AmoledColorScheme
        AppTheme.LIGHT  -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
