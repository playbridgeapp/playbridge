package com.playbridge.sender.cast
import com.playbridge.sender.browser.*

import android.content.Context
import android.util.Log
import com.playbridge.sender.notifications.NotificationPermissionRecovery
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
    private var artworkJob: Job? = null
    private var metadataGeneration = 0L
    private var isActive = false
    private var disposed = false
    private var pageTitle: String? = null
    private var pageUrl: String? = null
    private var metadata: MediaSession.Metadata? = null
    private var artwork: android.graphics.Bitmap? = null
    private var features = MediaSession.Feature()
    private var position: MediaSession.PositionState? = null

    override fun onMediaActivated(mediaSessionController: MediaSession.Controller) {
        if (disposed) return
        isActive = true
        tabManager.markTabAsPlaying(tabId)
        MediaPlaybackService.activate(context, tabId)
        publishMetadata()
        MediaPlaybackService.sendSeekAvailability(tabId, features.contains(MediaSession.Feature.SEEK_TO))
        position?.let(::publishPosition)
        loadArtwork()

        controlJob?.cancel()
        controlJob = scope.launch {
            MediaControlChannel.actions.collect { command ->
                if (!isActive || !command.targets(tabId) || tabManager.sessions[tabId] !== engineSession) return@collect
                when (command.action) {
                    MediaControlChannel.Action.PLAY -> mediaSessionController.play()
                    MediaControlChannel.Action.PAUSE -> {
                        mediaSessionController.pause()
                        tabManager.pauseMedia(engineSession)
                    }
                    MediaControlChannel.Action.STOP -> mediaSessionController.stop()
                    MediaControlChannel.Action.SEEK_TO -> {
                        val duration = position?.duration ?: return@collect
                        if (!duration.isFinite() || duration <= 0 || !features.contains(MediaSession.Feature.SEEK_TO)) return@collect
                        val target = command.positionMs ?: return@collect
                        mediaSessionController.seekTo((target / 1000.0).coerceIn(0.0, duration), fast = false)
                    }
                }
            }
        }
    }

    override fun onMediaDeactivated() {
        if (disposed) return
        isActive = false
        tabManager.playingTabIds.remove(tabId)
        controlJob?.cancel()
        controlJob = null
        artworkJob?.cancel()
        artworkJob = null
        metadataGeneration++
        metadata = null
        artwork = null
        position = null
        features = MediaSession.Feature()
        MediaPlaybackService.deactivate(tabId)
    }

    /** Owner deactivation is handled by TabManager before it registers a replacement. */
    fun dispose() {
        if (disposed) return
        disposed = true
        isActive = false
        scope.cancel()
        controlJob = null
        artworkJob = null
        metadataGeneration++
        metadata = null
        artwork = null
        position = null
    }

    override fun onTitleChange(title: String) {
        if (disposed) return
        pageTitle = title
        publishMetadata()
    }

    override fun onLocationChange(url: String, hasUserGesture: Boolean) {
        if (disposed) return
        pageUrl = url
        publishMetadata()
    }

    override fun onMediaMetadataChanged(metadata: MediaSession.Metadata) {
        if (disposed) return
        this.metadata = metadata
        artwork = null
        publishMetadata()
        loadArtwork()
    }

    private fun publishMetadata() {
        if (!isActive) return
        val tab = Components.store.state.tabs.find { it.id == tabId } ?: return
        val text = websiteMediaNotificationText(pageTitle ?: tab.content.title, metadata?.title, pageUrl ?: tab.content.url)
        MediaPlaybackService.sendMetadataUpdate(tabId, text.title, text.subtitle, artwork)
    }

    private fun loadArtwork() {
        artworkJob?.cancel()
        val generation = ++metadataGeneration
        if (!isActive) return
        val loader = metadata?.getArtwork ?: return
        artworkJob = scope.launch {
            val bitmap = try {
                withTimeoutOrNull(5_000) { loader() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null // Optional artwork; never log a provider URL or exception message.
            }
            if (isActive && generation == metadataGeneration && tabManager.sessions[tabId] === engineSession) {
                artwork = bitmap
                publishMetadata()
            }
        }
    }

    override fun onMediaFeatureChanged(features: MediaSession.Feature) {
        if (disposed) return
        this.features = features
        if (isActive) MediaPlaybackService.sendSeekAvailability(tabId, features.contains(MediaSession.Feature.SEEK_TO))
    }

    override fun onMediaPositionStateChanged(positionState: MediaSession.PositionState) {
        if (disposed) return
        position = positionState
        if (isActive) publishPosition(positionState)
    }

    private fun publishPosition(positionState: MediaSession.PositionState) {
        MediaPlaybackService.sendPositionUpdate(tabId, positionState.duration, positionState.position, positionState.playbackRate)
    }

    override fun onMediaPlaybackStateChanged(playbackState: MediaSession.PlaybackState) {
        if (disposed) return
        when (playbackState) {
            MediaSession.PlaybackState.PLAYING -> {
                NotificationPermissionRecovery.onNotificationNeeded()
                tabManager.markTabAsPlaying(tabId)
                MediaPlaybackService.sendStateUpdate(context, tabId, true)
            }
            MediaSession.PlaybackState.PAUSED, MediaSession.PlaybackState.STOPPED -> {
                tabManager.playingTabIds.remove(tabId)
                MediaPlaybackService.sendStateUpdate(context, tabId, false)
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "MediaSessionObserver"
    }
}
