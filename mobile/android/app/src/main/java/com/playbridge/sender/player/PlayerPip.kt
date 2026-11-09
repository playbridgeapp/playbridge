package com.playbridge.sender.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import com.playbridge.sender.R

/** A button in the picture-in-picture window. */
internal enum class PipControl(val id: Int, val title: String, val icon: Int) {
    PLAY(1, "Play", R.drawable.ic_pip_play),
    PAUSE(2, "Pause", R.drawable.ic_pip_pause),
    NEXT(3, "Next", R.drawable.ic_pip_next),
    /** Closes the window and keeps the audio playing, controlled from the media notification. */
    BACKGROUND(4, "Play in background", R.drawable.ic_pip_background);

    companion object {
        fun of(id: Int): PipControl? = entries.firstOrNull { it.id == id }
    }
}

/** The window's buttons, at most [max] of them: play or pause, then next (when there is one), then background. */
internal fun pipControls(playing: Boolean, hasNext: Boolean, max: Int = 3): List<PipControl> =
    listOfNotNull(
        if (playing) PipControl.PAUSE else PipControl.PLAY,
        PipControl.NEXT.takeIf { hasNext },
        PipControl.BACKGROUND,
    ).take(max.coerceAtLeast(1))

/** Android rejects windows wider than 2.39:1 or taller than 1:2.39. */
private const val PIP_MAX_RATIO = 2.39

/** A width:height ratio in lowest terms. */
internal data class PipAspect(val width: Int, val height: Int) {
    fun toRational() = Rational(width, height)
}

/** The window's shape for a video of [width]×[height] (before [rotationDegrees]); 16:9 until the size is known. */
internal fun pipAspect(width: Int, height: Int, rotationDegrees: Int = 0, pixelRatio: Float = 1f): PipAspect {
    if (width <= 0 || height <= 0) return PipAspect(16, 9)
    val displayWidth = width * (pixelRatio.takeIf { it > 0f } ?: 1f).toDouble()
    val ratio = if (rotationDegrees % 180 != 0) height / displayWidth else displayWidth / height
    // Exact limits, so rounding can't push a very wide or tall video past them.
    if (ratio >= PIP_MAX_RATIO) return PipAspect(239, 100)
    if (ratio <= 1 / PIP_MAX_RATIO) return PipAspect(100, 239)
    // Square pixels keep the exact frame ratio; stretched ones are rounded to four decimals.
    if (pixelRatio == 1f || pixelRatio <= 0f) {
        return if (rotationDegrees % 180 != 0) lowestTerms(height, width) else lowestTerms(width, height)
    }
    return lowestTerms(Math.round(ratio * 10_000).toInt(), 10_000)
}

private fun lowestTerms(width: Int, height: Int): PipAspect {
    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val d = gcd(width, height).coerceAtLeast(1)
    return PipAspect(width / d, height / d)
}

/**
 * Where the video sits, as left, top, right, bottom, in a [screenWidth]×[screenHeight] window
 * when fitted, so the window animates out of it. Null before the window has a size.
 */
internal fun fittedVideoBounds(screenWidth: Int, screenHeight: Int, aspect: PipAspect): IntArray? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    val ratio = aspect.width.toDouble() / aspect.height
    return if (screenWidth.toDouble() / screenHeight > ratio) {
        val width = (screenHeight * ratio).toInt()
        val left = (screenWidth - width) / 2
        intArrayOf(left, 0, left + width, screenHeight)
    } else {
        val height = (screenWidth / ratio).toInt()
        val top = (screenHeight - height) / 2
        intArrayOf(0, top, screenWidth, top + height)
    }
}

/** True when this device has PiP and the user hasn't turned it off for PlayBridge. */
internal fun pipAllowed(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return true
    val mode = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
        }
    }.getOrDefault(AppOpsManager.MODE_ALLOWED)
    return mode == AppOpsManager.MODE_ALLOWED
}

internal const val ACTION_PIP_CONTROL = "com.playbridge.sender.player.PIP_CONTROL"
internal const val EXTRA_PIP_CONTROL = "pip_control"

/**
 * The window's settings: its shape, the buttons for [controls], and whether pressing Home
 * opens it by itself ([autoEnter], Android 12+; older versions use onUserLeaveHint).
 */
internal fun pipParams(
    activity: Activity,
    aspect: PipAspect,
    controls: List<PipControl>,
    autoEnter: Boolean,
    title: String?,
): PictureInPictureParams {
    val actions = controls.map { control ->
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(activity.packageName).putExtra(EXTRA_PIP_CONTROL, control.id)
        val pending = PendingIntent.getBroadcast(
            activity, control.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        RemoteAction(Icon.createWithResource(activity, control.icon), control.title, control.title, pending)
    }
    val decor = activity.window?.decorView
    return PictureInPictureParams.Builder()
        .setAspectRatio(aspect.toRational())
        .setActions(actions)
        .apply {
            fittedVideoBounds(decor?.width ?: 0, decor?.height ?: 0, aspect)
                ?.let { (left, top, right, bottom) -> setSourceRectHint(Rect(left, top, right, bottom)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(autoEnter)
                // Video re-lays out on resize; a crossfade looks better than stretching.
                setSeamlessResizeEnabled(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !title.isNullOrBlank()) setTitle(title.take(80))
        }
        .build()
}
