package com.playbridge.player.server

import java.net.InetAddress
import java.net.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import playbridge.ProgressWebhook
import playbridge.ProgressIdentity

internal fun progressIdentityJson(identity: ProgressIdentity): String = buildJsonObject {
    put("type", identity.type)
    put("contentId", identity.content_id)
    put("videoId", identity.video_id)
    identity.season?.let { put("season", it) }
    identity.episode?.let { put("episode", it) }
}.toString()

/** Session-only webhook state. Never serialized into intents, history, diagnostics, or logs. */
internal class PlaybackWebhook(
    private val send: (ProgressWebhook, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var config: ProgressWebhook? = null
    private var playbackId: String? = null
    private var retiredPlaybackId: String? = null
    private var itemId: String? = null
    private val identities = mutableMapOf<String, JsonObject>()
    private var last: JsonObject? = null
    private var lastState: String? = null
    private var lastSent = 0L
    private var finished = false

    @Synchronized
    fun configure(value: ProgressWebhook?): Boolean {
        stop()
        retiredPlaybackId = playbackId
        playbackId = null
        itemId = null
        identities.clear()
        last = null
        lastState = null
        finished = false
        config = value?.takeIf { validWebhook(it) }
        return value == null || config != null
    }

    @Synchronized
    fun observe(encoded: String) {
        if (config == null) return
        val status = runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull() ?: return
        val id = status.string("playbackId") ?: return
        if (id == retiredPlaybackId) return
        when (status.string("type")) {
            "playlist_status" -> {
                if (playbackId != null && id != playbackId) {
                    if (!finished) emit("stopped")
                    retiredPlaybackId = playbackId
                    last = null
                    lastState = null
                    itemId = null
                    finished = false
                    identities.clear()
                }
                playbackId = id
                val accepted = mutableMapOf<String, JsonObject>()
                status["items"]?.jsonArray?.forEach { entry ->
                    val item = entry.jsonObject
                    val key = item.string("itemId") ?: return@forEach
                    (item["progressIdentity"] as? JsonObject)?.takeIf(::validProgressIdentity)?.let { accepted[key] = it }
                }
                // Retain at most the outgoing item until its final status has been emitted.
                itemId?.let { current -> identities[current]?.let { accepted.putIfAbsent(current, it) } }
                identities.clear()
                identities.putAll(accepted)
            }
            "status" -> {
                if (playbackId != id) return
                val current = status.string("currentItemId") ?: return
                val state = status.string("state") ?: return
                if (itemId != null && itemId != current) {
                    if (!finished) emit("stopped")
                    last = null
                    lastState = null
                    finished = false
                    itemId = null
                }
                if (current !in identities) return
                if ((status["duration"]?.jsonPrimitive?.longOrNull ?: 0L) <= 0) {
                    if (current == itemId && !finished && (state == "ended" || state == "stopped")) {
                        emit(state)
                        finished = true
                    }
                    return
                }
                playbackId = id
                itemId = current
                val zeroDuringTeardown = (state == "ended" || state == "stopped") &&
                    (status["position"]?.jsonPrimitive?.longOrNull ?: 0L) == 0L &&
                    (last?.get("position")?.jsonPrimitive?.longOrNull ?: 0L) > 0L
                if (!zeroDuringTeardown) last = status
                if (finished) return
                when {
                    state == "ended" || state == "stopped" -> {
                        emit(state)
                        finished = true
                    }
                    state == "playing" && lastState != "playing" -> emit("started")
                    state == "paused" && lastState == "playing" -> emit("paused")
                    state == "playing" && clock() - lastSent >= 30_000 -> emit("progress")
                }
                // Buffering is not a user pause or a new playback start.
                if (state != "buffering") lastState = state
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!finished) emit("stopped")
        config = null
        finished = true
    }

    private fun emit(event: String) {
        val destination = config ?: return
        val status = last ?: return
        val identity = identities[itemId] ?: return
        val duration = status["duration"]?.jsonPrimitive?.longOrNull ?: return
        if (duration <= 0) return
        val now = clock()
        val body = buildJsonObject {
            put("version", 1)
            put("eventId", UUID.randomUUID().toString())
            put("playbackId", playbackId)
            put("itemId", itemId)
            put("event", event)
            put("content", identity)
            put("positionMs", (status["position"]?.jsonPrimitive?.longOrNull ?: 0).coerceAtLeast(0))
            put("durationMs", duration)
            put("occurredAt", Instant.ofEpochMilli(now).toString())
        }.toString()
        lastSent = now
        send(destination, body)
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun validProgressIdentity(value: JsonObject): Boolean =
    value.string("type") in setOf("movie", "series") &&
        listOf("contentId", "videoId").all { value.string(it)?.let { id -> id.isNotBlank() && id.length <= 256 } == true } &&
        listOf("season", "episode").all { key -> value[key]?.let { (it.jsonPrimitive.longOrNull ?: -1) >= 0 } ?: (value.string("type") == "movie") }

internal fun validWebhook(value: ProgressWebhook): Boolean {
    val url = value.url.toHttpUrlOrNull() ?: return false
    if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) {
        if (!runCatching { isPublicWebhookAddress(InetAddress.getByName(url.host)) }.getOrDefault(false)) return false
    }
    return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null &&
        url.host != "localhost" && !url.host.endsWith(".localhost") && !url.host.endsWith(".local") &&
        value.url.length <= 2048 && value.bearer_token.length in 1..4096 &&
        value.bearer_token.all { it.code in 33..126 }
}

/** Only global unicast addresses; evaluated by OkHttp DNS immediately before connection. */
internal fun isPublicWebhookAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99))) ||
            (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
            (a == 203 && b == 0 && c == 113))
    }
    // Restrict IPv6 to global unicast and exclude documentation, Teredo, and 6to4.
    return bytes.size == 16 && (bytes[0] and 0xe0) == 0x20 &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 0x02) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02)
}

internal class PlaybackWebhookTransport {
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        ThreadPoolExecutor.DiscardPolicy(),
    )
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || !addresses.all(::isPublicWebhookAddress)) {
                        throw java.net.UnknownHostException("Webhook requires public addresses")
                    }
                }
        })
        .build()

    fun send(config: ProgressWebhook, body: String) {
        val created = System.currentTimeMillis()
        executor.execute {
            if (System.currentTimeMillis() - created > 90_000) return@execute
            val url = config.url.toHttpUrlOrNull() ?: return@execute
            if (!validWebhook(config)) return@execute
            // Literal IPs bypass OkHttp DNS, so validate them here too.
            if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) {
                if (!runCatching { isPublicWebhookAddress(InetAddress.getByName(url.host)) }.getOrDefault(false)) return@execute
            }
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${config.bearer_token}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            repeat(2) {
                if (System.currentTimeMillis() - created > 90_000) return@execute
                val retry = runCatching {
                    client.newCall(request).execute().use { response ->
                        response.code == 429 || response.code >= 500
                    }
                }.getOrDefault(true)
                if (!retry) return@execute
            }
        }
    }

    fun close() { executor.shutdown() }
}
