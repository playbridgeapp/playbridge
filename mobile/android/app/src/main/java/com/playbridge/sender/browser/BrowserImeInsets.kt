package com.playbridge.sender.browser

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.max

/**
 * How far the browser chrome and the engine sit above the bottom of the screen.
 *
 * [toolbarBottomPx] is the padding under the bottom toolbar buttons. With the toolbar
 * showing, that padding is the larger of the keyboard and the navigation bar, so the
 * buttons sit on the keyboard and the scaffold content (the engine) shortens with them.
 * [engineBottomPx] is extra padding on the engine itself. It is used when the toolbar
 * is hidden (chrome-hidden browsing, a Bridged App, media fullscreen). A closed keyboard
 * leaves that value at 0 so the page stays edge-to-edge.
 */
internal data class BrowserBottomSpace(
    val toolbarBottomPx: Int,
    val engineBottomPx: Int,
)

internal fun browserBottomSpace(
    imeBottomPx: Int,
    navigationBarBottomPx: Int,
    bottomToolbarVisible: Boolean,
): BrowserBottomSpace {
    val ime = imeBottomPx.coerceAtLeast(0)
    val navigation = navigationBarBottomPx.coerceAtLeast(0)
    return if (bottomToolbarVisible) {
        BrowserBottomSpace(toolbarBottomPx = max(ime, navigation), engineBottomPx = 0)
    } else {
        BrowserBottomSpace(toolbarBottomPx = 0, engineBottomPx = ime)
    }
}

/** Shrinks the engine by the keyboard only while the bottom toolbar is not already doing that. */
@Composable
internal fun Modifier.browserEngineImePadding(bottomToolbarVisible: Boolean): Modifier =
    if (bottomToolbarVisible) this else windowInsetsPadding(WindowInsets.ime)
