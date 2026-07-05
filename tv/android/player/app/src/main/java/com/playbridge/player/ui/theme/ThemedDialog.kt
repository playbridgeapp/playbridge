package com.playbridge.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A [Dialog] that re-applies [PlayBridgeTVTheme] to its content.
 *
 * `androidx.compose.ui.window.Dialog` hosts its content in a separate window
 * whose subcomposition doesn't reliably inherit the tv-material3 theme, so a raw
 * Dialog renders with default colours / typography / shapes and looks off-theme.
 * Use this wrapper for any popup so it matches the rest of the app.
 */
@Composable
fun ThemedDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        PlayBridgeTVTheme(theme = AppTheme.fromPrefs(context)) {
            content()
        }
    }
}
