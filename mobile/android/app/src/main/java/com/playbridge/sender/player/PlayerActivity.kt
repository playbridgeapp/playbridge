package com.playbridge.sender.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.PictureInPicture
import android.graphics.Bitmap
import android.view.PixelCopy
import android.os.Handler
import android.os.HandlerThread
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.widget.Toast
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.playbridge.sender.R
import com.playbridge.sender.cast.openInExternalPlayer
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A single sidecar subtitle track to attach to playback.
 */
data class SubtitleTrack(
    val url: String,
    val label: String? = null,
    val language: String? = null
)

/**
 * Full-screen, landscape in-app video player backed by Media3 ExoPlayer.
 *
 * Launched via [PlayerLauncher.start]. Handles HLS / DASH / progressive streams
 * (auto-detected by [DefaultMediaSourceFactory]), forwards request headers for
 * stream URLs that need them, and attaches any sidecar subtitle tracks.
 *
 * Used by the library "Watch on Phone" action and the cast sheet's
 * "Play on phone" menu item — neither casts to the TV.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var isBackgroundModeEnabled = false
    private val isInPipModeState = mutableStateOf(false)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val playlistUrls = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URLS)
        val isPlaylist = !playlistUrls.isNullOrEmpty()

        val url = intent.getStringExtra(EXTRA_URL)
        if (!isPlaylist && url.isNullOrBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE)

        @Suppress("UNCHECKED_CAST")
        val headers: Map<String, String> = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
            ?: emptyMap()

        val subUrls = intent.getStringArrayListExtra(EXTRA_SUB_URLS).orEmpty()
        val subLabels = intent.getStringArrayListExtra(EXTRA_SUB_LABELS).orEmpty()
        val subLangs = intent.getStringArrayListExtra(EXTRA_SUB_LANGS).orEmpty()
        val subtitles = subUrls.mapIndexed { i, u ->
            SubtitleTrack(
                url = u,
                label = subLabels.getOrNull(i)?.takeIf { it.isNotBlank() },
                language = subLangs.getOrNull(i)?.takeIf { it.isNotBlank() }
            )
        }

        // Fullscreen, landscape, immersive — and keep the screen awake while playing.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val exo = buildPlayer(this, headers)
        this.player = exo

        val initialTitle: String?
        if (isPlaylist) {
            // Series auto-play: load every episode so ExoPlayer advances on its own.
            val titles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES).orEmpty()
            val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            val items = playlistUrls!!.mapIndexed { i, u ->
                buildMediaItem(u, null, titles.getOrNull(i), emptyList())
            }
            val safeStart = startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
            exo.setMediaItems(items, safeStart, 0L)
            initialTitle = titles.getOrNull(safeStart)
        } else {
            exo.setMediaItem(buildMediaItem(url!!, contentType, title, subtitles))
            initialTitle = title
        }
        exo.prepare()
        exo.playWhenReady = true

        setContent {
            PlayBridgeTheme {
                val isInPip by isInPipModeState
                PlayerScreen(
                    player = exo,
                    title = initialTitle,
                    externalHeaders = headers,
                    externalContentType = contentType,
                    onClose = { finish() },
                    onEnded = { finish() },
                    isInPip = isInPip,
                    onBackgroundModeChanged = { enabled -> this.isBackgroundModeEnabled = enabled }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause when leaving the foreground, UNLESS background mode is enabled.
        if (!isBackgroundModeEnabled) {
            player?.playWhenReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LABELS = "sub_labels"
        const val EXTRA_SUB_LANGS = "sub_langs"
        const val EXTRA_PLAYLIST_URLS = "playlist_urls"
        const val EXTRA_PLAYLIST_TITLES = "playlist_titles"
        const val EXTRA_START_INDEX = "start_index"

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun buildPlayer(context: Context, headers: Map<String, String>): ExoPlayer {
            // Hub/proxy stream URLs can be slow to respond (server-side resolve), so the
            // default 8s timeout is too aggressive when switching episodes.
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
            if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 300_000,
                    /* maxBufferMs = */ 300_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000
                )
                .setBackBuffer(
                    /* backBufferDurationMs = */ 60_000,
                    /* retainBackBufferFromKeyframe = */ true
                )
                .setTargetBufferBytes(128 * 1024 * 1024)
                .build()

            return ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build()
        }

        private fun buildMediaItem(
            url: String,
            contentType: String?,
            title: String?,
            subtitles: List<SubtitleTrack>
        ): MediaItem {
            val builder = MediaItem.Builder().setUri(url)

            // Hint the container so DefaultMediaSourceFactory picks HLS/DASH even when
            // the URL has no recognizable extension.
            val mime = when {
                url.contains(".m3u8", ignoreCase = true) ||
                    url.startsWith("data:application/x-mpegurl", ignoreCase = true) ||
                    contentType?.contains("mpegurl", ignoreCase = true) == true -> MimeTypes.APPLICATION_M3U8

                url.contains(".mpd", ignoreCase = true) ||
                    contentType?.contains("dash", ignoreCase = true) == true -> MimeTypes.APPLICATION_MPD

                else -> null
            }
            if (mime != null) builder.setMimeType(mime)

            if (!title.isNullOrBlank()) {
                builder.setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
                )
            }

            if (subtitles.isNotEmpty()) {
                builder.setSubtitleConfigurations(
                    subtitles.mapIndexed { index, sub ->
                        val subMime = if (sub.url.endsWith(".srt", ignoreCase = true))
                            MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                            .setMimeType(subMime)
                            .setLanguage(sub.language)
                            .setLabel(sub.label)
                            .apply { if (index == 0) setSelectionFlags(C.SELECTION_FLAG_DEFAULT) }
                            .build()
                    }
                )
            }

            return builder.build()
        }
    }
}

/**
 * Launches [PlayerActivity] for the given stream. Safe to call from any [Context];
 * [headers] are only attached when non-empty (resolved addon URLs usually carry none).
 */
object PlayerLauncher {
    fun start(
        context: Context,
        url: String,
        title: String? = null,
        contentType: String? = null,
        headers: Map<String, String>? = null,
        subtitles: List<SubtitleTrack> = emptyList()
    ) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, contentType)
            if (!headers.isNullOrEmpty()) putExtra(PlayerActivity.EXTRA_HEADERS, HashMap(headers))
            if (subtitles.isNotEmpty()) {
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_URLS, ArrayList(subtitles.map { it.url }))
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_LABELS, ArrayList(subtitles.map { it.label ?: "" }))
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_LANGS, ArrayList(subtitles.map { it.language ?: "" }))
            }
        }
        context.startActivity(intent)
    }

    /**
     * Launches [PlayerActivity] with a playlist of episodes; ExoPlayer auto-advances
     * through them and the player exits when the last one ends. Used for series.
     */
    fun startPlaylist(
        context: Context,
        urls: List<String>,
        titles: List<String>,
        startIndex: Int
    ) {
        if (urls.isEmpty()) return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URLS, ArrayList(urls))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(titles))
            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        }
        context.startActivity(intent)
    }
}

/** Which vertical/horizontal drag the user is currently performing. */
private enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS }

/** Which track type the selection sheet is showing. */
private enum class TrackSheetMode { AUDIO, SUBTITLE }

/** A full-width horizontal swipe scrubs this many milliseconds. */
private const val SEEK_RANGE_MS = 120_000L

@Composable
private fun PlayerScreen(
    player: ExoPlayer,
    title: String?,
    externalHeaders: Map<String, String>,
    externalContentType: String?,
    onClose: () -> Unit,
    onEnded: () -> Unit,
    isInPip: Boolean,
    onBackgroundModeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Playback state
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }

    // Controls / scrubbing
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var trackSheet by remember { mutableStateOf<TrackSheetMode?>(null) }
    var currentTracks by remember { mutableStateOf(player.currentTracks) }
    var currentTitle by remember { mutableStateOf(title) }
    var aspectLabel by remember { mutableStateOf<String?>(null) }
    var showStats by remember { mutableStateOf(false) }

    val activeVideoFormat = remember(currentTracks) {
        currentTracks.groups.find { it.type == C.TRACK_TYPE_VIDEO }?.let { group ->
            (0 until group.length).find { group.isTrackSelected(it) }?.let { index ->
                group.getTrackFormat(index)
            }
        }
    }

    val activeAudioFormat = remember(currentTracks) {
        currentTracks.groups.find { it.type == C.TRACK_TYPE_AUDIO }?.let { group ->
            (0 until group.length).find { group.isTrackSelected(it) }?.let { index ->
                group.getTrackFormat(index)
            }
        }
    }

    // New states
    var isBackgroundModeEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(player.repeatMode) }
    var abStartMs by remember { mutableStateOf<Long?>(null) }
    var abEndMs by remember { mutableStateOf<Long?>(null) }
    var isPanZoomEnabled by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Sync background playback toggle to activity
    LaunchedEffect(isBackgroundModeEnabled) {
        onBackgroundModeChanged(isBackgroundModeEnabled)
    }

    // Precision AB Loop segment repeat
    LaunchedEffect(abStartMs, abEndMs) {
        if (abStartMs != null && abEndMs != null) {
            while (true) {
                if (player.currentPosition >= abEndMs!!) {
                    player.seekTo(abStartMs!!)
                }
                delay(100)
            }
        }
    }

    // Force hide controls in PiP mode
    LaunchedEffect(isInPip) {
        if (isInPip) {
            controlsVisible = false
        }
    }

    // Playlist (series) state, derived from the player's timeline.
    val episodeTitles = remember {
        (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "Episode ${it + 1}"
        }
    }
    val isPlaylist = episodeTitles.size > 1
    var currentIndex by remember { mutableIntStateOf(player.currentMediaItemIndex) }
    var showEpisodes by remember { mutableStateOf(false) }

    // Gesture HUD state
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var volume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember { mutableFloatStateOf(initialBrightness(window, context)) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var doubleTapSide by remember { mutableIntStateOf(0) }    // -1 = rewind, +1 = forward, 0 = none
    var doubleTapSeconds by remember { mutableIntStateOf(0) } // cumulative skip amount shown in the indicator

    fun markInteraction() { interactionTick++ }

    fun seekBy(deltaMs: Long) {
        val d = player.duration.coerceAtLeast(0)
        val target = (player.currentPosition + deltaMs).coerceIn(0, if (d > 0) d else Long.MAX_VALUE)
        player.seekTo(target)
        positionMs = target
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        markInteraction()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                currentTracks = tracks
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Title + index follow the current playlist item (e.g. next episode on auto-advance).
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: title
                currentIndex = player.currentMediaItemIndex

                // On media item transition (seeking to previous/next or auto-advance), certain hardware
                // decoders fail to hand off the SurfaceView correctly, causing a black screen.
                // Re-preparing the player forces codec recreation and clean rendering.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    player.prepare()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    errorMessage = null
                    val d = player.duration
                    if (d > 0) durationMs = d
                }
                // End of all content (playlist exhausted, or a single item finished): exit.
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "Playback error"
                isBuffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll position/buffer while not actively scrubbing/seeking.
    LaunchedEffect(Unit) {
        while (true) {
            if (!isScrubbing && dragMode != DragMode.SEEK) {
                positionMs = player.currentPosition
                bufferedMs = player.bufferedPosition
                val d = player.duration
                if (d > 0) durationMs = d
            }
            delay(400)
        }
    }

    // Auto-hide controls a few seconds after the last interaction while playing.
    LaunchedEffect(controlsVisible, isPlaying, interactionTick, dragMode) {
        if (controlsVisible && isPlaying && dragMode == DragMode.NONE) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Clear the double-tap skip indicator shortly after the last tap.
    LaunchedEffect(doubleTapSide, doubleTapSeconds) {
        if (doubleTapSide != 0) {
            delay(800)
            doubleTapSide = 0
            doubleTapSeconds = 0
        }
    }

    // Auto-hide the unlock affordance shown while locked.
    LaunchedEffect(showUnlock) {
        if (showUnlock) {
            delay(2500)
            showUnlock = false
        }
    }

    // Auto-clear the aspect ratio HUD notification
    LaunchedEffect(aspectLabel) {
        if (aspectLabel != null) {
            delay(1500)
            aspectLabel = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(locked, isInPip) {
                    detectTapGestures(
                        onTap = {
                            if (isInPip) return@detectTapGestures
                            if (locked) {
                                showUnlock = true
                            } else {
                                controlsVisible = !controlsVisible
                                if (controlsVisible) markInteraction()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (isInPip) return@detectTapGestures
                            if (!locked) {
                                if (offset.x < size.width / 2f) {
                                    doubleTapSeconds = if (doubleTapSide == -1) doubleTapSeconds + 10 else 10
                                    doubleTapSide = -1
                                    seekBy(-10_000)
                                } else {
                                    doubleTapSeconds = if (doubleTapSide == 1) doubleTapSeconds + 10 else 10
                                    doubleTapSide = 1
                                    seekBy(10_000)
                                }
                            }
                        }
                    )
                }
                .pointerInput(locked, isInPip) {
                    val slop = viewConfiguration.touchSlop
                    var startX = 0f
                    var accDx = 0f
                    var accDy = 0f
                    var seekStartMs = 0L
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (isInPip) return@detectDragGestures
                            startX = offset.x
                            accDx = 0f; accDy = 0f
                            dragMode = DragMode.NONE
                            seekStartMs = player.currentPosition
                            // Re-sync in case volume was changed by hardware keys since last drag.
                            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        },
                        onDragEnd = {
                            if (dragMode == DragMode.SEEK) {
                                player.seekTo(seekPreviewMs)
                                positionMs = seekPreviewMs
                            }
                            dragMode = DragMode.NONE
                        },
                        onDragCancel = { dragMode = DragMode.NONE },
                        onDrag = onDrag@{ change, amount ->
                            if (locked) return@onDrag
                            change.consume()
                            accDx += amount.x
                            accDy += amount.y
                            if (dragMode == DragMode.NONE) {
                                if (abs(accDx) > slop && abs(accDx) > abs(accDy)) {
                                    dragMode = DragMode.SEEK
                                } else if (abs(accDy) > slop) {
                                    dragMode = if (startX < size.width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                                }
                            }
                            when (dragMode) {
                                DragMode.SEEK -> {
                                    val d = durationMs
                                    if (d > 0) {
                                        val delta = ((accDx / size.width) * SEEK_RANGE_MS).toLong()
                                        seekPreviewMs = (seekStartMs + delta).coerceIn(0, d)
                                        seekDeltaMs = seekPreviewMs - seekStartMs
                                    }
                                }
                                DragMode.VOLUME -> {
                                    val prevVolume = volume
                                    val newVolume = (volume - (amount.y / size.height) * maxVolume)
                                        .coerceIn(0f, maxVolume.toFloat())
                                    volume = newVolume
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC, volume.roundToInt(), 0
                                    )
                                    if ((newVolume == 0f && prevVolume > 0f) || (newVolume == maxVolume.toFloat() && prevVolume < maxVolume.toFloat())) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                }
                                DragMode.BRIGHTNESS -> {
                                    val prevBrightness = brightness
                                    val newBrightness = (brightness - amount.y / size.height).coerceIn(0.01f, 1f)
                                    brightness = newBrightness
                                    window?.let {
                                        val lp = it.attributes
                                        lp.screenBrightness = brightness
                                        it.attributes = lp
                                    }
                                    if ((newBrightness == 0.01f && prevBrightness > 0.01f) || (newBrightness == 1f && prevBrightness < 1f)) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                }
                                DragMode.NONE -> {}
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isPanZoomEnabled) {
                        if (isPanZoomEnabled) {
                            detectTransformGestures { centroid, pan, zoom, rotation ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offset += pan
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        // Inflated from XML so it uses a SurfaceView surface (see player_view.xml).
                        (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                            this.player = player
                            this.resizeMode = resizeMode

                            // Remove ugly dark background boxes from subtitles and use a high-contrast text outline
                            subtitleView?.apply {
                                setApplyEmbeddedStyles(false)
                                setApplyEmbeddedFontSizes(false)
                                val customStyle = androidx.media3.ui.CaptionStyleCompat(
                                    android.graphics.Color.WHITE,
                                    android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT,
                                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    android.graphics.Color.BLACK,
                                    android.graphics.Typeface.DEFAULT_BOLD
                                )
                                setStyle(customStyle)
                                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                            }
                        }
                    },
                    update = { it.resizeMode = resizeMode },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }

            // Buffering spinner (suppressed while a gesture HUD is showing).
            if (isBuffering && dragMode == DragMode.NONE && errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // Double-tap skip indicator (cumulative: 10s, 20s, …).
            if (doubleTapSide != 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .align(if (doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (doubleTapSide < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${doubleTapSeconds}s",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Gesture HUDs.
            when (dragMode) {
                DragMode.VOLUME -> GestureHud(
                    icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White) },
                    fraction = volume / maxVolume,
                    label = "${(volume / maxVolume * 100).roundToInt()}%",
                    alignment = Alignment.CenterStart
                )
                DragMode.BRIGHTNESS -> GestureHud(
                    icon = { Icon(Icons.Filled.BrightnessHigh, null, tint = Color.White) },
                    fraction = brightness,
                    label = "${(brightness * 100).roundToInt()}%",
                    alignment = Alignment.CenterEnd
                )
                DragMode.SEEK -> SeekHud(seekDeltaMs, seekPreviewMs, durationMs)
                DragMode.NONE -> {}
            }

            // Aspect ratio confirmation HUD
            AnimatedVisibility(
                visible = aspectLabel != null,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = aspectLabel ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Stats overlay
            AnimatedVisibility(
                visible = showStats && errorMessage == null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StatsOverlay(
                    player = player,
                    activeVideoFormat = activeVideoFormat,
                    activeAudioFormat = activeAudioFormat,
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    speed = speed,
                    onClose = { showStats = false }
                )
            }

            // Error overlay with recovery actions (transient timeouts shouldn't dead-end).
            if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Playback failed", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClose) { Text("Close", color = Color.White.copy(alpha = 0.8f)) }
                            if (isPlaylist && currentIndex < episodeTitles.size - 1) {
                                TextButton(onClick = { errorMessage = null; player.seekToNextMediaItem() }) {
                                    Text("Next episode", color = Color.White)
                                }
                            }
                            Button(onClick = { errorMessage = null; player.prepare() }) { Text("Retry") }
                        }
                    }
                }
            }

            // ── Control overlay: glassy "bubble" layout ─────────────────
            AnimatedVisibility(
                visible = controlsVisible && !locked && errorMessage == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.5f),
                                0.25f to Color.Transparent,
                                0.75f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.6f)
                            )
                        )
                ) {
                    // Top bar: back, title, and a translucent action pill.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text(
                            text = currentTitle ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        Surface(color = Color.White.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPlaylist) {
                                    IconButton(onClick = { showEpisodes = true; markInteraction() }) {
                                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Episodes", tint = Color.White)
                                    }
                                }
                                val subtitlesActive = currentTracks.groups.any {
                                    it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any { i -> it.isTrackSelected(i) }
                                }
                                IconButton(onClick = { trackSheet = TrackSheetMode.AUDIO; markInteraction() }) {
                                    Icon(Icons.Filled.Audiotrack, "Audio track", tint = Color.White)
                                }
                                IconButton(onClick = { trackSheet = TrackSheetMode.SUBTITLE; markInteraction() }) {
                                    Icon(
                                        if (subtitlesActive) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                                        "Subtitles",
                                        tint = Color.White
                                    )
                                }
                                IconButton(onClick = { showStats = !showStats; markInteraction() }) {
                                    Icon(
                                        imageVector = Icons.Default.BarChart,
                                        contentDescription = "Stats",
                                        tint = if (showStats) Color.Green else Color.White
                                    )
                                }
                                IconButton(onClick = {
                                    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                                    if (uri != null) {
                                        player.pause()
                                        openInExternalPlayer(
                                            context,
                                            uri,
                                            externalContentType,
                                            externalHeaders.takeIf { it.isNotEmpty() }
                                        )
                                    }
                                    markInteraction()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in external player", tint = Color.White)
                                }
                            }
                        }
                    }

                    // Centered Transport Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        if (isPlaylist) {
                            GlassControl(
                                onClick = { player.seekToPreviousMediaItem(); markInteraction() },
                                diameter = 56.dp,
                                enabled = currentIndex > 0
                            ) {
                                Icon(Icons.Filled.SkipPrevious, "Previous episode", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                        GlassControl(
                            onClick = { togglePlay() },
                            diameter = 72.dp,
                            background = Color.White.copy(alpha = 0.2f)
                        ) {
                            androidx.compose.animation.Crossfade(
                                targetState = isPlaying,
                                label = "PlayPauseAnimation"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        if (isPlaylist) {
                            GlassControl(
                                onClick = { player.seekToNextMediaItem(); markInteraction() },
                                diameter = 56.dp,
                                enabled = currentIndex < episodeTitles.size - 1
                            ) {
                                Icon(Icons.Filled.SkipNext, "Next episode", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    // Bottom controls: transport cluster (left) + resize (right),
                    // with the full-width seek bar underneath. Center stays clear.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. Lock Controls
                            GlassControl(
                                onClick = { locked = true; showUnlock = true },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 2. Background Playback Toggle
                            GlassControl(
                                onClick = {
                                    isBackgroundModeEnabled = !isBackgroundModeEnabled
                                    aspectLabel = if (isBackgroundModeEnabled) "Background Playback Enabled" else "Background Playback Disabled"
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (isBackgroundModeEnabled) Color.Green.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Hearing,
                                    contentDescription = "Background mode",
                                    tint = if (isBackgroundModeEnabled) Color.Green else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 3. Screen Rotation Toggle
                            GlassControl(
                                onClick = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        activity.requestedOrientation = if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.ScreenRotation, "Rotate screen", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 4. Playback Speed Button (Moved here)
                            GlassControl(
                                onClick = {
                                    speed = nextSpeed(speed)
                                    player.setPlaybackSpeed(speed)
                                    aspectLabel = "Speed: ${speedLabel(speed)}"
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = speedLabel(speed),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // 5. Repeat Mode Selector
                            GlassControl(
                                onClick = {
                                    val nextMode = when (player.repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                    player.repeatMode = nextMode
                                    repeatMode = nextMode
                                    aspectLabel = when (nextMode) {
                                        Player.REPEAT_MODE_OFF -> "Repeat: Off"
                                        Player.REPEAT_MODE_ONE -> "Repeat: One"
                                        Player.REPEAT_MODE_ALL -> "Repeat: All"
                                        else -> "Repeat: Off"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                    contentDescription = "Repeat mode",
                                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 6. AB Loop Repeat Selector
                            GlassControl(
                                onClick = {
                                    val currentPos = player.currentPosition
                                    if (abStartMs == null) {
                                        abStartMs = currentPos
                                        aspectLabel = "Loop Start [A] Set"
                                    } else if (abEndMs == null) {
                                        if (currentPos > abStartMs!!) {
                                            abEndMs = currentPos
                                            aspectLabel = "Loop End [B] Set: A-B Active"
                                        } else {
                                            aspectLabel = "End must be after Start"
                                        }
                                    } else {
                                        abStartMs = null
                                        abEndMs = null
                                        aspectLabel = "AB Loop Cleared"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (abStartMs != null) Color.Yellow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Loop,
                                        contentDescription = "AB Loop",
                                        tint = if (abStartMs != null) Color.Yellow else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    val badgeText = when {
                                        abStartMs != null && abEndMs != null -> "AB"
                                        abStartMs != null -> "A"
                                        else -> ""
                                    }
                                    if (badgeText.isNotEmpty()) {
                                        Text(
                                            text = badgeText,
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .background(Color.Yellow, shape = RoundedCornerShape(4.dp))
                                                .padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }

                            // 7. Flexible Spacing Separator
                            Spacer(Modifier.weight(1f))

                            // 8. Capture Frame (Photo)
                            GlassControl(
                                onClick = {
                                    val playerView = (context as? Activity)?.findViewById<PlayerView>(R.id.player_view)
                                    if (playerView != null) {
                                        captureFrame(playerView) { bitmap ->
                                            if (bitmap != null) {
                                                saveBitmapToGallery(context, bitmap, currentTitle ?: "PlayBridge")
                                            } else {
                                                (context as? Activity)?.runOnUiThread {
                                                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.CameraAlt, "Capture frame", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 9. Video Zoom (Pan and Zoom toggle)
                            GlassControl(
                                onClick = {
                                    isPanZoomEnabled = !isPanZoomEnabled
                                    if (!isPanZoomEnabled) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                    aspectLabel = if (isPanZoomEnabled) "Pan & Zoom Enabled (Pinch/Drag)" else "Pan & Zoom Disabled"
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (isPanZoomEnabled) Color.Magenta.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = "Zoom and Pan",
                                    tint = if (isPanZoomEnabled) Color.Magenta else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 10. Picture-in-Picture Mode Button
                            GlassControl(
                                onClick = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        enterPip(activity)
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 11. Aspect Ratio Mode Toggle
                            GlassControl(
                                onClick = {
                                    val nextMode = nextResizeMode(resizeMode)
                                    resizeMode = nextMode
                                    aspectLabel = when (nextMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit to Screen"
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoomed"
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretched"
                                        else -> "Fit"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.AspectRatio, "Resize", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val shownPos = if (isScrubbing) (scrubFraction * durationMs).toLong() else positionMs
                            Text(formatTime(shownPos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            SeekBar(
                                fraction = if (durationMs > 0) (shownPos.toFloat() / durationMs) else 0f,
                                bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs) else 0f,
                                onScrubStart = { isScrubbing = true; markInteraction() },
                                onScrub = { scrubFraction = it; markInteraction() },
                                onScrubEnd = {
                                    if (durationMs > 0) {
                                        val target = (it * durationMs).toLong()
                                        player.seekTo(target)
                                        positionMs = target
                                    }
                                    isScrubbing = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // ── Unlock affordance (shown briefly while locked) ──────────
            AnimatedVisibility(
                visible = locked && showUnlock,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassControl(
                        onClick = {
                            locked = false
                            showUnlock = false
                            controlsVisible = true
                            markInteraction()
                        },
                        diameter = 56.dp,
                        background = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Icon(Icons.Filled.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }

            if (showEpisodes) {
                EpisodeSheet(
                    titles = episodeTitles,
                    currentIndex = currentIndex,
                    onSelect = { idx -> player.seekTo(idx, 0L); player.play() },
                    onDismiss = { showEpisodes = false }
                )
            }

            trackSheet?.let { mode ->
                TrackSelectionSheet(
                    mode = mode,
                    tracks = currentTracks,
                    onDismiss = { trackSheet = null },
                    onSelectAudio = { group, idx ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group, idx))
                            .build()
                    },
                    onSelectText = { group, idx ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group, idx))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    },
                    onDisableText = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                )
            }
        }
    }
}

/** Glassy vertical HUD card showing an icon, a vertical progress bar and a percentage label (volume / brightness). */
@Composable
private fun BoxScope.GestureHud(
    icon: @Composable () -> Unit,
    fraction: Float,
    label: String,
    alignment: Alignment
) {
    Surface(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 48.dp),
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter)
                        .background(Color.White)
                )
            }
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Centered HUD showing the scrub target and signed delta during a horizontal seek drag. */
@Composable
private fun BoxScope.SeekHud(deltaMs: Long, targetMs: Long, durationMs: Long) {
    Surface(
        modifier = Modifier.align(Alignment.Center),
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${formatTime(targetMs)} / ${formatTime(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val sign = if (deltaMs >= 0) "+" else "-"
            Text(
                "$sign${formatTime(abs(deltaMs))}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** A circular translucent "glass" button used for the transport controls. */
@Composable
private fun GlassControl(
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.White.copy(alpha = 0.14f),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.matchParentSize()) { content() }
    }
}

/**
 * Slim YouTube-style seek bar: thin rounded track with a buffered sub-track, a white
 * progress fill and a dot thumb that grows while dragging. Tap to seek, drag to scrub.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    bufferedFraction: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val shown = (if (dragging) dragFrac else fraction).coerceIn(0f, 1f)
    val thumbSize = if (dragging) 16.dp else 12.dp

    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFrac = (o.x / size.width).coerceIn(0f, 1f)
                        onScrubStart(); onScrub(dragFrac)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(dragFrac)
                    },
                    onDragEnd = { dragging = false; onScrubEnd(dragFrac) },
                    onDragCancel = { dragging = false }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val f = (o.x / size.width).coerceIn(0f, 1f)
                    onScrubStart(); onScrubEnd(f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val w = maxWidth
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f))
        )
        if (bufferedFraction > 0f) {
            Box(
                Modifier.fillMaxWidth(bufferedFraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.45f))
            )
        }
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(CircleShape).background(Color.White))
        Box(
            Modifier
                .offset(x = (w * shown - thumbSize / 2f).coerceIn(0.dp, w - thumbSize))
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}


private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

private fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOf(current)
    return SPEEDS[(if (i < 0) 1 else i + 1) % SPEEDS.size]
}

private fun speedLabel(s: Float): String {
    val n = if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0').trimEnd('.')
    return "${n}×"
}

private fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * Custom bottom sheet rendered inside the player's own (immersive) window, so the
 * system bars stay hidden — unlike [androidx.compose.material3.ModalBottomSheet], which
 * spawns a separate window that drops out of immersive mode. Tap the scrim or press back
 * to dismiss; [content] gets the scroll state so it can scroll the selected row into view.
 */
@Composable
private fun PlayerSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(ScrollState) -> Unit
) {
    BackHandler(onBack = onDismiss)
    val scrollState = rememberScrollState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        // Floating, width-constrained card anchored bottom-center (not a full-width slab).
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .heightIn(max = maxHeight)
                .pointerInput(Unit) { detectTapGestures { } }, // swallow taps so they don't dismiss
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp)
            ) {
                content(scrollState)
            }
        }
    }
}

private class SheetRow(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit
)

/**
 * Renders [rows] inside a [PlayerSheet], auto-scrolling so the selected row is in view.
 * Offset is estimated from row count (no layout-coordinate APIs needed).
 */
@Composable
private fun SelectionSheet(
    header: String,
    rows: List<SheetRow>,
    emptyMessage: String?,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    PlayerSheet(onDismiss = onDismiss) { scrollState ->
        val selectedIndex = rows.indexOfFirst { it.selected }
        LaunchedEffect(selectedIndex) {
            if (selectedIndex > 0) {
                val rowPx = with(density) { 48.dp.toPx() }
                val headerPx = with(density) { 52.dp.toPx() }
                val target = (headerPx + (selectedIndex - 1) * rowPx).toInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target)
            }
        }
        TrackSectionHeader(header)
        if (rows.isEmpty() && emptyMessage != null) EmptyTracksMessage(emptyMessage)
        rows.forEach { r -> TrackRow(r.label, r.selected, r.enabled, r.onClick) }
    }
}

/** Bottom sheet to pick the active audio *or* subtitle track from what the stream exposes. */
@Composable
private fun TrackSelectionSheet(
    mode: TrackSheetMode,
    tracks: Tracks,
    onDismiss: () -> Unit,
    onSelectAudio: (TrackGroup, Int) -> Unit,
    onSelectText: (TrackGroup, Int) -> Unit,
    onDisableText: () -> Unit
) {
    val type = if (mode == TrackSheetMode.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val groups = tracks.groups.filter { it.type == type }

    val rows = buildList {
        if (mode == TrackSheetMode.AUDIO) {
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    add(SheetRow(audioTrackLabel(g.getTrackFormat(i), i), g.isTrackSelected(i), g.isTrackSupported(i)) {
                        onSelectAudio(g.mediaTrackGroup, i); onDismiss()
                    })
                }
            }
        } else {
            val anyTextSelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            add(SheetRow("Off", !anyTextSelected, true) { onDisableText(); onDismiss() })
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    add(SheetRow(textTrackLabel(g.getTrackFormat(i), i), g.isTrackSelected(i), g.isTrackSupported(i)) {
                        onSelectText(g.mediaTrackGroup, i); onDismiss()
                    })
                }
            }
        }
    }

    SelectionSheet(
        header = if (mode == TrackSheetMode.AUDIO) "Audio" else "Subtitles",
        rows = rows,
        emptyMessage = if (mode == TrackSheetMode.AUDIO) "This stream has only one audio track." else null,
        onDismiss = onDismiss
    )
}

@Composable
private fun EmptyTracksMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

/** Bottom sheet listing the episodes in the current playlist; tap to jump. */
@Composable
private fun EpisodeSheet(
    titles: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = titles.mapIndexed { i, t ->
        SheetRow(t, i == currentIndex, true) { onSelect(i); onDismiss() }
    }
    SelectionSheet(header = "Episodes", rows = rows, emptyMessage = null, onDismiss = onDismiss)
}

@Composable
private fun TrackSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

private fun audioTrackLabel(format: Format, index: Int): String {
    val parts = mutableListOf<String>()
    format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    trackLanguageName(format.language)?.let { name ->
        if (parts.none { it.equals(name, ignoreCase = true) }) parts.add(name)
    }
    if (format.channelCount > 0) {
        parts.add(
            when (format.channelCount) {
                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
                else -> "${format.channelCount}ch"
            }
        )
    }
    return parts.joinToString(" · ").ifBlank { "Audio ${index + 1}" }
}

private fun textTrackLabel(format: Format, index: Int): String {
    val parts = mutableListOf<String>()
    format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    trackLanguageName(format.language)?.let { name ->
        if (parts.none { it.equals(name, ignoreCase = true) }) parts.add(name)
    }
    return parts.joinToString(" · ").ifBlank { "Subtitle ${index + 1}" }
}

private fun trackLanguageName(lang: String?): String? {
    if (lang.isNullOrBlank() || lang == "und") return null
    return try {
        Locale.forLanguageTag(lang).displayLanguage.ifBlank { lang }
    } catch (e: Exception) {
        lang
    }
}

private fun initialBrightness(window: Window?, context: Context): Float {
    val current = window?.attributes?.screenBrightness ?: -1f
    if (current in 0f..1f) return current
    return try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) {
        0.5f
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun captureFrame(playerView: PlayerView, onCaptured: (Bitmap?) -> Unit) {
    val surfaceView = findSurfaceView(playerView)
    if (surfaceView == null) {
        onCaptured(null)
        return
    }

    val surface = surfaceView.holder.surface
    if (surface == null || !surface.isValid) {
        onCaptured(null)
        return
    }

    val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
    val handlerThread = HandlerThread("PixelCopy")
    handlerThread.start()

    try {
        PixelCopy.request(
            surfaceView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    onCaptured(null)
                }
                handlerThread.quitSafely()
            },
            Handler(handlerThread.looper)
        )
    } catch (e: Exception) {
        onCaptured(null)
        handlerThread.quitSafely()
    }
}

private fun findSurfaceView(view: View): SurfaceView? {
    if (view is SurfaceView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val res = findSurfaceView(child)
            if (res != null) return res
        }
    }
    return null
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, title: String) {
    val filename = "${title.replace(Regex("[^a-zA-Z0-9]"), "_")}_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PlayBridge")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    if (uri != null) {
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(context, "Frame saved to Gallery!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save frame: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Failed to capture frame", Toast.LENGTH_SHORT).show()
    }
}

private fun enterPip(activity: android.app.Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        activity.enterPictureInPictureMode(params)
    } else {
        @Suppress("DEPRECATION")
        activity.enterPictureInPictureMode()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StatsOverlay(
    player: ExoPlayer,
    activeVideoFormat: androidx.media3.common.Format?,
    activeAudioFormat: androidx.media3.common.Format?,
    positionMs: Long,
    bufferedMs: Long,
    speed: Float,
    onClose: () -> Unit
) {
    val streamUrl = remember(player.currentMediaItem) {
        player.currentMediaItem?.localConfiguration?.uri?.toString() ?: "Unknown"
    }

    // Refresh state periodically for counters
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val counters = player.videoDecoderCounters
    val rendered = remember(tick) { counters?.renderedOutputBufferCount ?: 0 }
    val dropped = remember(tick) { counters?.droppedBufferCount ?: 0 }
    val skipped = remember(tick) { counters?.skippedOutputBufferCount ?: 0 }

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = Modifier
            .padding(16.dp)
            .widthIn(max = 420.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Statistics (MPV style)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.Green,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close Stats",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            StatItem(label = "Source URL", value = streamUrl, isUrl = true)
            StatItem(
                label = "Resolution",
                value = activeVideoFormat?.let { "${it.width}x${it.height}" } ?: "${player.videoSize.width}x${player.videoSize.height}"
            )
            StatItem(label = "Video Codec", value = activeVideoFormat?.codecs ?: activeVideoFormat?.sampleMimeType ?: "Unknown")
            StatItem(label = "Audio Codec", value = activeAudioFormat?.codecs ?: activeAudioFormat?.sampleMimeType ?: "Unknown")
            StatItem(
                label = "Buffer Duration",
                value = "${String.format(Locale.US, "%.1f", (bufferedMs - positionMs).coerceAtLeast(0L) / 1000f)}s (Max: 300.0s)"
            )
            StatItem(label = "Playback Speed", value = "${speed}x")
            StatItem(
                label = "Decoder Performance",
                value = "Rendered: $rendered / Dropped: $dropped / Skipped: $skipped"
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, isUrl: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = if (isUrl) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
