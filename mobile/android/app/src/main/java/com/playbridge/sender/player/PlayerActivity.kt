package com.playbridge.sender.player

import android.os.Build
import android.view.OrientationEventListener
import com.playbridge.sender.R
import com.playbridge.sender.cast.VideoDetector
import android.content.Context
import com.playbridge.shared.logging.redactUrlForLog
import androidx.core.net.toUri
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.lifecycle.lifecycleScope
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.ui.theme.PlayBridgePlayerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.get

/** `adb logcat -s PB_PLAYER` */
internal const val PB_PLAYER = "PB_PLAYER"

/** Prepare only after PlayerView is attached (TextureView surface ready). */
internal fun ensurePrepared(player: Player) {
    if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
        player.playWhenReady = true
    } else if (!player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
        player.playWhenReady = true
    }
}

/**
 * True hang: a frame has painted (or we have real buffer) but the clock never leaves
 * BUFFERING. Requires [hasRenderedFrame] so slow remote opens (buffered but not playing
 * yet) are not mistaken for stuck audio and "recovered" into silence.
 */
internal fun isPlaybackStuck(player: Player, hasRenderedFrame: Boolean): Boolean {
    if (!hasRenderedFrame) return false
    if (player.trackSelectionParameters.disabledTrackTypes.contains(
            androidx.media3.common.C.TRACK_TYPE_AUDIO,
        )
    ) {
        return false // already dropped audio — nothing left for stuck recovery
    }
    return player.playbackState == Player.STATE_BUFFERING &&
        player.playWhenReady &&
        !player.isPlaying &&
        player.bufferedPosition > 500
}

/** TV-style audio recovery (discontinuity / decoder-init). Never mutes as first response. */
private fun recoverFromPlayerError(player: ExoPlayer, error: PlaybackException) {
    val isAudioDiscontinuity =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.cause is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException
    val isDecoderInit =
        error.cause is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
    when {
        isAudioDiscontinuity -> {
            Log.w(PB_PLAYER, "audio discontinuity — reinit")
            PhoneExoPlayerFactory.recoverAudio(player, "reinit")
        }
        isDecoderInit -> {
            Log.w(PB_PLAYER, "decoder init failed — clear audio override")
            PhoneExoPlayerFactory.recoverAudio(player, "clear-override")
        }
    }
}

/**
 * A single sidecar subtitle track to attach to playback.
 */
data class SubtitleTrack(
    val url: String,
    val label: String? = null,
    val language: String? = null,
    /** The subtitle's own request headers, from the page that loaded it. */
    val headers: Map<String, String> = emptyMap(),
    /** The format found in the file (`vtt`, `srt`, `ass`, `ttml`); null guesses from the URL. */
    val format: String? = null,
)

/**
 * Full-screen, landscape in-app video player backed by Media3 ExoPlayer, or by mpv through
 * [MpvMediaPlayer] when the Phone player setting asks for it or ExoPlayer can't play a format.
 *
 * Launched via [PlayerLauncher.start]. Handles HLS / DASH / progressive streams
 * (auto-detected by [DefaultMediaSourceFactory]), forwards request headers for
 * stream URLs that need them, and attaches any sidecar subtitle tracks.
 *
 * Used for local phone playback and the cast sheet's
 * "Play on phone" menu item — neither casts to the TV.
 */
open class PlayerActivity : ComponentActivity() {

    private var player: Player? = null
    private var attachedPageSessionId: String? = null

    /** The player on screen; changing it rebuilds the player UI around the new one. */
    private val playerState = mutableStateOf<Player?>(null)
    private val engineState = mutableStateOf(PhonePlayerEngine.EXOPLAYER)
    /** Shown over the player after an automatic switch or a switch mpv can't make. */
    private val engineNoticeState = mutableStateOf<EngineNotice?>(null)
    /** The tapped thumbnail's screen bounds, until the opening animation has run. */
    private val growFromState = mutableStateOf<android.graphics.Rect?>(null)
    private var playerMode = PhonePlayerMode.AUTOMATIC
    private var switchedToMpvAutomatically = false
    private var requestHeaders: Map<String, String> = emptyMap()
    /** Opening audio and subtitle languages, applied to each player this activity creates. */
    private var trackLanguages = PhoneTrackLanguages(audio = null, subtitles = null, subtitlesOffUnlessPreferred = false)
    /** Items of a single video or playlist; website queues are rebuilt by [PagePlayerSession]. */
    private var queueItems: List<MediaItem> = emptyList()

    /**
     * The More sheet's "Keep playing in the background" switch, hoisted here (single source
     * of truth shared with the composable) because onStop reads it.
     */
    private val backgroundModeState = mutableStateOf(false)
    private val isInPipModeState = mutableStateOf(false)


    /** Follow the video's aspect for orientation until the user rotates manually. */
    private var autoOrientationEnabled = true

    /**
     * Portrait first: the screen follows how the phone is held. After the fullscreen button
     * forces an orientation, it follows the phone again once the phone is turned to match.
     */
    private var followPhone = false
    private var forcedOrientation: Int? = null
    private val orientationWatcher by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val forced = forcedOrientation ?: return
                if (degrees == ORIENTATION_UNKNOWN) return
                val heldLandscape = degrees in 60..120 || degrees in 240..300
                val heldPortrait = degrees <= 30 || degrees >= 330 || degrees in 150..210
                val matches = if (forced == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) heldLandscape else heldPortrait
                // With auto-rotate off, following the phone would snap straight back, so the
                // button's choice stays until the player's own controls change it.
                if (matches && autoRotateOn()) {
                    forcedOrientation = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }
        }
    }

    private fun autoRotateOn(): Boolean =
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    /** The rotate button: landscape to portrait or back, by the same rules as the fullscreen button. */
    fun toggleRotation() {
        val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setFullscreen(!landscapeNow)
    }

    /** Fullscreen in landscape, or back to portrait, from the player's own buttons. */
    private fun setFullscreen(fullscreen: Boolean) {
        val target = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        autoOrientationEnabled = false
        requestedOrientation = target
        if (followPhone && autoRotateOn()) {
            forcedOrientation = target
            if (orientationWatcher.canDetectOrientation()) orientationWatcher.enable()
        }
    }

    // ── Background-mode media session ────────────────────────────────────────
    // While background playback is enabled and the activity leaves the foreground,
    // playback is exposed through MediaPlaybackService: a media notification with
    // play/pause/stop, audio focus, and a wake lock — the same owner-refcounted
    // service the browser uses. Without it, background playback ran with no
    // controls, no notification, and nothing keeping the process alive.
    private var backgroundSessionActive = false
    private var mediaControlJob: kotlinx.coroutines.Job? = null
    private var backgroundPositionJob: Job? = null

    private val backgroundStateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (backgroundSessionActive) {
                com.playbridge.sender.cast.MediaPlaybackService.sendStateUpdate(
                    this@PlayerActivity,
                    com.playbridge.sender.cast.MediaPlaybackService.OWNER_INAPP_PLAYER,
                    isPlaying,
                )
                publishBackgroundMediaPosition()
            }
        }
    }

    private fun publishBackgroundMediaPosition() {
        val current = player ?: return
        if (!backgroundSessionActive) return
        com.playbridge.sender.cast.MediaPlaybackService.sendPositionUpdate(
            com.playbridge.sender.cast.MediaPlaybackService.OWNER_INAPP_PLAYER,
            durationSeconds = current.duration.takeIf { it > 0 }?.div(1000.0) ?: -1.0,
            positionSeconds = current.currentPosition.coerceAtLeast(0) / 1000.0,
            playbackRate = current.playbackParameters.speed.toDouble(),
            seekSupported = current.isCurrentMediaItemSeekable,
        )
    }

    private fun startBackgroundMediaSession() {
        val exo = player ?: return
        if (backgroundSessionActive) return
        backgroundSessionActive = true
        val service = com.playbridge.sender.cast.MediaPlaybackService
        // Notification taps should land back HERE (singleTask brings this instance
        // forward), not in the browser.
        service.setContentIntent(
            Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        service.activate(this, service.OWNER_INAPP_PLAYER)
        service.sendMetadataUpdate(
            ownerId = service.OWNER_INAPP_PLAYER,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Video",
            artist = "PlayBridge Player",
        )
        service.sendStateUpdate(this, service.OWNER_INAPP_PLAYER, exo.isPlaying)
        exo.addListener(backgroundStateListener)
        publishBackgroundMediaPosition()
        backgroundPositionJob?.cancel()
        backgroundPositionJob = lifecycleScope.launch {
            while (backgroundSessionActive) {
                delay(1_000)
                publishBackgroundMediaPosition()
            }
        }
        // Obey notification controls. lifecycleScope keeps collecting while STOPPED
        // (it only dies with the activity), which is exactly the window we need.
        mediaControlJob?.cancel()
        mediaControlJob = lifecycleScope.launch {
            com.playbridge.sender.cast.MediaControlChannel.actions.collect { command ->
                if (!backgroundSessionActive || !command.targets(com.playbridge.sender.cast.MediaPlaybackService.OWNER_INAPP_PLAYER)) return@collect
                val p = player ?: return@collect
                when (command.action) {
                    com.playbridge.sender.cast.MediaControlChannel.Action.PLAY -> p.play()
                    com.playbridge.sender.cast.MediaControlChannel.Action.PAUSE -> p.pause()
                    com.playbridge.sender.cast.MediaControlChannel.Action.STOP -> {
                        p.pause()
                        finish()
                    }
                    com.playbridge.sender.cast.MediaControlChannel.Action.SEEK_TO -> {
                        val target = command.positionMs ?: return@collect
                        if (p.isCurrentMediaItemSeekable && p.duration > 0) p.seekTo(target.coerceIn(0, p.duration))
                        publishBackgroundMediaPosition()
                    }
                }
            }
        }
    }

    private fun stopBackgroundMediaSession() {
        if (!backgroundSessionActive) return
        backgroundSessionActive = false
        mediaControlJob?.cancel()
        mediaControlJob = null
        backgroundPositionJob?.cancel()
        backgroundPositionJob = null
        player?.removeListener(backgroundStateListener)
        com.playbridge.sender.cast.MediaPlaybackService.setContentIntent(null)
        com.playbridge.sender.cast.MediaPlaybackService.deactivate(
            com.playbridge.sender.cast.MediaPlaybackService.OWNER_INAPP_PLAYER
        )
    }

    /**
     * Back: with "Keep playing in the background" on and the video playing, the player steps
     * aside (its own task goes behind the browser) and keeps playing with a media notification;
     * otherwise it closes.
     */
    private fun leave() {
        if (backgroundModeState.value && player?.playWhenReady == true) moveTaskToBack(true) else finish()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only one player plays at a time: a video opened in the other player activity (portrait
        // first or not) replaces one still playing in the background.
        current?.get()?.takeIf { it !== this }?.finish()
        current = java.lang.ref.WeakReference(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

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
        val headers: Map<String, String> = androidx.core.content.IntentCompat.getSerializableExtra(
            intent,
            EXTRA_HEADERS,
            HashMap::class.java
        ) as? HashMap<String, String> ?: emptyMap()

        val subUrls = intent.getStringArrayListExtra(EXTRA_SUB_URLS).orEmpty()
        val subLabels = intent.getStringArrayListExtra(EXTRA_SUB_LABELS).orEmpty()
        val subLangs = intent.getStringArrayListExtra(EXTRA_SUB_LANGS).orEmpty()
        val subFormats = intent.getStringArrayListExtra(EXTRA_SUB_FORMATS).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val subHeaders = androidx.core.content.IntentCompat.getSerializableExtra(
            intent, EXTRA_SUB_HEADERS, ArrayList::class.java,
        ) as? ArrayList<HashMap<String, String>> ?: arrayListOf()
        val subtitles = subUrls.mapIndexed { i, u ->
            SubtitleTrack(
                url = u,
                label = subLabels.getOrNull(i)?.takeIf { it.isNotBlank() },
                language = subLangs.getOrNull(i)?.takeIf { it.isNotBlank() },
                headers = subHeaders.getOrNull(i).orEmpty(),
                format = subFormats.getOrNull(i)?.takeIf { it.isNotBlank() },
            )
        }

        // Website preferences affect only the opening policy, not the manual rotate control.
        // Without one, the Landscape videos setting decides: fullscreen at once, or follow the
        // phone. Vertical videos turn to portrait either way.
        val requestedByWebsite = PhonePlayerOpeningOrientation.parse(intent.getStringExtra(EXTRA_INITIAL_ORIENTATION))
        // The launcher picked the activity from the setting (see activityClass), so the class says
        // which way this player opens. DataStore is already loaded, so the read below is immediate.
        val settings = get<SettingsRepository>()
        val landscapeOpen = if (this is PortraitFirstPlayerActivity) PhoneLandscapeOpen.PORTRAIT_FIRST else PhoneLandscapeOpen.FULLSCREEN
        val opening = playerOpening(requestedByWebsite, landscapeOpen)
        followPhone = opening.followPhone
        autoOrientationEnabled = opening.automatic
        requestedOrientation = opening.requestedOrientation
        // Rotate in place rather than with the system's cross-screen jump.
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.player_close)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        Log.i(
            PB_PLAYER,
            "start title=${title?.take(80)} mime=$contentType " +
                "url=${redactUrlForLog(url)}",
        )

        requestHeaders = headers
        playerMode = runBlocking { settings.phonePlayerMode.first() }
        backgroundModeState.value = runBlocking { settings.phoneBackgroundPlay.first() }
        val pageSessionId = intent.getStringExtra(EXTRA_PAGE_SESSION_ID)
        // Subtitles ticked in the cast sheet, or sent by a website, override the subtitle preference.
        trackLanguages = PhoneTrackLanguages.of(
            audioSetting = runBlocking { settings.preferredAudioLang.first() },
            subtitleSetting = runBlocking { settings.preferredSubtitleLang.first() },
            chosenSubtitles = subtitles.isNotEmpty() || pageSessionId != null,
        )
        val request = PhoneMediaRequest(headers)
        val initialTitle: String?
        val startIndex: Int
        if (pageSessionId != null) {
            attachedPageSessionId = pageSessionId
            initialTitle = title
            startIndex = 0
        } else if (isPlaylist) {
            // Series auto-play: load every episode so the player advances on its own.
            val titles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES).orEmpty()
            queueItems = playlistUrls!!.mapIndexed { i, u ->
                buildMediaItem(u, null, titles.getOrNull(i), emptyList(), request)
            }
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, queueItems.lastIndex.coerceAtLeast(0))
            initialTitle = titles.getOrNull(startIndex)
        } else {
            // ExoPlayer sends the video's headers with every request; mpv fetches subtitles separately,
            // with the headers the page used for each subtitle, or else the video's headers when the
            // subtitle is on the video's own origin.
            val subtitleRequest = request.copy(
                subtitleHeaders = subtitles.mapNotNull { sub ->
                    when {
                        sub.headers.isNotEmpty() -> sub.url to sub.headers
                        sameOrigin(sub.url, url!!) -> sub.url to headers
                        else -> null
                    }
                }.toMap(),
            )
            queueItems = listOf(buildMediaItem(url!!, contentType, title, subtitles, subtitleRequest))
            startIndex = 0
            initialTitle = title
        }

        val initialEngine = playerMode.initialEngine.takeIf {
            it == PhonePlayerEngine.EXOPLAYER || PhonePlayerFallback.canOpen(firstUrl(url, playlistUrls))
        } ?: PhonePlayerEngine.EXOPLAYER
        val first = createPlayer(initialEngine)
        if (!loadQueue(first, startIndex, startPositionMs, initial = true)) {
            first.release()
            finish()
            return
        }
        showPlayer(first, initialEngine)
        // Grow the video out of the tapped thumbnail on a fresh open, not on recreation.
        growFromState.value = if (savedInstanceState == null) {
            androidx.core.content.IntentCompat.getParcelableExtra(intent, PlayerLaunchOrigin.EXTRA_ORIGIN_BOUNDS, android.graphics.Rect::class.java)
        } else null
        val poster = PlayerPoster(
            bitmap = intent.getStringExtra(EXTRA_THUMBNAIL_KEY)?.let(VideoDetector::getCachedThumbnail),
            url = intent.getStringExtra(EXTRA_POSTER_URL),
            siteIconUrl = intent.getStringExtra(EXTRA_SITE_ICON_URL),
        )

        setContent {
            PlayBridgePlayerTheme {
                val waitingForWebsiteQueue by PagePlayerSession.waitingForNext.collectAsState()
                val isInPip by isInPipModeState
                val current = playerState.value ?: return@PlayBridgePlayerTheme
                val engine by engineState
                // A new player starts the screen's state afresh; its surface moves to a new PlayerView.
                key(current) { PlayerScreen(
                    player = current,
                    engine = engine,
                    onSwitchEngine = { switchEngine(it, automatic = false) },
                    title = initialTitle,
                    externalHeaders = headers,
                    externalContentType = contentType,
                    websiteQueue = pageSessionId != null,
                    waitingForWebsiteQueue = pageSessionId != null && waitingForWebsiteQueue,
                    onClose = ::leave,
                    onEnded = { if (pageSessionId == null || !PagePlayerSession.waitForNext(pageSessionId)) finish() },
                    isInPip = isInPip,
                    poster = poster,
                    growFrom = growFromState.value,
                    onGrown = { growFromState.value = null },
                    onFullscreen = ::setFullscreen,
                    backgroundMode = backgroundModeState,
                    engineNotice = engineNoticeState.value,
                    onEngineNoticeAction = {
                        engineNoticeState.value = null
                        switchEngine(PhonePlayerEngine.EXOPLAYER, automatic = false)
                    },
                    onEngineNoticeDismiss = { engineNoticeState.value = null },
                ) }
            }
        }
    }

    private fun sameOrigin(first: String, second: String): Boolean {
        val a = runCatching { java.net.URI(first) }.getOrNull() ?: return false
        val b = runCatching { java.net.URI(second) }.getOrNull() ?: return false
        return a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }

    private fun firstUrl(url: String?, playlistUrls: List<String>?): String =
        url ?: playlistUrls?.firstOrNull().orEmpty()

    private fun createPlayer(engine: PhonePlayerEngine): Player = when (engine) {
        PhonePlayerEngine.EXOPLAYER -> buildPlayer(this, requestHeaders)
        PhonePlayerEngine.MPV -> MpvMediaPlayer(this)
    }.also { it.trackSelectionParameters = trackLanguages.applyTo(it.trackSelectionParameters) }

    /** Loads the video, playlist or website queue into [target]. False when the website session is gone. */
    private fun loadQueue(target: Player, index: Int, positionMs: Long, initial: Boolean): Boolean {
        val pageSessionId = attachedPageSessionId
        if (pageSessionId != null) {
            return PagePlayerSession.attach(
                pageSessionId, this, target, onQueueFinished = { finish() },
                startIndex = index.takeUnless { initial }, startPositionMs = positionMs.takeUnless { initial },
            )
        }
        if (queueItems.size > 1) target.setMediaItems(queueItems, index, positionMs)
        else target.setMediaItem(queueItems.first(), positionMs)
        return true
    }

    private fun showPlayer(next: Player, engine: PhonePlayerEngine) {
        next.addListener(playerListener)
        if (backgroundSessionActive) next.addListener(backgroundStateListener)
        player = next
        engineState.value = engine
        playerState.value = next
        Log.i(PB_PLAYER, "player=${engine.label} mode=${playerMode.id}")
        // prepare() is deferred until PlayerView attaches (see ensurePrepared).
    }

    /** Moves the current video, at its current position, to the other decoder. */
    private fun switchEngine(target: PhonePlayerEngine, automatic: Boolean) {
        val old = player ?: return
        if (target == engineState.value) return
        val url = old.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        if (target == PhonePlayerEngine.MPV && !PhonePlayerFallback.canOpen(url)) {
            if (!automatic) engineNoticeState.value = EngineNotice("mpv can't open this video")
            return
        }
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        val position = old.currentPosition.coerceAtLeast(0)
        old.removeListener(playerListener)
        old.removeListener(backgroundStateListener)
        val next = createPlayer(target)
        next.setPlaybackSpeed(old.playbackParameters.speed)
        next.repeatMode = old.repeatMode
        if (!loadQueue(next, index, position, initial = false)) {
            next.release()
            finish()
            return
        }
        old.release()
        Log.i(PB_PLAYER, "switch to ${target.label} automatic=$automatic at index=$index")
        showPlayer(next, target)
        engineNoticeState.value = if (automatic) {
            EngineNotice("Playing with mpv", "ExoPlayer can't decode this format.", "Use ExoPlayer")
        } else null
    }

    /** In Automatic mode, hands a format ExoPlayer can't play to mpv, once. */
    private fun fallBackToMpv(reason: String): Boolean {
        if (playerMode != PhonePlayerMode.AUTOMATIC || switchedToMpvAutomatically) return false
        if (engineState.value != PhonePlayerEngine.EXOPLAYER) return false
        val url = player?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        if (!PhonePlayerFallback.canOpen(url)) return false
        switchedToMpvAutomatically = true
        Log.w(PB_PLAYER, "ExoPlayer can't play this ($reason); switching to mpv")
        // Listener callbacks run inside ExoPlayer; release it after they return.
        window.decorView.post { switchEngine(PhonePlayerEngine.MPV, automatic = true) }
        return true
    }

    // Match the player orientation to the video: portrait content (e.g. phone
    // recordings, shorts) rotates the activity to portrait as soon as its size is
    // known; landscape content keeps the sensor-landscape default. The manual
    // rotate button disables this so it can't fight a user choice.
    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (!autoOrientationEnabled) return
            val w = videoSize.width
            val h = videoSize.height
            if (w <= 0 || h <= 0) return
            // Some containers deliver a rotated frame + rotation metadata.
            val swapped = videoSize.unappliedRotationDegrees % 180 != 0
            val isPortraitVideo = if (swapped) w > h else h > w
            // Portrait first leaves landscape videos however the phone is held.
            if (followPhone && !isPortraitVideo) return
            requestedOrientation = if (isPortraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (PhonePlayerFallback.hasUnplayableTrackType(tracks)) fallBackToMpv("no decoder for a track")
        }

        override fun onPlayerError(error: PlaybackException) {
            val sanitizedMsg = error.message?.let { com.playbridge.shared.logging.redactLogText(it) } ?: "unknown"
            Log.e(PB_PLAYER, "error ${error.errorCodeName}: $sanitizedMsg")
            if (PhonePlayerFallback.isFormatError(error.errorCode) && fallBackToMpv(error.errorCodeName)) return
            (player as? ExoPlayer)?.let { recoverFromPlayerError(it, error) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: notification and dashboard returns keep playback; a new video or website
        // session replaces it.
        val id = intent.getStringExtra(EXTRA_PAGE_SESSION_ID)
        val replacesPlayback = if (id != null) id != attachedPageSessionId
            else intent.hasExtra(EXTRA_URL) || intent.hasExtra(EXTRA_PLAYLIST_URLS)
        if (replacesPlayback) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground (e.g. via the notification): the on-screen controls take
        // over again. Background play stays on until it's switched off in More.
        stopBackgroundMediaSession()
        // Resume if the user returned while content was ready (onStop pauses).
        player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED) {
                p.playWhenReady = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause when leaving the foreground, UNLESS background mode is enabled — in which
        // case expose playback through the media notification service. Skip on config
        // changes (rotation would flash a notification) and in PiP (still visible).
        if (!backgroundModeState.value) {
            player?.playWhenReady = false
        } else if (!isFinishing && !isChangingConfigurations && !isInPipModeState.value) {
            startBackgroundMediaSession()
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, R.anim.player_close)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        orientationWatcher.disable()
        stopBackgroundMediaSession()
        // Flush a final resume point and clear the in-app leg (the polling loop is already
        // cancelled with lifecycleScope). Only fires on real close, not on rotation — the
        // activity handles orientation config changes itself.
        attachedPageSessionId?.let(PagePlayerSession::close)
        player?.release()
        player = null
    }

    companion object {
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null

        /** Brings back the player still playing in the background, or null when there's none. */
        fun returnIntent(context: Context): Intent? {
            val playing = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return null
            return Intent(context, playing.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /**
         * The player to launch: [PortraitFirstPlayerActivity] when landscape videos open portrait
         * first and the website hasn't asked for an orientation, so the window opens however the
         * phone is held rather than in landscape.
         */
        fun activityClass(websiteOrientation: PhonePlayerOpeningOrientation? = null): Class<out PlayerActivity> {
            if (websiteOrientation != null) return PlayerActivity::class.java
            val settings = org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<SettingsRepository>()
                ?: return PlayerActivity::class.java
            val open = runBlocking { settings.phoneLandscapeOpen.first() }
            return if (open == PhoneLandscapeOpen.PORTRAIT_FIRST) PortraitFirstPlayerActivity::class.java
            else PlayerActivity::class.java
        }

        const val EXTRA_PAGE_SESSION_ID = "page_session_id"
        const val EXTRA_INITIAL_ORIENTATION = "initial_orientation"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_HEADERS = "sub_headers"
        const val EXTRA_SUB_FORMATS = "sub_formats"
        const val EXTRA_SUB_LABELS = "sub_labels"
        const val EXTRA_SUB_LANGS = "sub_langs"
        const val EXTRA_PLAYLIST_URLS = "playlist_urls"
        const val EXTRA_PLAYLIST_TITLES = "playlist_titles"
        const val EXTRA_START_INDEX = "start_index"

        const val EXTRA_START_POSITION_MS = "start_position_ms"
        /** A detector thumbnail's cache key (the media URL), shown until the first frame. */
        const val EXTRA_THUMBNAIL_KEY = "thumbnail_key"
        /** A website poster or backdrop URL, shown until the first frame. */
        const val EXTRA_POSTER_URL = "poster_url"
        const val EXTRA_SITE_ICON_URL = "site_icon_url"


        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun buildPlayer(context: Context, headers: Map<String, String>): ExoPlayer {
            // TV-aligned factory: sync MediaCodec, decoder fallback, audio offload off,
            // hardware-first video (see PhoneExoPlayerFactory / shared ExoPlayerEngine).
            return PhoneExoPlayerFactory.create(context, headers)
        }

        /**
         * Builds a [MediaSource] that carries per-request [headers] on its own HTTP data
         * source — used for lazily-resolved episodes whose scraper streams need headers
         * different from the first episode the player was built with.
         */
        internal fun buildHeaderMediaSource(
            context: Context,
            mediaItem: MediaItem,
            headers: Map<String, String>
        ): MediaSource {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
            httpFactory.setDefaultRequestProperties(headers)
            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)
            return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }

        internal fun buildMediaItem(
            url: String,
            contentType: String?,
            title: String?,
            subtitles: List<SubtitleTrack>,
            request: PhoneMediaRequest = PhoneMediaRequest(),
        ): MediaItem {
            val builder = MediaItem.Builder().setUri(url).setTag(request)

            // Only force mime when it changes MediaSource selection (HLS/DASH/TS).
            // Progressive containers are left unset so extractors sniff — matches
            // PhoneExoPlayerFactory.mapContentTypeToMime.
            val mime = PhoneExoPlayerFactory.mapContentTypeToMime(contentType, url)
            if (mime != null) builder.setMimeType(mime)

            if (!title.isNullOrBlank()) {
                builder.setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
                )
            }

            if (subtitles.isNotEmpty()) {
                builder.setSubtitleConfigurations(
                    subtitles.mapIndexed { index, sub ->
                        val subMime = when (sub.format) {
                            "srt" -> MimeTypes.APPLICATION_SUBRIP
                            "ass" -> MimeTypes.TEXT_SSA
                            "ttml" -> MimeTypes.APPLICATION_TTML
                            "vtt" -> MimeTypes.TEXT_VTT
                            else -> if (sub.url.endsWith(".srt", ignoreCase = true))
                                MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                        }
                        MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
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
