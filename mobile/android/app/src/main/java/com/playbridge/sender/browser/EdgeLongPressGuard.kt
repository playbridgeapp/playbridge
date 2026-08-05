package com.playbridge.sender.browser

/**
 * Records the most recent touch-down on the browser viewport so a Gecko long-press
 * context menu can be suppressed when the press began inside a system gesture zone
 * (edge back / home swipe areas).
 *
 * During an edge swipe the initial touch still reaches GeckoView, and Gecko's
 * ~500 ms long-press timer can win the race against the system stealing the touch
 * stream for the back gesture — surfacing the link-options sheet mid-swipe.
 * Touches that begin inside the system gesture insets are gesture candidates, not
 * genuine long-presses, so they must not open link options. On devices without
 * gesture navigation the insets are zero and nothing is suppressed.
 */
object EdgeLongPressGuard {
    /** A recorded down older than this can no longer be the long-press trigger. */
    private const val MAX_DOWN_AGE_MS = 2_000L

    @Volatile private var downX = -1f
    @Volatile private var downY = -1f
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0
    @Volatile private var downAtMs = 0L

    fun recordDown(x: Float, y: Float, widthPx: Int, heightPx: Int) {
        downX = x
        downY = y
        viewWidth = widthPx
        viewHeight = heightPx
        downAtMs = System.currentTimeMillis()
    }

    fun shouldSuppressLongPress(
        leftInset: Int,
        topInset: Int,
        rightInset: Int,
        bottomInset: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val at = downAtMs
        if (at == 0L || nowMs - at > MAX_DOWN_AGE_MS) return false
        return isWithinGestureZone(
            downX, downY, viewWidth, viewHeight,
            leftInset, topInset, rightInset, bottomInset,
        )
    }

    internal fun resetForTesting() {
        downX = -1f
        downY = -1f
        viewWidth = 0
        viewHeight = 0
        downAtMs = 0L
    }
}

/** True when the down point lies inside any nonzero system gesture inset edge zone. */
internal fun isWithinGestureZone(
    downX: Float,
    downY: Float,
    viewWidth: Int,
    viewHeight: Int,
    leftInset: Int,
    topInset: Int,
    rightInset: Int,
    bottomInset: Int,
): Boolean =
    (leftInset > 0 && downX <= leftInset) ||
        (rightInset > 0 && viewWidth > 0 && downX >= viewWidth - rightInset) ||
        (topInset > 0 && downY <= topInset) ||
        (bottomInset > 0 && viewHeight > 0 && downY >= viewHeight - bottomInset)
