package com.playbridge.sender.cast.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createScreenMirrorCandidateCommandJson
import com.playbridge.shared.protocol.createScreenMirrorOfferJson
import com.playbridge.shared.protocol.createScreenMirrorStartJson
import com.playbridge.shared.protocol.createScreenMirrorStopJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.math.roundToInt

/** Owns one phone-to-TV WebRTC screen mirror session. All mutable WebRTC state stays on [thread]. */
class ScreenMirrorCoordinator(
    private val context: Context,
    private val webSocketClient: WebSocketClient,
    private val appScope: CoroutineScope,
) {
    enum class Phase { IDLE, STARTING, CONNECTING, MIRRORING, FAILED }

    enum class AudioStatus { DISABLED, STARTING, ACTIVE, UNAVAILABLE }

    enum class Quality(
        val id: String,
        val label: String,
        val maxLongEdge: Int,
        val maxBitrateBps: Int,
    ) {
        DEFAULT("default", "Default", 1_280, 6_000_000),
        HIGH("high", "High", 1_920, 10_000_000),
        MAXIMUM("maximum", "Maximum", 2_560, 16_000_000);

        companion object {
            fun fromId(id: String?): Quality = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    data class Options(
        val quality: Quality = Quality.DEFAULT,
        val deviceAudio: Boolean = true,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val sessionId: String? = null,
        val message: String? = null,
        val deviceAudioRequested: Boolean = false,
        val audioStatus: AudioStatus = AudioStatus.DISABLED,
        val audioMessage: String? = null,
    ) {
        val isActive: Boolean get() = phase == Phase.STARTING || phase == Phase.CONNECTING || phase == Phase.MIRRORING
    }

    private val worker = HandlerThread("PB-ScreenMirror").apply { start() }
    private val handler = Handler(worker.looper)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var sessionId: String? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var playbackAudioFactory: ScreenMirrorPlaybackAudioRecordFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var answerApplied = false
    private var generation = 0L
    private var quality = Quality.DEFAULT
    private var timeoutJob: Job? = null

    init {
        appScope.launch {
            webSocketClient.messages.collect { text ->
                when (val message = com.playbridge.shared.protocol.parseIncomingMessage(text)) {
                    is IncomingMessage.ScreenMirrorReady -> post { if (matches(message.sessionId)) createOffer() }
                    is IncomingMessage.ScreenMirrorAnswer -> post { if (matches(message.sessionId)) applyAnswer(message.sdp) }
                    is IncomingMessage.ScreenMirrorCandidate -> post {
                        if (matches(message.sessionId)) addRemoteCandidate(message)
                    }
                    is IncomingMessage.ScreenMirrorEvent -> post {
                        if (!matches(message.sessionId)) return@post
                        when (message.state) {
                            "connected" -> {
                                timeoutJob?.cancel()
                                timeoutJob = null
                                Log.i(TAG, "Screen mirror peer connected")
                                _state.value = _state.value.copy(
                                    phase = Phase.MIRRORING,
                                    sessionId = message.sessionId,
                                    message = null,
                                )
                            }
                            "failed" -> fail(message.reason ?: "TV could not start screen mirroring")
                            "stopped" -> stopInternal(notifyReceiver = false, reason = null)
                        }
                    }
                    else -> Unit
                }
            }
        }
        appScope.launch {
            webSocketClient.connectionState.collect { connection ->
                post {
                    if (_state.value.isActive && connection !is WebSocketClient.ConnectionState.Connected) {
                        fail("Connection to TV was lost")
                    }
                }
            }
        }
    }

    /** Called only after CastSessionService has entered the mediaProjection foreground-service type. */
    fun start(projectionPermission: Intent, options: Options = Options()) = post {
        if (_state.value.isActive) return@post
        val id = UUID.randomUUID().toString()
        val currentGeneration = ++generation
        val deviceAudio = options.deviceAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        quality = options.quality
        sessionId = id
        _state.value = State(
            phase = Phase.STARTING,
            sessionId = id,
            message = "Preparing screen capture…",
            deviceAudioRequested = deviceAudio,
            audioStatus = if (deviceAudio) AudioStatus.STARTING else AudioStatus.DISABLED,
        )
        try {
            initialiseCapture(projectionPermission, currentGeneration, options.copy(deviceAudio = deviceAudio))
            if (!webSocketClient.send(createScreenMirrorStartJson(id))) {
                fail("TV is not connected")
                return@post
            }
            _state.value = _state.value.copy(phase = Phase.CONNECTING, message = "Waiting for TV…")
            timeoutJob?.cancel()
            timeoutJob = appScope.launch {
                delay(10_000)
                post { if (matches(id) && _state.value.phase != Phase.MIRRORING) fail("TV did not respond in time") }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start screen mirror", t)
            fail("Unable to start screen capture")
        }
    }

    fun stop(reason: String = "stopped_by_phone") = post { stopInternal(notifyReceiver = true, reason = reason) }

    private fun initialiseCapture(
        permission: Intent,
        captureGeneration: Long,
        options: Options,
    ) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val egl = EglBase.create()
        eglBase = egl
        val peerFactoryBuilder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        if (options.deviceAudio) {
            runCatching { createPlaybackAudioDeviceModule(captureGeneration) }
                .onSuccess { module ->
                    audioDeviceModule = module
                    peerFactoryBuilder.setAudioDeviceModule(module)
                }
                .onFailure {
                    Log.w(TAG, "Playback audio initialization failed; continuing video-only", it)
                    markAudioUnavailable(captureGeneration, "Device audio is unavailable; mirroring video only.")
                }
        }
        val peerFactory = peerFactoryBuilder.createPeerConnectionFactory()
        factory = peerFactory
        val source = peerFactory.createVideoSource(true)
        videoSource = source
        val capture = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() = post {
                if (captureGeneration == generation && _state.value.isActive) {
                    fail("Screen capture permission was revoked")
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) = post {
                if (captureGeneration != generation || !_state.value.isActive) return@post
                val activeCapture = capturer ?: return@post
                val (captureWidth, captureHeight) =
                    screenMirrorCaptureSize(width, height, options.quality)
                runCatching { activeCapture.changeCaptureFormat(captureWidth, captureHeight, 30) }
                    .onFailure { Log.w(TAG, "Unable to resize screen capture", it) }
            }
        })
        capturer = capture
        val textureHelper = SurfaceTextureHelper.create("PB-ScreenMirrorCapture", egl.eglBaseContext)
        surfaceTextureHelper = textureHelper
        capture.initialize(
            textureHelper,
            context.applicationContext,
            source.capturerObserver,
        )
        val (width, height) = mirrorSize(context.resources.displayMetrics, options.quality)
        Log.i(
            TAG,
            "Starting capture quality=${options.quality.id} size=${width}x$height " +
                "maxBitrate=${options.quality.maxBitrateBps} deviceAudio=${options.deviceAudio}",
        )
        capture.startCapture(width, height, 30)
        videoTrack = peerFactory.createVideoTrack("screen-$sessionId", source)
        if (options.deviceAudio && audioDeviceModule != null) {
            val projection = capture.mediaProjection
            if (projection == null) {
                markAudioUnavailable(captureGeneration, "Device audio capture could not start; mirroring video only.")
            } else {
                playbackAudioFactory?.attachProjection(projection)
                runCatching {
                    val constraints = MediaConstraints().apply {
                        optional += MediaConstraints.KeyValuePair("googEchoCancellation", "false")
                        optional += MediaConstraints.KeyValuePair("googAutoGainControl", "false")
                        optional += MediaConstraints.KeyValuePair("googNoiseSuppression", "false")
                        optional += MediaConstraints.KeyValuePair("googHighpassFilter", "false")
                    }
                    val source = peerFactory.createAudioSource(constraints)
                    audioSource = source
                    audioTrack = peerFactory.createAudioTrack("audio-$sessionId", source)
                }.onFailure {
                    Log.w(TAG, "Unable to create WebRTC playback audio track", it)
                    markAudioUnavailable(captureGeneration, "Device audio is unavailable; mirroring video only.")
                }
            }
        }
    }

    private fun createPlaybackAudioDeviceModule(captureGeneration: Long): JavaAudioDeviceModule {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val recordFactory = ScreenMirrorPlaybackAudioRecordFactory()
        playbackAudioFactory = recordFactory
        return JavaAudioDeviceModule.builder(context.applicationContext)
            .setInputSampleRate(AUDIO_SAMPLE_RATE)
            .setUseStereoInput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordFactory(recordFactory)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    onPlaybackAudioError(captureGeneration, "init", errorMessage)
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?,
                ) {
                    onPlaybackAudioError(captureGeneration, "start", errorMessage)
                }

                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    onPlaybackAudioError(captureGeneration, "runtime", errorMessage)
                }
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() = post {
                    if (captureGeneration != generation || !_state.value.isActive) return@post
                    Log.i(TAG, "Device playback audio capture started")
                    _state.value = _state.value.copy(audioStatus = AudioStatus.ACTIVE, audioMessage = null)
                }

                override fun onWebRtcAudioRecordStop() {
                    Log.i(TAG, "Device playback audio capture stopped")
                }
            })
            .createAudioDeviceModule()
    }

    private fun onPlaybackAudioError(captureGeneration: Long, stage: String, detail: String?) = post {
        if (captureGeneration != generation || !_state.value.isActive) return@post
        Log.w(TAG, "Playback audio $stage error: ${detail ?: "unknown"}")
        markAudioUnavailable(captureGeneration, "Device audio stopped; mirroring video only.")
    }

    private fun markAudioUnavailable(captureGeneration: Long, message: String) {
        if (captureGeneration != generation || !_state.value.deviceAudioRequested) return
        audioTrack?.setEnabled(false)
        _state.value = _state.value.copy(audioStatus = AudioStatus.UNAVAILABLE, audioMessage = message)
    }

    private fun createOffer() {
        if (peerConnection != null || videoTrack == null) return
        val currentGeneration = generation
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val connection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                post {
                    if (!isCurrent(currentGeneration)) return@post
                    val id = sessionId ?: return@post
                    webSocketClient.send(
                        createScreenMirrorCandidateCommandJson(
                            id, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp,
                        ),
                    )
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                post {
                    if (!isCurrent(currentGeneration)) return@post
                    if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.CLOSED
                    ) {
                        fail("Direct connection to TV failed")
                    }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidateError(event: IceCandidateErrorEvent?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
            override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) = Unit
        }) ?: run { fail("WebRTC is unavailable on this phone"); return }
        peerConnection = connection
        Log.i(TAG, "Creating screen mirror offer generation=$currentGeneration")
        val streamIds = listOf("screen-$sessionId")
        connection.addTrack(videoTrack, streamIds)
        audioTrack?.takeIf { _state.value.audioStatus != AudioStatus.UNAVAILABLE }?.let {
            connection.addTrack(it, streamIds)
        }
        connection.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?.let { transceiver ->
                val h264 = factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                    .codecs.filter { it.name.equals("H264", ignoreCase = true) }
                if (h264.isNotEmpty()) transceiver.setCodecPreferences(h264)
                transceiver.sender.parameters = transceiver.sender.parameters.apply {
                    encodings.firstOrNull()?.let { encoding ->
                        encoding.minBitrateBps = 400_000
                        encoding.maxBitrateBps = quality.maxBitrateBps
                        encoding.maxFramerate = 30
                    }
                    degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                }
            }
        connection.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            ?.let { transceiver ->
                val opus = factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                    .codecs.filter { it.name.equals("opus", ignoreCase = true) }
                if (opus.isNotEmpty()) transceiver.setCodecPreferences(opus)
                transceiver.sender.parameters = transceiver.sender.parameters.apply {
                    encodings.firstOrNull()?.maxBitrateBps = AUDIO_MAX_BITRATE_BPS
                }
            }
        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) = post {
                if (!isCurrent(currentGeneration, connection)) return@post
                connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() = post {
                        if (!isCurrent(currentGeneration, connection)) return@post
                        val id = sessionId ?: return@post
                        Log.i(TAG, "Sending screen mirror offer generation=$currentGeneration")
                        webSocketClient.send(createScreenMirrorOfferJson(id, sdp.description))
                    }
                    override fun onSetFailure(error: String?) = post {
                        if (isCurrent(currentGeneration, connection)) fail("Could not prepare screen stream")
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = post {
                if (isCurrent(currentGeneration, connection)) fail("Could not create screen stream")
            }
        }, MediaConstraints())
    }

    private fun applyAnswer(sdp: String) {
        val connection = peerConnection ?: return
        val currentGeneration = generation
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = post {
                if (!isCurrent(currentGeneration, connection)) return@post
                answerApplied = true
                Log.i(TAG, "Applied screen mirror answer generation=$currentGeneration")
                pendingCandidates.toList().also { pendingCandidates.clear() }.forEach(connection::addIceCandidate)
            }
            override fun onSetFailure(error: String?) = post {
                if (isCurrent(currentGeneration, connection)) fail("TV rejected the screen stream")
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun addRemoteCandidate(message: IncomingMessage.ScreenMirrorCandidate) {
        val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
        if (answerApplied) peerConnection?.addIceCandidate(candidate)
        else if (pendingCandidates.size < MAX_PENDING_CANDIDATES) pendingCandidates += candidate
    }

    private fun fail(message: String) {
        val id = sessionId
        _state.value = State(Phase.FAILED, null, message)
        stopInternal(notifyReceiver = id != null, reason = "failed")
    }

    private fun stopInternal(notifyReceiver: Boolean, reason: String?) {
        val id = sessionId
        generation++
        timeoutJob?.cancel(); timeoutJob = null
        if (_state.value.phase != Phase.FAILED) _state.value = State()
        if (notifyReceiver && id != null) webSocketClient.send(createScreenMirrorStopJson(id, reason))
        val capture = capturer
        capturer = null
        val textureHelper = surfaceTextureHelper
        surfaceTextureHelper = null
        val connection = peerConnection
        peerConnection = null
        val localVideoTrack = videoTrack
        videoTrack = null
        val localAudioTrack = audioTrack
        audioTrack = null
        val localVideoSource = videoSource
        videoSource = null
        val localAudioSource = audioSource
        audioSource = null
        val peerFactory = factory
        factory = null
        val audioModule = audioDeviceModule
        audioDeviceModule = null
        val playbackFactory = playbackAudioFactory
        playbackAudioFactory = null
        val egl = eglBase
        eglBase = null

        Log.i(TAG, "Stopping screen mirror generation=$generation capturedFrames=${capture?.numCapturedFrames ?: 0}")
        cleanup("capture stop") { capture?.stopCapture() }
        cleanup("capture dispose") { capture?.dispose() }
        cleanup("capture texture dispose") { textureHelper?.dispose() }
        cleanup("peer connection dispose") { connection?.dispose() }
        cleanup("video track dispose") { localVideoTrack?.dispose() }
        cleanup("audio track dispose") { localAudioTrack?.dispose() }
        cleanup("video source dispose") { localVideoSource?.dispose() }
        cleanup("audio source dispose") { localAudioSource?.dispose() }
        cleanup("playback audio detach") { playbackFactory?.clearProjection() }
        cleanup("peer factory dispose") { peerFactory?.dispose() }
        cleanup("audio module release") { audioModule?.release() }
        cleanup("EGL release") { egl?.release() }
        pendingCandidates.clear()
        answerApplied = false
        sessionId = null
        Log.i(TAG, "Screen mirror teardown complete generation=$generation")
    }

    private inline fun cleanup(stage: String, block: () -> Unit) {
        Log.d(TAG, "Screen mirror teardown stage=$stage")
        runCatching(block).onFailure { Log.w(TAG, "Screen mirror $stage failed", it) }
    }

    private fun matches(id: String) = id == sessionId
    private fun isCurrent(expectedGeneration: Long, connection: PeerConnection? = null): Boolean =
        expectedGeneration == generation && sessionId != null &&
            (connection == null || peerConnection === connection)

    private fun post(block: () -> Unit) { handler.post(block) }

    private fun mirrorSize(metrics: DisplayMetrics, quality: Quality): Pair<Int, Int> =
        screenMirrorCaptureSize(metrics.widthPixels, metrics.heightPixels, quality)

    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        private const val TAG = "ScreenMirror"
        private const val MAX_PENDING_CANDIDATES = 128
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_MAX_BITRATE_BPS = 96_000
    }
}

internal fun screenMirrorCaptureSize(
    width: Int,
    height: Int,
    quality: ScreenMirrorCoordinator.Quality,
): Pair<Int, Int> {
    val safeWidth = width.coerceAtLeast(2)
    val safeHeight = height.coerceAtLeast(2)
    val longEdge = maxOf(safeWidth, safeHeight)
    val scale = minOf(1f, quality.maxLongEdge.toFloat() / longEdge)
    return (safeWidth * scale).roundToInt().toEvenDimension() to
        (safeHeight * scale).roundToInt().toEvenDimension()
}

private fun Int.toEvenDimension(): Int = coerceAtLeast(2).let { it - (it % 2) }
