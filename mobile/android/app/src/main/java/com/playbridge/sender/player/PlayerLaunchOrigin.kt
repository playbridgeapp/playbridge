package com.playbridge.sender.player

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import com.playbridge.sender.R

/** The thumbnail the player was opened from, in [view]'s window, so the player grows out of it. */
class PlayerLaunchOrigin(private val view: View, private val bounds: Rect) {
    internal fun options(): Bundle = ActivityOptionsCompat.makeScaleUpAnimation(
        view.rootView, bounds.left, bounds.top, bounds.width(), bounds.height(),
    ).toBundle() ?: Bundle()

    companion object {
        /** Grows from [origin] when there is one; otherwise fades and scales in. */
        internal fun openOptions(context: Context, origin: PlayerLaunchOrigin?): Bundle? =
            origin?.options() ?: ActivityOptionsCompat.makeCustomAnimation(context, R.anim.player_open, 0).toBundle()
    }
}
