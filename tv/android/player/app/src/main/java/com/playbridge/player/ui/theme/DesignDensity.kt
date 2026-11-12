package com.playbridge.player.ui.theme

import android.util.DisplayMetrics

/** Width of the design canvas: the mockups are 1280 x 720 and "1 mockup px = 1 dp". */
const val DESIGN_WIDTH_DP = 1280f

/**
 * Density that makes a screen [displayWidthPx] pixels wide exactly [designWidthDp] dp wide, so one
 * design unit is the same fraction of the screen on every TV (1080p at 320 dpi, 720p, 4K...).
 * Null when the width is unknown.
 */
fun designDensity(displayWidthPx: Int, designWidthDp: Float = DESIGN_WIDTH_DP): Float? =
    if (displayWidthPx > 0 && designWidthDp > 0f) displayWidthPx / designWidthDp else null

/** [designDensity] for a display; the system density when the size is unknown. Uses the longer side (TVs are landscape). */
fun designDensity(metrics: DisplayMetrics): Float =
    designDensity(maxOf(metrics.widthPixels, metrics.heightPixels)) ?: metrics.density
