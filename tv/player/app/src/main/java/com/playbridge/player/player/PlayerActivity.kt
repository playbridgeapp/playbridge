package com.playbridge.player.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

abstract class PlayerActivity : ComponentActivity() {

    // Common abstract properties and functions for player controls
    abstract fun play()
    abstract fun pause()
    abstract fun isPlaying(): Boolean
    abstract fun getMediaDuration(): Long
    abstract fun getCurrentPosition(): Long
    abstract fun seekTo(position: Long)
    abstract fun getVideoSurfaceView(): android.view.SurfaceView?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ensure only one player activity is alive at a time so that pressing back always
        // returns to the home/library screen rather than a previously-used player.
        current?.get()?.let { prev ->
            if (prev !== this && !prev.isFinishing) {
                prev.finish()
            }
        }
        current = java.lang.ref.WeakReference(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}