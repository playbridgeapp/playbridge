package com.playbridge.sender.cast.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import com.playbridge.sender.BuildConfig
import com.playbridge.sender.cast.HlsSegmentHints
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
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
 * Via phone **remote** HTTP(S) uses the embedded Rust stream-proxy `/s/...` with
 * Android JNI upstream ([JniUpstreamHttpClient] / HttpURLConnection ≈ Media3).
 * Local `content://` files still use [DlnaProxyHolder]/[LocalProxyServer].
 * Via proxy uses a remote stream-proxy-rust `/register`; Direct passes origin URL
 * + headers through.
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

        val packaged = when (mode) {
            StreamRouteMode.DIRECT -> packageDirect(media)
            StreamRouteMode.VIA_PHONE -> packageViaPhone(media)
            StreamRouteMode.VIA_PROXY -> packageViaRemote(media, settings)
        }
        if (mode == StreamRouteMode.DIRECT) packaged else attachCastHlsHints(packaged, media)
    }

    /**
     * Attaches pb_hls_format/pb_hls_stream to packaged HLS URLs so Google Cast
     * loads can tell receivers the real segment container (e.g. MPEG-TS bytes
     * behind .jpg suffixes). Mirrors Desktop TvCastMediaPreparer.withHlsHints.
     */
    private suspend fun attachCastHlsHints(
        packaged: PackagedMedia,
        media: CastableMedia,
    ): PackagedMedia {
        val contentType = packaged.contentType ?: media.contentType
        if (!HlsSegmentHints.isHlsContentType(contentType)) return packaged
        val suppliedBody = media.playlistBody?.takeIf { it.isNotBlank() }
            ?: media.url.takeIf { it.startsWith("data:") }?.let { decodeDataUri(it) }
        val evidence = HlsSegmentHints.probe(
            url = media.url,
            headers = ensureProxyUpstreamHeaders(media.headers.orEmpty()),
            suppliedBody = suppliedBody,
        )
        val hinted = HlsSegmentHints.withHints(
            url = packaged.url,
            format = evidence?.format,
            streamType = evidence?.streamType ?: HlsSegmentHints.streamTypeForBody(suppliedBody),
        )
        return packaged.copy(url = hinted)
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
        if (!media.playlistBody.isNullOrBlank()) {
            val path = writePlaylistTemp(media.playlistBody)
            return packageViaPhoneRustFile(
                path = path,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
            )
        }

        val localUri = media.localUri
            ?: media.url.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { Uri.parse(it) }

        if (localUri != null) {
            try {
                val proxy = DlnaProxyHolder.proxy(context)
                val url = proxy.publishLocal(localUri, media.contentType)
                return PackagedMedia(
                    url = url,
                    contentType = media.contentType,
                    headers = null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "LocalProxy publishLocal failed, falling back to Rust: ${e.message}")
                val path = materializeLocalPath(localUri, media.contentType)
                return packageViaPhoneRustFile(path, media.contentType)
            }
        }

        if (media.url.startsWith("data:")) {
            val body = decodeDataUri(media.url)
                ?: throw StreamRouteException("Unsupported data URI")
            val path = writePlaylistTemp(body)
            return packageViaPhoneRustFile(
                path = path,
                contentType = media.contentType ?: "application/vnd.apple.mpegurl",
            )
        }

        if (media.url.startsWith("http://") || media.url.startsWith("https://")) {
            return packageViaPhoneRemote(media)
        }

        throw StreamRouteException("Unsupported media URL for Via phone")
    }

    /**
     * Remote streams: primary path is embedded Rust `registerUrl` → `/s/...` with
     * JNI HttpURLConnection upstream. Falls back to LocalProxy if JNI callbacks
     * are missing or register fails (one-release safety net).
     */
    private suspend fun packageViaPhoneRemote(media: CastableMedia): PackagedMedia {
        val jniReady = SenderServicesNative.jniUpstreamReady &&
            runCatching { SenderServicesNative.upstreamCallbacksRegistered() }.getOrDefault(false)

        if (jniReady) {
            try {
                return packageViaPhoneRemoteRust(media)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Rust Via phone registerUrl failed; LocalProxy fallback: ${e.message}",
                )
            }
        } else {
            Log.e(
                TAG,
                "Rust proxy JNI upstream not ready (libraryLoaded=" +
                    "${SenderServicesNative.libraryLoaded}); Via phone remote uses LocalProxy fallback",
            )
        }
        return packageViaPhoneRemoteLocalProxy(media)
    }

    private suspend fun packageViaPhoneRemoteRust(media: CastableMedia): PackagedMedia {
        val services = PhoneSenderServices.get()
            ?: throw StreamRouteException("Embedded stream proxy is unavailable")
        val host = lanIpv4()
            ?: throw StreamRouteException("No LAN address for Via phone")
        val headers = ensureProxyUpstreamHeaders(media.headers.orEmpty())
        // Header names only — never values. Debug builds only: raw capture first
        // (does GeckoView surface Origin/referrer?), then the set registered upstream.
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Via phone raw captured headers: ${media.headers.orEmpty().keys.sorted()}",
            )
            Log.d(TAG, "Via phone registerUrl upstream headers: ${headers.keys.sorted()}")
        }
        val registered = services.registerUrl(
            host = host,
            url = media.url,
            headers = headers,
            contentType = media.contentType,
        )
        if (!PhoneProxyUrls.isRustEmbeddedProxyUrl(registered.url)) {
            throw StreamRouteException("Via phone produced unexpected proxy URL shape")
        }
        Log.i(TAG, "Via phone remote via Rust /s/ (origin host only, not full stream URL)")
        return PackagedMedia(
            url = registered.url,
            contentType = media.contentType ?: guessRemoteMime(media.url),
            headers = null,
        )
    }

    /** Legacy / fallback remote packaging through LocalProxyServer. */
    private fun packageViaPhoneRemoteLocalProxy(media: CastableMedia): PackagedMedia {
        val headers = ensureProxyUpstreamHeaders(media.headers.orEmpty())
        val mime = media.contentType ?: guessRemoteMime(media.url)
        val proxy = try {
            DlnaProxyHolder.proxy(context)
        } catch (e: Exception) {
            throw StreamRouteException("Could not start Via phone proxy: ${e.message}")
        }
        val url = try {
            proxy.publish(media.url, headers, mime)
        } catch (e: Exception) {
            throw StreamRouteException("Via phone publish failed: ${e.message}")
        }
        Log.i(TAG, "Via phone remote via LocalProxy (fallback)")
        return PackagedMedia(url = url, contentType = mime, headers = null)
    }

    private fun guessRemoteMime(url: String): String? = when {
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            url.contains(".m3u8?", ignoreCase = true) ||
            url.contains("mpegurl", ignoreCase = true) ||
            url.contains("manifest/hls", ignoreCase = true) ->
            "application/vnd.apple.mpegurl"
        url.substringBefore('?').endsWith(".mpd", ignoreCase = true) ||
            url.contains("manifest/dash", ignoreCase = true) ->
            "application/dash+xml"
        else -> null
    }

    private suspend fun packageViaPhoneRustFile(
        path: File,
        contentType: String?,
    ): PackagedMedia {
        val services = PhoneSenderServices.get()
            ?: throw StreamRouteException("Embedded stream proxy is unavailable")
        val host = lanIpv4()
            ?: throw StreamRouteException("No LAN address for Via phone")
        val registered = services.registerFile(
            host = host,
            path = path.absolutePath,
            contentType = contentType,
        )
        return PackagedMedia(
            url = registered.url,
            contentType = contentType,
            headers = null,
        )
    }

    private fun ensureProxyUpstreamHeaders(headers: Map<String, String>): Map<String, String> {
        val out = headers.toMutableMap()
        // Match PhoneExoPlayerFactory / VideoDetector defaults so Via phone and
        // on-phone ExoPlayer present the same client identity to the CDN.
        if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            out["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        // Prefer browser-generic Accept. A narrow mpegurl-only Accept made some
        // live CDNs return 403 while ExoPlayer (*/*) succeeded.
        if (out.keys.none { it.equals("Accept", ignoreCase = true) }) {
            out["Accept"] = "*/*"
        }
        // Keep page Origin: origin-protected CDNs (e.g. same-site media hosts)
        // return 403 without it. Desktop forwards Origin to the CDN too.
        return out
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
            media.contentType?.takeIf { it.isNotBlank() }?.let { put("content_type", it) }
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
