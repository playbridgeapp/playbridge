package com.playbridge.sender.player

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import androidx.core.app.ActivityOptionsCompat
import com.playbridge.sender.R

/**
 * The thumbnail the player was opened from, in screen pixels. The player animates the video
 * frame out of it itself: system scale-up launch animations are ignored on some phones.
 */
class PlayerLaunchOrigin(private val screenBounds: Rect) {
    internal fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_ORIGIN_BOUNDS, screenBounds)
    }

    companion object {
        internal const val EXTRA_ORIGIN_BOUNDS = "origin_bounds"

        /** A quick fade when the player grows from a thumbnail; otherwise a fade and scale. */
        internal fun openOptions(context: Context, origin: PlayerLaunchOrigin?): Bundle? =
            ActivityOptionsCompat.makeCustomAnimation(
                context,
                if (origin != null) R.anim.player_fade_in else R.anim.player_open,
                0,
            ).toBundle()
    }
}
