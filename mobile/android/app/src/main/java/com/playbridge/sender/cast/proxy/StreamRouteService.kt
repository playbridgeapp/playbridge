package com.playbridge.sender.cast.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CastableMedia(
    val url: String,
    val headers: Map<String, String>? = null,
    val contentType: String? = null,
    val title: String? = null,
    val playlistBody: String? = null,
    val audioUrl: String? = null,
    val localUri: Uri? = null,
)

data class PackagedMedia(
    val url: String,
    val contentType: String?,
    val headers: Map<String, String>?,
)

class StreamRouteException(message: String) : Exception(message)

/**
 * Packages media for cast according to [StreamRouteMode].
 *
 * Via phone uses the embedded Rust stream proxy; Via proxy uses a remote
 * stream-proxy-rust `/register`; Direct passes origin URL + headers through.
 */
class StreamRouteService(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    suspend fun packageForCast(
        media: CastableMedia,
        mode: StreamRouteMode,
        settings: StreamProxySettings = StreamProxySettingsStore.load(context),
    ): PackagedMedia = withContext(Dispatchers.IO) {
        val exclusive = !media.playlistBody.isNullOrBlank()
        val local = media.localUri != null ||
            media.url.startsWith("content://") ||
            media.url.startsWith("file://")

        when {
            local && mode != StreamRouteMode.VIA_PHONE ->
                throw StreamRouteException("Local files must use Via phone")
            exclusive && mode == StreamRouteMode.DIRECT ->
                throw StreamRouteException("Use Via phone for this stream")
            exclusive && mode == StreamRouteMode.VIA_PROXY ->
                throw StreamRouteException("Use Via phone for exclusive playlists")
            mode == StreamRouteMode.VIA_PROXY && !settings.isRemoteConfigured ->
                throw StreamRouteException("Configure stream proxy in Settings")
        }

        when (mode) {
            StreamRouteMode.DIRECT -> packageDirect(media)
            StreamRouteMode.VIA_PHONE -> packageViaPhone(media)
            StreamRouteMode.VIA_PROXY -> packageViaRemote(media, settings)
        }
    }

    private fun packageDirect(media: CastableMedia): PackagedMedia {
        if (media.url.startsWith("data:")) {
            throw StreamRouteException("Data URIs cannot be cast Direct — use Via phone")
        }
        return PackagedMedia(
            url = media.url,
            contentType = media.contentType,
            headers = media.headers,
        )
    }

    private suspend fun packageViaPhone(media: CastableMedia): PackagedMedia {
        val services = PhoneSenderServices.get()
            ?: throw StreamRouteException("Embedded stream proxy is unavailable")
        val host = lanIpv4()
            ?: throw StreamRouteException("No LAN address for Via phone")

        if (!media.playlistBody.isNullOrBlank()) {
            val path = writePlaylistTemp(media.playlistBody)
            val registered = services.registerFile(
                host = host,
                path = path.absolutePath,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
            )
            return PackagedMedia(
                url = registered.url,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
                headers = null,
            )
        }

        val localUri = media.localUri
            ?: media.url.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { Uri.parse(it) }

        if (localUri != null) {
            val path = materializeLocalPath(localUri, media.contentType)
            val registered = services.registerFile(
                host = host,
                path = path.absolutePath,
                contentType = media.contentType,
            )
            return PackagedMedia(
                url = registered.url,
                contentType = media.contentType,
                headers = null,
            )
        }

        if (media.url.startsWith("data:")) {
            val body = decodeDataUri(media.url)
                ?: throw StreamRouteException("Unsupported data URI")
            val path = writePlaylistTemp(body)
            val registered = services.registerFile(
                host = host,
                path = path.absolutePath,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
            )
            return PackagedMedia(
                url = registered.url,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
                headers = null,
            )
        }

        val registered = services.registerUrl(
            host = host,
            url = media.url,
            headers = media.headers.orEmpty(),
        )
        return PackagedMedia(
            url = registered.url,
            contentType = media.contentType,
            headers = null,
        )
    }

    private fun packageViaRemote(
        media: CastableMedia,
        settings: StreamProxySettings,
    ): PackagedMedia {
        if (media.url.startsWith("data:")) {
            throw StreamRouteException("Data URIs cannot use Via proxy — use Via phone")
        }
        val base = settings.remoteBaseUrl.trimEnd('/')
        val token = URLEncoder.encode(settings.remotePassword, Charsets.UTF_8.name())
        val registerUrl = "$base/register?token=$token"
        val bodyJson = JSONObject().apply {
            put("url", media.url)
            put("headers", JSONObject().also { h ->
                media.headers.orEmpty().forEach { (k, v) -> h.put(k, v) }
            })
        }
        val request = Request.Builder()
            .url(registerUrl)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw StreamRouteException(
                    "Remote proxy register failed: HTTP ${response.code}",
                )
            }
            val text = response.body?.string().orEmpty()
            val json = JSONObject(text)
            val proxyUrl = json.optString("proxy_url").ifBlank {
                json.optString("encrypted_url")
            }
            if (proxyUrl.isBlank()) {
                throw StreamRouteException("Remote proxy returned no URL")
            }
            return PackagedMedia(
                url = proxyUrl,
                contentType = media.contentType,
                headers = null,
            )
        }
    }

    private fun writePlaylistTemp(body: String): File {
        val dir = File(context.cacheDir, "cast-proxy").apply { mkdirs() }
        val file = File(dir, "playlist-${UUID.randomUUID()}.m3u8")
        file.writeText(body)
        return file
    }

    private fun materializeLocalPath(uri: Uri, contentType: String?): File {
        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.isFile) return f
            }
        }
        val dir = File(context.cacheDir, "cast-proxy").apply { mkdirs() }
        val ext = when {
            contentType?.contains("mpegurl", true) == true -> ".m3u8"
            contentType?.contains("mp4", true) == true -> ".mp4"
            contentType?.contains("webm", true) == true -> ".webm"
            else -> ".bin"
        }
        val out = File(dir, "local-${UUID.randomUUID()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw StreamRouteException("Cannot open local media")
        return out
    }

    private fun decodeDataUri(url: String): String? {
        // data:application/x-mpegurl;base64,...
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val data = url.substring(comma + 1)
        return if (meta.contains(";base64", ignoreCase = true)) {
            String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        } else {
            java.net.URLDecoder.decode(data, Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val TAG = "StreamRouteService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        /** Prefer Wi-Fi site-local IPv4; skip VPN tunnels (same rules as LocalProxyServer). */
        fun lanIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { nif ->
                    val n = nif.name.orEmpty()
                    n.startsWith("tun") || n.startsWith("wg") ||
                        n.startsWith("ppp") || n.startsWith("ipsec")
                }
                .sortedByDescending { it.name.orEmpty().startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull().also {
            if (it == null) Log.w(TAG, "No site-local IPv4 for Via phone")
        }
    }
}
