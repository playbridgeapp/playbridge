package com.playbridge.player.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Material 3 Expressive-style motion for the TV player.
 *
 * tv-material3 has no `MotionScheme` / `MaterialExpressiveTheme`, so we mirror the
 * expressive spring tokens by hand and feed them into our own transitions
 * (control-overlay fades/slides, pre-play countdown). Values match the phone's
 * `MotionScheme.expressive()`: a lightly under-damped spatial spring that gives
 * motion a "spirited" overshoot, and a critically-damped effects spring for
 * fades/alpha so opacity changes don't wobble.
 */
object TvExpressiveMotion {
    /** Position / size changes — slightly springy (overshoots). */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Fade / alpha / colour — critically damped, no overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 380f)
}
