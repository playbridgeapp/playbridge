package com.playbridge.sender.cast.googlecast

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Lightweight CastV2 protocol client.
 *
 * The CastV2 protocol frames messages as: [4-byte big-endian length][protobuf CastMessage].
 * Rather than pulling in a full protobuf dependency, we manually build and parse the
 * CastMessage wire format — it has a fixed, simple structure:
 *
 *   field 1 (varint)  : protocol_version = 0 (CASTV2_1_0)
 *   field 2 (string)  : source_id
 *   field 3 (string)  : destination_id
 *   field 4 (string)  : namespace
 *   field 5 (varint)  : payload_type = 0 (STRING)
 *   field 6 (string)  : payload_utf8
 *
 * All playback control is done through JSON payloads in the
 * `urn:x-cast:com.google.cast.media` namespace.
 */
class CastV2Client {

    companion object {
        private const val TAG = "CastV2Client"
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

        // CastV2 namespaces
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        private const val SENDER_ID = "sender-0"
        private const val RECEIVER_ID = "receiver-0"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var requestId = 1

    /** The transport ID of the launched media receiver session. */
    @Volatile var transportId: String? = null
        private set

    /** The session ID of the launched media receiver session. */
    @Volatile var sessionId: String? = null
        private set

    /** The media session ID of the currently loaded media. */
    @Volatile var mediaSessionId: Int = 0
        private set

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Connect to the Chromecast device over TLS on the given port (default 8009).
     * Chromecasts use self-signed certificates, so we trust all certs.
     */
    @Throws(IOException::class)
    fun connect(host: String, port: Int = 8009) {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true)
        sslSocket.soTimeout = READ_TIMEOUT_MS
        socket = sslSocket
        output = DataOutputStream(sslSocket.getOutputStream())
        input = DataInputStream(sslSocket.getInputStream())

        // Open a virtual connection to the receiver
        sendMessage(RECEIVER_ID, NS_CONNECTION, JSONObject().apply {
            put("type", "CONNECT")
        })
        Log.d(TAG, "Connected to $host:$port")
    }

    /** Launch the Default Media Receiver app (or attach/reuse if already running). */
    fun launchApp(appId: String = DEFAULT_MEDIA_RECEIVER_APP_ID): Boolean {
        val reqId = nextRequestId()
        // 1. Send GET_STATUS
        sendMessage(RECEIVER_ID, NS_RECEIVER, JSONObject().apply {
            put("type", "GET_STATUS")
            put("requestId", reqId)
        })

        var destId = ""
        var sessId = ""
        var isActiveApp = false

        // Wait up to 2 seconds for status
        val statusDeadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < statusDeadline) {
            val msg = readMessage() ?: continue
            handleHeartbeat(msg)
            if (msg.namespace == NS_RECEIVER) {
                val json = JSONObject(msg.payload)
                if (json.optString("type") == "RECEIVER_STATUS") {
                    val statusObj = json.optJSONObject("status")
                    val apps = statusObj?.optJSONArray("applications")
                    if (apps != null && apps.length() > 0) {
                        for (i in 0 until apps.length()) {
                            val app = apps.getJSONObject(i)
                            val sid = app.optString("sessionId")
                            if (sid.isNotEmpty()) {
                                sessId = sid
                            }
                            if (app.optString("appId") == appId) {
                                val tid = app.optString("transportId")
                                if (tid.isNotEmpty()) {
                                    destId = tid
                                }
                                val isIdle = app.optBoolean("isIdleScreen", false)
                                if (destId.isNotEmpty() && !isIdle) {
                                    isActiveApp = true
                                }
                            }
                        }
                    }
                    if (sessId.isNotEmpty() || isActiveApp) {
                        break
                    }
                }
            }
        }

        if (isActiveApp && destId.isNotEmpty()) {
            transportId = destId
            sessionId = sessId
            Log.d(TAG, "Reusing active application transportId=$destId sessionId=$sessId")
            // Connect to the media session transport
            sendMessage(destId, NS_CONNECTION, JSONObject().apply {
                put("type", "CONNECT")
            })
            return true
        }

        // Otherwise launch it
        // Stop lingering stale session if any
        if (sessId.isNotEmpty()) {
            sendMessage(RECEIVER_ID, NS_RECEIVER, JSONObject().apply {
                put("type", "STOP")
                put("sessionId", sessId)
                put("requestId", nextRequestId())
            })
            try { Thread.sleep(300) } catch (e: InterruptedException) {}
        }

        // Launch
        sendMessage(RECEIVER_ID, NS_RECEIVER, JSONObject().apply {
            put("type", "LAUNCH")
            put("appId", appId)
            put("requestId", nextRequestId())
        })

        transportId = null
        sessionId = null
        val launchDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < launchDeadline) {
            val msg = readMessage() ?: continue
            handleHeartbeat(msg)
            if (msg.namespace == NS_RECEIVER) {
                val json = JSONObject(msg.payload)
                if (json.optString("type") == "RECEIVER_STATUS") {
                    val statusObj = json.optJSONObject("status")
                    val apps = statusObj?.optJSONArray("applications")
                    if (apps != null && apps.length() > 0) {
                        for (i in 0 until apps.length()) {
                            val app = apps.getJSONObject(i)
                            if (app.optString("appId") == appId) {
                                val tid = app.optString("transportId")
                                val sid = app.optString("sessionId")
                                if (tid.isNotEmpty()) {
                                    transportId = tid
                                    sessionId = sid
                                    Log.d(TAG, "Launched application transportId=$tid sessionId=$sid")
                                    sendMessage(tid, NS_CONNECTION, JSONObject().apply {
                                        put("type", "CONNECT")
                                    })
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Load media on the launched receiver. Returns true if the receiver acknowledged.
     *
     * @param contentUrl   URL for the Chromecast to fetch (must be reachable from the LAN)
     * @param contentType  MIME type (e.g. "video/mp4", "application/x-mpegURL")
     * @param title        Optional title for the media metadata
     * @param artUrl       Optional artwork URL
     * @param startSeconds Resume position in seconds (0 = start)
     */
    fun loadMedia(
        contentUrl: String,
        contentType: String? = null,
        title: String? = null,
        artUrl: String? = null,
        startSeconds: Double = 0.0,
    ): Boolean {
        val tid = transportId ?: return false
        val id = nextRequestId()
        val media = JSONObject().apply {
            put("contentId", contentUrl)
            contentType?.let { put("contentType", it) }
            put("streamType", "BUFFERED")
            val metadata = JSONObject().apply {
                put("type", 0) // GenericMediaMetadata
                title?.let { put("title", it) }
                artUrl?.let {
                    put("images", JSONArray().put(JSONObject().apply { put("url", it) }))
                }
            }
            put("metadata", metadata)
        }
        sendMessage(tid, NS_MEDIA, JSONObject().apply {
            put("type", "LOAD")
            put("media", media)
            put("autoplay", true)
            put("currentTime", startSeconds)
            sessionId?.let { put("sessionId", it) }
            put("requestId", id)
        })

        // Wait for MEDIA_STATUS to confirm load
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: continue
            handleHeartbeat(msg)
            if (msg.namespace == NS_MEDIA) {
                val json = JSONObject(msg.payload)
                if (json.optString("type") == "MEDIA_STATUS") {
                    val statuses = json.optJSONArray("status")
                    if (statuses != null && statuses.length() > 0) {
                        mediaSessionId = statuses.getJSONObject(0).optInt("mediaSessionId", 0)
                        Log.d(TAG, "Media loaded, mediaSessionId=$mediaSessionId")
                        return true
                    }
                }
                // LOAD_FAILED or LOAD_CANCELLED
                if (json.optString("type") == "LOAD_FAILED" ||
                    json.optString("type") == "LOAD_CANCELLED"
                ) {
                    Log.w(TAG, "Load failed: ${json.optString("type")}")
                    return false
                }
            }
        }
        return false
    }

    /** Send a PLAY command to the media receiver. */
    fun play() {
        sendMediaCommand("PLAY")
    }

    /** Send a PAUSE command. */
    fun pause() {
        sendMediaCommand("PAUSE")
    }

    /** Send a STOP command (stops media, does not close the receiver app). */
    fun stopMedia() {
        sendMediaCommand("STOP")
    }

    /** Seek to a position in seconds. */
    fun seek(positionSeconds: Double) {
        val tid = transportId ?: return
        sendMessage(tid, NS_MEDIA, JSONObject().apply {
            put("type", "SEEK")
            put("mediaSessionId", mediaSessionId)
            put("currentTime", positionSeconds)
            put("requestId", nextRequestId())
        })
    }

    /** Set volume (0.0 – 1.0). */
    fun setVolume(level: Float) {
        sendMessage(RECEIVER_ID, NS_RECEIVER, JSONObject().apply {
            put("type", "SET_VOLUME")
            put("volume", JSONObject().apply { put("level", level.toDouble()) })
            put("requestId", nextRequestId())
        })
    }

    data class MediaStatus(
        val playerState: String, // IDLE, BUFFERING, PLAYING, PAUSED
        val currentTime: Double,
        val duration: Double,
        val mediaSessionId: Int,
    )

    /**
     * Request and read the current media status. Blocks until a MEDIA_STATUS
     * response is received or the timeout expires.
     */
    fun getMediaStatus(): MediaStatus? {
        val tid = transportId ?: return null
        sendMessage(tid, NS_MEDIA, JSONObject().apply {
            put("type", "GET_STATUS")
            put("requestId", nextRequestId())
        })
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: continue
            handleHeartbeat(msg)
            if (msg.namespace == NS_MEDIA) {
                val json = JSONObject(msg.payload)
                if (json.optString("type") == "MEDIA_STATUS") {
                    val statuses = json.optJSONArray("status")
                    if (statuses != null && statuses.length() > 0) {
                        val st = statuses.getJSONObject(0)
                        val sid = st.optInt("mediaSessionId", 0)
                        if (sid > 0) mediaSessionId = sid
                        return MediaStatus(
                            playerState = st.optString("playerState", "IDLE"),
                            currentTime = st.optDouble("currentTime", 0.0),
                            duration = st.optJSONObject("media")?.optDouble("duration", 0.0)
                                ?: 0.0,
                            mediaSessionId = sid,
                        )
                    }
                }
            }
        }
        return null
    }

    /** Send a heartbeat PONG. Must be called periodically to keep the connection alive. */
    fun pong() {
        sendMessage(RECEIVER_ID, NS_HEARTBEAT, JSONObject().apply {
            put("type", "PONG")
        })
    }

    /**
     * Pump incoming messages for up to [timeoutMs], responding to PINGs and
     * returning any MEDIA_STATUS update seen. Call from the poll loop.
     */
    fun pumpAndGetStatus(timeoutMs: Long = 2000): MediaStatus? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: continue
            handleHeartbeat(msg)
            if (msg.namespace == NS_MEDIA) {
                val json = JSONObject(msg.payload)
                if (json.optString("type") == "MEDIA_STATUS") {
                    val statuses = json.optJSONArray("status")
                    if (statuses != null && statuses.length() > 0) {
                        val st = statuses.getJSONObject(0)
                        val sid = st.optInt("mediaSessionId", 0)
                        if (sid > 0) mediaSessionId = sid
                        return MediaStatus(
                            playerState = st.optString("playerState", "IDLE"),
                            currentTime = st.optDouble("currentTime", 0.0),
                            duration = st.optJSONObject("media")?.optDouble("duration", 0.0)
                                ?: 0.0,
                            mediaSessionId = sid,
                        )
                    }
                }
            }
        }
        return null
    }

    fun stopSession() {
        val sessId = sessionId ?: return
        sendMessage(RECEIVER_ID, NS_RECEIVER, JSONObject().apply {
            put("type", "STOP")
            put("sessionId", sessId)
            put("requestId", nextRequestId())
        })
        sessionId = null
    }

    fun disconnect() {
        runCatching {
            stopSession()
        }
        runCatching {
            sendMessage(RECEIVER_ID, NS_CONNECTION, JSONObject().apply {
                put("type", "CLOSE")
            })
        }
        close()
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        transportId = null
        sessionId = null
        mediaSessionId = 0
    }

    // -----------------------------------------------------------------------
    // CastV2 wire protocol: hand-rolled protobuf framing
    // -----------------------------------------------------------------------

    private data class CastMessage(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payload: String,
    )

    private fun sendMessage(destinationId: String, namespace: String, json: JSONObject) {
        sendRaw(SENDER_ID, destinationId, namespace, json.toString())
    }

    private fun sendMediaCommand(type: String) {
        val tid = transportId ?: return
        sendMessage(tid, NS_MEDIA, JSONObject().apply {
            put("type", type)
            put("mediaSessionId", mediaSessionId)
            put("requestId", nextRequestId())
        })
    }

    /**
     * Build a CastMessage protobuf manually.
     *
     * Wire layout (proto2):
     *   1: varint  protocol_version (0)
     *   2: string  source_id
     *   3: string  destination_id
     *   4: string  namespace
     *   5: varint  payload_type (0 = STRING)
     *   6: string  payload_utf8
     *
     * String encoding: tag (field<<3 | 2), varint length, UTF-8 bytes
     * Varint encoding: tag (field<<3 | 0), value byte(s)
     */
    @Synchronized
    private fun sendRaw(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payload: String,
    ) {
        val out = output ?: return
        val body = buildCastMessageBytes(sourceId, destinationId, namespace, payload)
        // 4-byte big-endian length prefix
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array()
        try {
            out.write(lenBuf)
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            Log.w(TAG, "sendRaw failed: ${e.message}")
        }
    }

    /**
     * Read a single CastMessage from the TLS socket. Returns null on timeout or error.
     */
    private fun readMessage(): CastMessage? {
        val inp = input ?: return null
        return try {
            // 4-byte big-endian length
            val lenBuf = ByteArray(4)
            inp.readFully(lenBuf)
            val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > 1_000_000) return null
            val msgBuf = ByteArray(len)
            inp.readFully(msgBuf)
            parseCastMessage(msgBuf)
        } catch (e: java.net.SocketTimeoutException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "readMessage failed: ${e.message}")
            null
        }
    }

    private fun handleHeartbeat(msg: CastMessage) {
        if (msg.namespace == NS_HEARTBEAT) {
            val json = JSONObject(msg.payload)
            if (json.optString("type") == "PING") {
                sendMessage(msg.sourceId, NS_HEARTBEAT, JSONObject().apply {
                    put("type", "PONG")
                })
            }
        }
    }

    @Synchronized
    private fun nextRequestId(): Int = requestId++

    // -----------------------------------------------------------------------
    // Manual protobuf serialization / deserialization
    // -----------------------------------------------------------------------

    private fun buildCastMessageBytes(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payload: String,
    ): ByteArray {
        val buf = mutableListOf<Byte>()

        // field 1: protocol_version = 0 (varint)
        buf.addAll(encodeVarintField(1, 0))
        // field 2: source_id (string)
        buf.addAll(encodeStringField(2, sourceId))
        // field 3: destination_id (string)
        buf.addAll(encodeStringField(3, destinationId))
        // field 4: namespace (string)
        buf.addAll(encodeStringField(4, namespace))
        // field 5: payload_type = 0 (STRING) (varint)
        buf.addAll(encodeVarintField(5, 0))
        // field 6: payload_utf8 (string)
        buf.addAll(encodeStringField(6, payload))

        return buf.toByteArray()
    }

    private fun encodeVarintField(fieldNumber: Int, value: Int): List<Byte> {
        val result = mutableListOf<Byte>()
        // tag: (fieldNumber << 3) | 0 (varint wire type)
        result.addAll(encodeVarint((fieldNumber shl 3) or 0))
        result.addAll(encodeVarint(value))
        return result
    }

    private fun encodeStringField(fieldNumber: Int, value: String): List<Byte> {
        val result = mutableListOf<Byte>()
        val bytes = value.toByteArray(Charsets.UTF_8)
        // tag: (fieldNumber << 3) | 2 (length-delimited wire type)
        result.addAll(encodeVarint((fieldNumber shl 3) or 2))
        result.addAll(encodeVarint(bytes.size))
        result.addAll(bytes.toList())
        return result
    }

    private fun encodeVarint(value: Int): List<Byte> {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result
    }

    /**
     * Parse a CastMessage from raw protobuf bytes. We only need fields 2-6.
     */
    private fun parseCastMessage(data: ByteArray): CastMessage? {
        var pos = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadUtf8 = ""

        while (pos < data.size) {
            val (tag, newPos) = decodeVarint(data, pos)
            pos = newPos
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                0 -> { // varint
                    val (_, np) = decodeVarint(data, pos)
                    pos = np
                }
                2 -> { // length-delimited
                    val (len, np) = decodeVarint(data, pos)
                    pos = np
                    val strBytes = data.copyOfRange(pos, (pos + len).coerceAtMost(data.size))
                    pos += len
                    when (fieldNumber) {
                        2 -> sourceId = String(strBytes, Charsets.UTF_8)
                        3 -> destinationId = String(strBytes, Charsets.UTF_8)
                        4 -> namespace = String(strBytes, Charsets.UTF_8)
                        6 -> payloadUtf8 = String(strBytes, Charsets.UTF_8)
                    }
                }
                else -> break // unknown wire type, stop parsing
            }
        }

        return if (namespace.isNotEmpty() && payloadUtf8.isNotEmpty()) {
            CastMessage(sourceId, destinationId, namespace, payloadUtf8)
        } else null
    }

    /** Decode a varint at [offset], returning (value, newOffset). */
    private fun decodeVarint(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }
}
