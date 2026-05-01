package com.playbridge.sender.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * Bridges GeckoView MediaSession events to our MediaPlaybackService.
 */
class MediaSessionDelegate(
    private val context: Context
) : MediaSession.Delegate {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controlJob: Job? = null
    private var isActive = false

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onActivated")
        isActive = true
        MediaPlaybackService.start(context)
        
        controlJob?.cancel()
        controlJob = scope.launch {
            MediaControlChannel.actions.collect { action ->
                if (!isActive) return@collect
                Log.d(TAG, "Received action from channel: $action")
                when (action) {
                    MediaControlChannel.Action.PLAY -> mediaSession.play()
                    MediaControlChannel.Action.PAUSE -> {
                        mediaSession.pause()
                        // Fallback: pause all media elements via JS injection
                        session.loadUri("javascript:document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});void 0;")
                    }
                    MediaControlChannel.Action.STOP -> mediaSession.stop()
                }
            }
        }
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onDeactivated")
        isActive = false
        controlJob?.cancel()
        controlJob = null
        MediaPlaybackService.stop(context)
    }

    override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, metadata: MediaSession.Metadata) {
        Log.d(TAG, "Media metadata changed: ${metadata.title} - ${metadata.artist}")
        
        MediaPlaybackService.sendMetadataUpdate(
            context,
            title = metadata.title,
            artist = metadata.artist ?: context.getString(com.playbridge.sender.R.string.app_name)
        )
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPlay")
        MediaPlaybackService.sendStateUpdate(context, true)
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onPause")
        MediaPlaybackService.sendStateUpdate(context, false)
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        Log.d(TAG, "onStop")
        MediaPlaybackService.sendStateUpdate(context, false)
    }

    override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
        // e.g. can seek, can play/pause
    }

    override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, positionState: MediaSession.PositionState) {
        // Optional: update seek bar in notification
    }

    companion object {
        private const val TAG = "MediaSessionDelegate"
    }
}
