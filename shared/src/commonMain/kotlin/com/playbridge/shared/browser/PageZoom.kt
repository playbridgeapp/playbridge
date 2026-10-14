package com.playbridge.shared.browser

/** Zoom factors are per-event multipliers; coordinates are normalized TV cursor positions. */
fun pageZoomScript(factor: Float, x: Float = 0.5f, y: Float = 0.5f): String? {
    if (!factor.isFinite() || factor <= 0f || !x.isFinite() || !y.isFinite()) return null
    return "window.__pbvc&&window.__pbvc.zoomPage($factor,${x.coerceIn(0f, 1f)},${y.coerceIn(0f, 1f)})"
}

const val RESET_PAGE_ZOOM_SCRIPT = "window.__pbvc&&window.__pbvc.resetPageZoom()"
