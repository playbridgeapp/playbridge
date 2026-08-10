package com.playbridge.player.mirror

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createScreenMirrorAnswerJson
import com.playbridge.shared.protocol.createScreenMirrorCandidateJson
import com.playbridge.shared.protocol.createScreenMirrorEventJson
import com.playbridge.shared.protocol.createScreenMirrorReadyJson
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.RendererCommon
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.ThreadUtils
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicLong

/** One receive-only WebRTC mirror session. ServerService serializes all command entry points here. */
class ScreenMirrorReceiverController(
    context: Context,
    private val send: (String) -> Unit,
    private val onEnded: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("PB-ScreenMirrorReceiver").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var connection: PeerConnection? = null
    private var renderer: SurfaceViewRenderer? = null
    private var rendererSessionEnded: (() -> Unit)? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var remoteSet = false
    private var generation = 0L
    private val lifecycleEpoch = AtomicLong()
    private val pendingCandidates = mutableListOf<IceCandidate>()

    fun start(id: String) {
        lifecycleEpoch.incrementAndGet()
        post {
            if (sessionId != null && sessionId != id) {
                stopInternal("replaced", notifyPhone = true, keepRendererSession = true)
            }
            if (sessionId == id) return@post
            lifecycleEpoch.incrementAndGet()
            generation++
            sessionId = id
            try {
                initialise()
            } catch (t: Throwable) {
                Log.e(TAG, "WebRTC receiver initialization failed", t)
                failInternal("initialization_failed")
            }
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer, onSessionEnded: () -> Unit) = post {
        if (sessionId == null || eglBase == null) {
            val rejectedEpoch = lifecycleEpoch.get()
            Log.w(TAG, "Rejecting screen mirror renderer because no session is active")
            releaseRendererOnMain(view)
            mainHandler.post {
                if (lifecycleEpoch.get() == rejectedEpoch) onSessionEnded()
            }
            return@post
        }
        if (renderer === view) {
            rendererSessionEnded = onSessionEnded
            return@post
        }
        renderer?.let { old ->
            videoTrack?.removeSink(old)
            releaseRendererOnMain(old)
        }
        renderer = view
        rendererSessionEnded = onSessionEnded
        prepareRendererIfPossible()
    }

    fun detachRenderer(view: SurfaceViewRenderer) = post {
        if (renderer !== view) return@post
        videoTrack?.removeSink(view)
        releaseRendererOnMain(view)
        renderer = null
        rendererSessionEnded = null
    }

    fun handle(message: IncomingMessage) = post {
        when (message) {
            is IncomingMessage.ScreenMirrorOffer -> if (matches(message.sessionId)) applyOffer(message.sdp)
            is IncomingMessage.ScreenMirrorCandidate -> if (matches(message.sessionId)) addCandidate(message)
            is IncomingMessage.ScreenMirrorStop -> if (matches(message.sessionId)) stopInternal(message.reason ?: "stopped_by_phone", false)
            else -> Unit
        }
    }

    fun stop(reason: String = "stopped_by_tv") = post { stopInternal(reason, true) }

    fun destroy() = post {
        stopInternal("server_destroyed", notifyPhone = false)
        thread.quitSafely()
    }

    private fun initialise() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        val egl = EglBase.create()
        eglBase = egl
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setUseStereoOutput(false)
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e(TAG, "Audio output initialization failed: $errorMessage")
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?,
                ) {
                    Log.e(TAG, "Audio output start failed code=$errorCode message=$errorMessage")
                }

                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e(TAG, "Audio output failed: $errorMessage")
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    Log.i(TAG, "Screen mirror audio output started")
                }

                override fun onWebRtcAudioTrackStop() {
                    Log.i(TAG, "Screen mirror audio output stopped")
                }
            })
            .createAudioDeviceModule()
        audioDeviceModule = audioModule
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val currentGeneration = ++generation
        connection = factory!!.createPeerConnection(config, observer(currentGeneration))
        if (connection == null) failInternal("webrtc_unavailable")
        prepareRendererIfPossible()
    }

    private fun applyOffer(sdp: String) {
        val peer = connection ?: return
        val currentGeneration = generation
        Log.i(TAG, "Applying screen mirror offer generation=$currentGeneration")
        peer.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = post {
                if (!isCurrent(currentGeneration, peer)) return@post
                remoteSet = true
                pendingCandidates.toList().also { pendingCandidates.clear() }.forEach(peer::addIceCandidate)
                peer.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) = post {
                        if (!isCurrent(currentGeneration, peer)) return@post
                        peer.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() = post {
                                if (!isCurrent(currentGeneration, peer)) return@post
                                Log.i(TAG, "Sending screen mirror answer generation=$currentGeneration")
                                sessionId?.let { send(createScreenMirrorAnswerJson(it, sdp.description)) }
                            }
                            override fun onSetFailure(error: String?) = post {
                                if (isCurrent(currentGeneration, peer)) failInternal("answer_failed")
                            }
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String?) = post {
                        if (isCurrent(currentGeneration, peer)) failInternal("answer_failed")
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) = post {
                if (isCurrent(currentGeneration, peer)) failInternal("offer_rejected")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun addCandidate(message: IncomingMessage.ScreenMirrorCandidate) {
        val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
        if (remoteSet) connection?.addIceCandidate(candidate)
        else if (pendingCandidates.size < MAX_PENDING_CANDIDATES) pendingCandidates += candidate
    }

    private fun observer(observerGeneration: Long) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            post {
                if (observerGeneration != generation) return@post
                val id = sessionId ?: return@post
                send(createScreenMirrorCandidateJson(id, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val track = transceiver?.receiver?.track()) {
                is VideoTrack -> post {
                    if (observerGeneration != generation) return@post
                    videoTrack?.let { old -> renderer?.let(old::removeSink) }
                    videoTrack = track
                    renderer?.let(track::addSink)
                }
                is AudioTrack -> post {
                    if (observerGeneration != generation) return@post
                    audioTrack = track
                    track.setEnabled(true)
                    Log.i(TAG, "Screen mirror audio track received")
                }
                else -> Unit
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            post {
                if (observerGeneration != generation) return@post
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> sessionId?.let {
                        Log.i(TAG, "Screen mirror peer connected generation=$observerGeneration")
                        send(createScreenMirrorEventJson(it, "connected"))
                    }
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> failInternal("connection_failed")
                    else -> Unit
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
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver?) = Unit
    }

    private fun stopInternal(
        reason: String,
        notifyPhone: Boolean,
        keepRendererSession: Boolean = false,
    ) {
        if (sessionId == null && connection == null && factory == null && eglBase == null) return
        val id = sessionId
        generation++
        val stoppedEpoch = lifecycleEpoch.incrementAndGet()
        Log.i(TAG, "Stopping screen mirror reason=$reason notifyPhone=$notifyPhone")
        if (notifyPhone && id != null) send(createScreenMirrorEventJson(id, "stopped", reason))
        videoTrack?.let { track -> renderer?.let(track::removeSink) }; videoTrack = null
        audioTrack = null
        renderer?.let(::releaseRendererOnMain)
        val endRendererSession = if (keepRendererSession) null else rendererSessionEnded
        if (!keepRendererSession) {
            renderer = null
            rendererSessionEnded = null
        }
        connection?.dispose(); connection = null
        factory?.dispose(); factory = null
        audioDeviceModule?.release(); audioDeviceModule = null
        eglBase?.release(); eglBase = null
        pendingCandidates.clear()
        remoteSet = false
        sessionId = null
        if (!keepRendererSession) {
            mainHandler.post {
                if (lifecycleEpoch.get() != stoppedEpoch) {
                    Log.d(TAG, "Ignoring stale screen mirror end callback epoch=$stoppedEpoch")
                    return@post
                }
                endRendererSession?.invoke()
                onEnded()
            }
        }
    }

    private fun failInternal(reason: String) {
        sessionId?.let { send(createScreenMirrorEventJson(it, "failed", reason)) }
        stopInternal(reason, notifyPhone = false)
    }

    private fun matches(id: String) = id == sessionId
    private fun isCurrent(expectedGeneration: Long, peer: PeerConnection): Boolean =
        expectedGeneration == generation && connection === peer && sessionId != null

    private fun post(block: () -> Unit) { handler.post(block) }

    private fun prepareRendererIfPossible() {
        val view = renderer ?: return
        val egl = eglBase ?: return
        try {
            ThreadUtils.invokeAtFrontUninterruptibly(mainHandler) {
                view.init(egl.eglBaseContext, null)
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                view.setEnableHardwareScaler(true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to initialize screen mirror renderer", t)
            failInternal("renderer_init_failed")
            return
        }
        videoTrack?.addSink(view)
        sessionId?.let {
            Log.i(TAG, "Screen mirror renderer ready")
            send(createScreenMirrorReadyJson(it))
        }
    }

    private fun releaseRendererOnMain(view: SurfaceViewRenderer) {
        runCatching {
            ThreadUtils.invokeAtFrontUninterruptibly(mainHandler) { view.release() }
        }.onFailure { Log.w(TAG, "Screen mirror renderer release failed", it) }
    }

    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        private const val MAX_PENDING_CANDIDATES = 128
        private const val TAG = "ScreenMirrorReceiver"
    }
}
