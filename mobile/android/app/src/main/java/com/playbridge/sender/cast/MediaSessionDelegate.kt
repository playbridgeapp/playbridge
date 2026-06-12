package com.playbridge.sender.cast
import com.playbridge.sender.browser.*

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.mediasession.MediaSession

/**
 * Bridges media session events from an [EngineSession] to MediaPlaybackService
 * and TabManager's playing-tab state.
 *
 * Registered once per engine session by TabManager's store mirror — so it
 * covers EVERY tab, including background ones (the old GeckoView
 * `MediaSession.Delegate`, set via reflection, only covered the selected tab
 * and was detached on tab switch).
 */
class MediaSessionObserver(
    private val context: Context,
    private val tabId: String,
    private val tabManager: TabManager,
    private val engineSession: EngineSession,
) : EngineSession.Observer {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controlJob: Job? = null
    private var isActive = false

    override fun onMediaActivated(mediaSessionController: MediaSession.Controller) {
        Log.d(TAG, "onMediaActivated (tabId=$tabId)")
        isActive = true
        tabManager.markTabAsPlaying(tabId)
        MediaPlaybackService.start(context)

        controlJob?.cancel()
        controlJob = scope.launch {
            MediaControlChannel.actions.collect { action ->
                if (!isActive) return@collect
                Log.d(TAG, "Received action from channel: $action")
                when (action) {
                    MediaControlChannel.Action.PLAY -> mediaSessionController.play()
                    MediaControlChannel.Action.PAUSE -> {
                        mediaSessionController.pause()
                        // Fallback: pause all media elements via JS injection
                        tabManager.pauseMedia(engineSession)
                    }
                    MediaControlChannel.Action.STOP -> mediaSessionController.stop()
                }
            }
        }
    }

    override fun onMediaDeactivated() {
        Log.d(TAG, "onMediaDeactivated (tabId=$tabId)")
        isActive = false
        tabManager.playingTabIds.remove(tabId)
        controlJob?.cancel()
        controlJob = null
        MediaPlaybackService.stop(context)
    }

    override fun onMediaMetadataChanged(metadata: MediaSession.Metadata) {
        Log.d(TAG, "Media metadata changed: ${metadata.title} - ${metadata.artist}")
        MediaPlaybackService.sendMetadataUpdate(
            context,
            title = metadata.title,
            artist = metadata.artist ?: context.getString(com.playbridge.sender.R.string.app_name)
        )
    }

    override fun onMediaPlaybackStateChanged(playbackState: MediaSession.PlaybackState) {
        Log.d(TAG, "onMediaPlaybackStateChanged (tabId=$tabId): $playbackState")
        when (playbackState) {
            MediaSession.PlaybackState.PLAYING -> {
                tabManager.markTabAsPlaying(tabId)
                MediaPlaybackService.sendStateUpdate(context, true)
            }
            MediaSession.PlaybackState.PAUSED,
            MediaSession.PlaybackState.STOPPED -> {
                tabManager.playingTabIds.remove(tabId)
                MediaPlaybackService.sendStateUpdate(context, false)
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "MediaSessionObserver"
    }
}
