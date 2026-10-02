package com.playbridge.sender.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.playbridge.sender.browser.LinkedPageCastItem
import com.playbridge.sender.browser.LinkedPageCastOpenRequest
import com.playbridge.sender.browser.PageCastConsentStore
import com.playbridge.sender.browser.linkedSessionExpired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One bounded website queue, attached to the existing phone player. No media is persisted. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PagePlayerSession {
    private val _waitingForNext = MutableStateFlow(false)
    val waitingForNext: StateFlow<Boolean> = _waitingForNext
    private class Session(val request: LinkedPageCastOpenRequest, val emit: (String, JSONObject) -> Unit) {
        val items = request.items.toMutableList()
        var player: ExoPlayer? = null
        var context: Context? = null
        val createdAtMillis = System.currentTimeMillis()
        var lastActivityAtMillis = createdAtMillis
        var lastNeedAtMillis = 0L
        var need: String? = null
        var lastSupply: String? = null
        var endOfList = false
        var ready = false
        var waitingAtEnd = false
        var onQueueFinished: (() -> Unit)? = null
        var listener: Player.Listener? = null
        var lastProgress: JSONObject? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var active: Session? = null
    private val ticker = object : Runnable {
        override fun run() {
            val session = active ?: return
            if (session.context?.let { !PageCastConsentStore.isApproved(it, session.request.origin) } == true ||
                linkedSessionExpired(System.currentTimeMillis(), session.lastActivityAtMillis, session.createdAtMillis)) {
                flush(session.request.sessionId)
                session.emit("ended", JSONObject().put("reason", "session_ended"))
                unlink(session.request.sessionId)
                return
            }
            report(session)
            demand(session)
            handler.postDelayed(this, 1000)
        }
    }

    fun open(request: LinkedPageCastOpenRequest, emit: (String, JSONObject) -> Unit) {
        active?.let { close(it.request.sessionId) }
        active = Session(request, emit)
    }

    fun attach(id: String, context: Context, player: ExoPlayer, onQueueFinished: () -> Unit): Boolean {
        val session = active?.takeIf { it.request.sessionId == id } ?: return false
        session.onQueueFinished = onQueueFinished
        session.player = player; session.context = context.applicationContext
        player.setMediaSources(session.items.map { source(context, it) }, session.request.startIndex,
            session.items[session.request.startIndex].payload.start_position_ms ?: 0)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (active !== session) return
                report(session)
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (active !== session) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    session.lastProgress?.let { previous ->
                        val finished = JSONObject(previous.toString()).put("state", "ended")
                        session.emit("statechange", finished)
                    }
                }
                report(session)
                demand(session)
            }
        }
        session.listener = listener; player.addListener(listener)
        handler.removeCallbacks(ticker); handler.postDelayed(ticker, 250)
        return true
    }

    fun supply(id: String, requestId: String, items: List<LinkedPageCastItem>, endOfList: Boolean): Boolean {
        val session = active?.takeIf { it.request.sessionId == id } ?: return false
        if (requestId == session.lastSupply) return true
        if (requestId != session.need || items.size > 1 || session.items.size + items.size > 200 ||
            items.map { it.id }.any { value -> session.items.any { it.id == value } }) return false
        val context = session.context ?: return false
        val player = session.player ?: return false
        if (items.isNotEmpty()) {
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val nextIndex = session.items.size
            session.items += items
            player.addMediaSources(items.map { source(context, it) })
            if (wasEnded) { player.seekTo(nextIndex, 0); player.prepare(); player.play() }
        }
        session.lastSupply = requestId; session.need = null; session.endOfList = endOfList
        if (session.waitingAtEnd && items.isEmpty() && endOfList) session.onQueueFinished?.invoke()
        if (items.isNotEmpty()) { session.waitingAtEnd = false; _waitingForNext.value = false }
        return true
    }

    fun ready(id: String) {
        active?.takeIf { it.request.sessionId == id }?.let { it.ready = true; it.lastActivityAtMillis = System.currentTimeMillis(); demand(it) }
    }

    /** Keep the existing player visible while the web app resolves the requested episode. */
    fun waitForNext(id: String): Boolean {
        val session = active?.takeIf { it.request.sessionId == id } ?: return false
        if (session.endOfList) return false
        session.waitingAtEnd = true
        _waitingForNext.value = true
        demand(session)
        return true
    }

    fun flush(id: String) {
        active?.takeIf { it.request.sessionId == id }?.let { report(it, "stopped") }
    }

    fun close(id: String) {
        val session = active?.takeIf { it.request.sessionId == id } ?: return
        report(session, "stopped")
        session.emit("ended", JSONObject().put("reason", "player_closed"))
        unlink(id)
    }

    fun unlink(id: String) {
        val session = active?.takeIf { it.request.sessionId == id } ?: return
        session.listener?.let { session.player?.removeListener(it) }
        handler.removeCallbacks(ticker)
        _waitingForNext.value = false
        active = null
    }

    private fun report(session: Session, state: String? = null) {
        val player = session.player ?: return
        val value = JSONObject().put("currentIndex", player.currentMediaItemIndex)
            .put("items", JSONArray(session.items.map { JSONObject().put("id", it.id) }))
            .put("positionMs", player.currentPosition.coerceAtLeast(0))
            .put("durationMs", player.duration.coerceAtLeast(0))
            .put("state", if (player.playbackState == Player.STATE_ENDED) "ended" else state ?: if (player.isPlaying) "playing" else "paused")
        session.lastProgress = value
        session.emit("statechange", value)
    }

    private fun demand(session: Session) {
        val player = session.player ?: return
        if (!session.ready || session.endOfList || session.items.size - player.currentMediaItemIndex > 2) return
        val now = System.currentTimeMillis()
        if (session.need != null && now - session.lastNeedAtMillis < 10_000) return
        val id = session.need ?: UUID.randomUUID().toString()
        session.need = id; session.lastNeedAtMillis = now
        session.emit("needitems", JSONObject().put("requestId", id).put("count", 1))
    }

    private fun source(context: Context, item: LinkedPageCastItem): MediaSource {
        val payload = item.payload
        val subtitles = payload.subtitles.map { SubtitleTrack(it) } + payload.subtitle_resources.map {
            SubtitleTrack(it.url, it.label, it.language)
        }
        val media = PlayerActivity.buildMediaItem(payload.url, payload.content_type, payload.title, subtitles)
        // Subtitle credentials stay on their own resource; video headers apply to the video and its segments.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000).setReadTimeoutMs(30_000)
        val sources = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            val subtitle = payload.subtitle_resources.firstOrNull { it.url == spec.uri.toString() }
            val plainSubtitle = payload.subtitles.any { it == spec.uri.toString() }
            spec.withRequestHeaders(subtitle?.headers ?: if (plainSubtitle) emptyMap() else payload.headers)
        }
        return DefaultMediaSourceFactory(sources).createMediaSource(media)
    }
}
