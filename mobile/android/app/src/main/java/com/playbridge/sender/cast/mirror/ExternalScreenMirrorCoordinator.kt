package com.playbridge.sender.cast.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.getSystemService
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the phone-side capture used by third-party receivers. One H.264/MPEG-TS
 * producer feeds both a live HLS view for Google Cast and a continuous TS view
 * for DLNA; capture is never duplicated.
 */
class ExternalScreenMirrorCoordinator(
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    data class Urls(
        val hls: String,
        val continuousTs: String,
        val hasAudio: Boolean = false,
    )

    private val _state = MutableStateFlow(ScreenMirrorCoordinator.State())
    val state = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var listener: ServerSocket? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var encoder: MediaCodecMpegTsEncoder? = null
    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var framePacer: MirrorFramePacer? = null
    private var streamHub: LiveMpegTsHub? = null
    private var startupTimeoutJob: Job? = null
    private var generation = 0L
    private var projectionCallback: MediaProjection.Callback? = null
    private var sourceSize: Pair<Int, Int>? = null
    @Volatile
    private var transportHasAudio = false

    /** Called only after CastSessionService entered the mediaProjection FGS type. */
    fun start(
        projectionPermission: Intent,
        options: ScreenMirrorCoordinator.Options,
        receiverHost: String,
        waitForHlsSegment: Boolean,
        onReady: (Urls) -> Unit,
    ) {
        if (_state.value.isActive) return
        transportHasAudio = false
        val currentGeneration = ++generation
        _state.value = ScreenMirrorCoordinator.State(
            phase = ScreenMirrorCoordinator.Phase.STARTING,
            sessionId = UUID.randomUUID().toString(),
            message = "Preparing screen capture…",
            deviceAudioRequested = options.deviceAudio,
            audioStatus = if (options.deviceAudio) {
                ScreenMirrorCoordinator.AudioStatus.STARTING
            } else {
                ScreenMirrorCoordinator.AudioStatus.DISABLED
            },
        )
        startupTimeoutJob?.cancel()
        startupTimeoutJob = appScope.launch {
            delay(STARTUP_TIMEOUT_MS)
            if (currentGeneration == generation &&
                _state.value.phase == ScreenMirrorCoordinator.Phase.STARTING
            ) {
                fail("Screen encoder did not produce a playable stream")
            }
        }
        appScope.launch(Dispatchers.IO) {
            try {
                startCapture(
                    currentGeneration,
                    projectionPermission,
                    options,
                    receiverHost,
                    waitForHlsSegment,
                    onReady,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start external screen mirror", error)
                if (currentGeneration == generation) {
                    fail(error.message ?: "Unable to start screen mirroring")
                }
            }
        }
    }

    fun markMirroring() {
        if (_state.value.isActive) {
            _state.value = _state.value.copy(
                phase = ScreenMirrorCoordinator.Phase.MIRRORING,
                message = null,
            )
        }
    }

    fun stop(reason: String = "stopped_by_phone") {
        Log.i(TAG, "Stopping external screen mirror reason=$reason")
        stopInternal(preserveFailure = false)
    }

    fun fail(message: String) {
        if (!_state.value.isActive) return
        _state.value = ScreenMirrorCoordinator.State(
            phase = ScreenMirrorCoordinator.Phase.FAILED,
            message = message,
        )
        stopInternal(preserveFailure = true)
    }

    @Synchronized
    private fun startCapture(
        captureGeneration: Long,
        permission: Intent,
        options: ScreenMirrorCoordinator.Options,
        receiverHost: String,
        waitForHlsSegment: Boolean,
        onReady: (Urls) -> Unit,
    ) {
        val host = localIpv4ForReceiver(context, receiverHost)
            ?: throw IOException("No Wi-Fi or Ethernet address can reach this receiver")
        val worker = Executors.newCachedThreadPool()
        executor = worker
        val server = ServerSocket(0)
        listener = server
        val token = UUID.randomUUID().toString()
        val basePath = "/screen/$token"
        val baseUrl = "http://$host:${server.localPort}$basePath"

        val pipes = ParcelFileDescriptor.createPipe()
        pipeRead = pipes[0]
        pipeWrite = pipes[1]
        val hub = LiveMpegTsHub(
            input = BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipes[0])),
            executor = worker,
            waitForHlsSegment = waitForHlsSegment,
            onReady = {
                if (captureGeneration == generation && _state.value.isActive) {
                    startupTimeoutJob?.cancel()
                    startupTimeoutJob = null
                    _state.value = _state.value.copy(
                        phase = ScreenMirrorCoordinator.Phase.CONNECTING,
                        message = "Waiting for receiver…",
                    )
                    onReady(
                        Urls(
                            hls = "$baseUrl/index.m3u8",
                            continuousTs = "$baseUrl/stream.ts",
                            hasAudio = transportHasAudio,
                        ),
                    )
                }
            },
        )
        streamHub = hub
        hub.start()
        worker.execute { acceptLoop(server, hub, basePath) }

        val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
        val activeProjection = checkNotNull(
            projectionManager.getMediaProjection(Activity.RESULT_OK, permission),
        ) { "MediaProjection permission was rejected" }
        projection = activeProjection

        val metrics = context.resources.displayMetrics
        val (sourceWidth, sourceHeight) = screenMirrorCaptureSize(
            metrics.widthPixels,
            metrics.heightPixels,
            options.quality,
        )
        val (outputWidth, outputHeight) = externalMirrorEncoderSize(sourceWidth, sourceHeight)
        sourceSize = sourceWidth to sourceHeight
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (captureGeneration == generation && _state.value.isActive) {
                        fail("Screen capture permission was revoked")
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                resizeCapture(
                    captureGeneration = captureGeneration,
                    width = width,
                    height = height,
                    densityDpi = metrics.densityDpi,
                    quality = options.quality,
                )
            }
        }
        projectionCallback = callback
        activeProjection.registerCallback(callback, mainHandler)
        val localEncoder = MediaCodecMpegTsEncoder(
            width = outputWidth,
            height = outputHeight,
            bitrateBps = options.quality.maxBitrateBps,
            framesPerSecond = FRAME_RATE,
            projection = activeProjection,
            deviceAudio = options.deviceAudio,
            output = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]),
            onError = { error ->
                Log.e(TAG, "Screen encoder stopped unexpectedly", error)
                mainHandler.post {
                    if (shouldHandleExternalMirrorCallback(
                            captureGeneration,
                            generation,
                            _state.value.isActive,
                        )
                    ) {
                        fail("Screen encoder stopped unexpectedly")
                    }
                }
            },
            onAudioActive = {
                mainHandler.post {
                    if (captureGeneration == generation && _state.value.isActive) {
                        _state.value = _state.value.copy(
                            audioStatus = ScreenMirrorCoordinator.AudioStatus.ACTIVE,
                            audioMessage = null,
                        )
                    }
                }
            },
            onAudioError = { error ->
                Log.w(TAG, "Device audio capture unavailable; continuing video-only", error)
                mainHandler.post {
                    if (captureGeneration == generation && _state.value.isActive) {
                        _state.value = _state.value.copy(
                            audioStatus = ScreenMirrorCoordinator.AudioStatus.UNAVAILABLE,
                            audioMessage = "Device audio is unavailable. Mirroring video only.",
                        )
                    }
                }
            },
        )
        encoder = localEncoder
        transportHasAudio = localEncoder.hasAudio
        if (options.deviceAudio && !localEncoder.hasAudio) {
            _state.value = _state.value.copy(
                audioStatus = ScreenMirrorCoordinator.AudioStatus.UNAVAILABLE,
                audioMessage = "Device audio is unavailable. Mirroring video only.",
            )
        }
        val pacer = MirrorFramePacer(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            inputWidth = sourceWidth,
            inputHeight = sourceHeight,
            framesPerSecond = FRAME_RATE,
            outputSurface = localEncoder.inputSurface,
            onError = { error ->
                Log.e(TAG, "Mirror frame renderer failed", error)
                mainHandler.post {
                    if (shouldHandleExternalMirrorCallback(
                            captureGeneration,
                            generation,
                            _state.value.isActive,
                        )
                    ) {
                        fail("Screen renderer stopped unexpectedly")
                    }
                }
            },
        )
        framePacer = pacer
        display = activeProjection.createVirtualDisplay(
            "PlayBridgeExternalScreenMirror",
            sourceWidth,
            sourceHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            pacer.inputSurface,
            null,
            null,
        )
        localEncoder.start()
        pacer.start()
        Log.i(
            TAG,
            "External mirror capture started source=${sourceWidth}x$sourceHeight " +
                "output=${outputWidth}x$outputHeight host=$host",
        )
    }

    @Synchronized
    private fun resizeCapture(
        captureGeneration: Long,
        width: Int,
        height: Int,
        densityDpi: Int,
        quality: ScreenMirrorCoordinator.Quality,
    ) {
        if (captureGeneration != generation || !_state.value.isActive) return
        val (captureWidth, captureHeight) = screenMirrorCaptureSize(width, height, quality)
        if (sourceSize == (captureWidth to captureHeight)) return
        sourceSize = captureWidth to captureHeight
        framePacer?.resizeInput(captureWidth, captureHeight)
        runCatching {
            display?.resize(captureWidth, captureHeight, densityDpi)
        }.onFailure {
            Log.w(TAG, "Unable to resize external mirror capture", it)
        }
        Log.i(TAG, "External mirror source resized to ${captureWidth}x$captureHeight")
    }

    @Synchronized
    private fun stopInternal(preserveFailure: Boolean) {
        generation++
        startupTimeoutJob?.cancel()
        startupTimeoutJob = null
        val encoderToRelease = encoder
        encoder = null
        display?.release()
        display = null
        framePacer?.close()
        framePacer = null
        sourceSize = null
        transportHasAudio = false
        projectionCallback?.let { callback ->
            projection?.unregisterCallback(callback)
        }
        projectionCallback = null
        projection?.stop()
        projection = null
        runCatching { listener?.close() }
        listener = null
        streamHub?.close()
        streamHub = null
        runCatching { pipeRead?.close() }
        pipeRead = null
        runCatching { pipeWrite?.close() }
        pipeWrite = null
        executor?.shutdownNow()
        executor = null
        if (!preserveFailure) _state.value = ScreenMirrorCoordinator.State()
        encoderToRelease?.let { releaseEncoderOffMainThread(it) }
    }

    private fun releaseEncoderOffMainThread(encoder: MediaCodecMpegTsEncoder) {
        Thread(
            { runCatching { encoder.close() }.onFailure { Log.w(TAG, "Encoder release failed", it) } },
            "PlayBridgeExternalMirrorStop",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket, hub: LiveMpegTsHub, basePath: String) {
        try {
            while (!server.isClosed) {
                val socket = server.accept()
                executor?.execute { serveRequest(socket, hub, basePath) } ?: socket.close()
            }
        } catch (_: IOException) {
            // Expected during teardown.
        }
    }

    private fun serveRequest(socket: Socket, hub: LiveMpegTsHub, basePath: String) {
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine().orEmpty()
            var header: String?
            do {
                header = reader.readLine()
            } while (!header.isNullOrEmpty())
            val request = requestLine.split(' ')
            val method = request.getOrNull(0)
            val path = request.getOrNull(1)?.substringBefore('?')
            when {
                method == "OPTIONS" && path?.startsWith(basePath) == true ->
                    respond(socket, MirrorHttpResponse.options())
                method !in setOf("GET", "HEAD") ->
                    respond(socket, MirrorHttpResponse.empty(405, "Method Not Allowed", allow = true))
                path == "$basePath/index.m3u8" -> {
                    val manifest = hub.manifest()
                    if (manifest == null) {
                        respond(socket, MirrorHttpResponse.empty(503, "Stream Not Ready"))
                    } else {
                        respond(socket, MirrorHttpResponse.bytes("application/x-mpegURL", manifest, method == "HEAD"))
                    }
                }
                path == "$basePath/stream.ts" -> {
                    if (method == "HEAD") {
                        respond(socket, MirrorHttpResponse.streamingTs())
                    } else {
                        val output = socket.getOutputStream()
                        output.write(MirrorHttpResponse.streamingTs())
                        output.flush()
                        socket.soTimeout = 0
                        hub.addContinuousClient(socket)
                    }
                }
                path?.startsWith("$basePath/segment-") == true && path.endsWith(".ts") -> {
                    val id = path.substringAfterLast("segment-").substringBefore(".ts").toLongOrNull()
                    val bytes = id?.let(hub::segment)
                    if (bytes == null) {
                        respond(socket, MirrorHttpResponse.empty(404, "Not Found"))
                    } else {
                        respond(socket, MirrorHttpResponse.bytes("video/mp2t", bytes, method == "HEAD"))
                    }
                }
                else -> respond(socket, MirrorHttpResponse.empty(404, "Not Found"))
            }
        } catch (_: IOException) {
            runCatching { socket.close() }
        }
    }

    private fun respond(socket: Socket, response: ByteArray) {
        socket.use { it.getOutputStream().write(response) }
    }

    private companion object {
        private const val TAG = "ExternalScreenMirror"
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val FRAME_RATE = 24
        private const val REQUEST_TIMEOUT_MS = 5_000
    }
}

internal fun shouldHandleExternalMirrorCallback(
    callbackGeneration: Long,
    currentGeneration: Long,
    isActive: Boolean,
): Boolean = isActive && callbackGeneration == currentGeneration

internal data class MirrorHlsSegment(
    val id: Long,
    val durationSeconds: Double,
    val bytes: ByteArray = ByteArray(0),
)

internal fun buildMirrorHlsManifest(segments: List<MirrorHlsSegment>): ByteArray? {
    if (segments.isEmpty()) return null
    val targetDuration = ceil(segments.maxOf { it.durationSeconds }).toInt().coerceAtLeast(1)
    return buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:$targetDuration")
        appendLine("#EXT-X-MEDIA-SEQUENCE:${segments.first().id}")
        appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        segments.forEach { segment ->
            appendLine("#EXTINF:${"%.3f".format(java.util.Locale.US, segment.durationSeconds)},")
            appendLine("segment-${segment.id}.ts")
        }
    }.toByteArray(Charsets.UTF_8)
}

/** Reads the recorder pipe once and exposes bounded HLS and continuous-TS views. */
private class LiveMpegTsHub(
    private val input: BufferedInputStream,
    private val executor: ExecutorService,
    private val waitForHlsSegment: Boolean,
    private val onReady: () -> Unit,
) {
    private val clients = linkedMapOf<Socket, MirrorClientBuffer>()
    private val joinBuffer = MpegTsJoinBuffer(MAX_GROUP_BYTES)
    private val segments = ArrayDeque<MirrorHlsSegment>()
    private var nextSegmentId = 0L
    private var readySent = false
    @Volatile private var closed = false

    fun start() {
        executor.execute {
            val buffer = ByteArray(32 * 1024)
            try {
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    var becameReady = false
                    synchronized(this) {
                        val chunk = joinBuffer.consume(buffer, count)
                        if (chunk.isNotEmpty()) {
                            val failed = clients.filter { (_, queue) -> !queue.offer(chunk) }
                            failed.keys.forEach(::removeClient)
                        }
                        joinBuffer.drainCompletedSegments().forEach { completed ->
                            segments += MirrorHlsSegment(
                                id = nextSegmentId++,
                                durationSeconds = completed.durationSeconds,
                                bytes = completed.bytes,
                            )
                            while (segments.size > RETAINED_SEGMENTS) segments.removeFirst()
                        }
                        val transportReady = if (waitForHlsSegment) {
                            segments.isNotEmpty()
                        } else {
                            joinBuffer.isReadyForJoin()
                        }
                        if (!readySent && transportReady) {
                            readySent = true
                            becameReady = true
                        }
                    }
                    if (becameReady) onReady()
                }
            } catch (_: IOException) {
                // Expected during teardown.
            } finally {
                close()
            }
        }
    }

    @Synchronized
    fun manifest(): ByteArray? =
        buildMirrorHlsManifest(segments.takeLast(PLAYLIST_SEGMENTS))

    @Synchronized
    fun segment(id: Long): ByteArray? = segments.firstOrNull { it.id == id }?.bytes

    @Synchronized
    fun addContinuousClient(socket: Socket) {
        if (closed) {
            socket.close()
            return
        }
        val initial = joinBuffer.snapshot()
        val queue = MirrorClientBuffer(CLIENT_QUEUE_BYTES)
        clients[socket] = queue
        executor.execute {
            try {
                val output = socket.getOutputStream()
                output.write(initial)
                output.flush()
                while (!closed && !socket.isClosed) {
                    val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    output.write(chunk)
                    output.flush()
                }
            } catch (_: IOException) {
                // Receiver disconnected.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(this) { removeClient(socket) }
            }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        clients.keys.toList().forEach(::removeClient)
        segments.clear()
    }

    private fun removeClient(socket: Socket) {
        clients.remove(socket)?.close()
        runCatching { socket.close() }
    }

    private companion object {
        private const val TS_PACKET_BYTES = 188
        private const val MAX_GROUP_BYTES = TS_PACKET_BYTES * 48_000
        private const val CLIENT_QUEUE_BYTES = 512 * 1024
        private const val RETAINED_SEGMENTS = 8
        private const val PLAYLIST_SEGMENTS = 6
    }
}

internal class MirrorClientBuffer(private val capacityBytes: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger()
    @Volatile private var closed = false

    fun offer(chunk: ByteArray): Boolean {
        if (closed || chunk.size > capacityBytes) return false
        while (true) {
            val current = queuedBytes.get()
            if (current + chunk.size > capacityBytes) return false
            if (queuedBytes.compareAndSet(current, current + chunk.size)) break
        }
        if (closed) {
            queuedBytes.addAndGet(-chunk.size)
            return false
        }
        queue.offer(chunk)
        return true
    }

    fun poll(timeout: Long, unit: TimeUnit): ByteArray? =
        queue.poll(timeout, unit)?.also { queuedBytes.addAndGet(-it.size) }

    fun close() {
        closed = true
        queue.clear()
        queuedBytes.set(0)
    }
}

internal object MirrorHttpResponse {
    private const val CORS =
        "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
            "Access-Control-Expose-Headers: Content-Type, Content-Length\r\n"

    fun streamingTs(): ByteArray = headers("video/mp2t", contentLength = null)

    fun bytes(contentType: String, body: ByteArray, headOnly: Boolean): ByteArray =
        headers(contentType, body.size) + if (headOnly) ByteArray(0) else body

    fun options(): ByteArray = (
        "HTTP/1.1 204 No Content\r\n" + CORS +
            "Access-Control-Max-Age: 86400\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    fun empty(status: Int, reason: String, allow: Boolean = false): ByteArray = buildString {
        append("HTTP/1.1 $status $reason\r\n")
        append(CORS)
        if (allow) append("Allow: GET, HEAD, OPTIONS\r\n")
        append("Content-Length: 0\r\nConnection: close\r\n\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun headers(contentType: String, contentLength: Int?): ByteArray = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: $contentType\r\n")
        append("Cache-Control: no-cache, no-store\r\n")
        append(CORS)
        if (contentLength != null) append("Content-Length: $contentLength\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray(Charsets.US_ASCII)
}

internal fun localIpv4ForReceiver(context: Context, receiverHost: String): String? {
    val connectivity = context.getSystemService<ConnectivityManager>() ?: return null
    val receiver = runCatching { InetAddress.getByName(receiverHost) }.getOrNull()
    val candidates = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val physical = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!physical || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
        val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        network to properties
    }
    val selected = candidates.firstOrNull { (_, properties) ->
        receiver != null && properties.routes.any { it.matches(receiver) }
    } ?: candidates.firstOrNull()
    return selected?.second?.linkAddresses
        ?.asSequence()
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        ?.sortedByDescending { it.isSiteLocalAddress }
        ?.firstOrNull()
        ?.hostAddress
}
