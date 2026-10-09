package com.playbridge.sender.cast.proxy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/** Registration carries media credentials, so redirects and unbounded bodies are forbidden. */
internal object RemoteProxyClient {
    internal const val MAX_RESPONSE_BYTES = 512 * 1024
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun register(client: OkHttpClient, media: CastableMedia, settings: StreamProxySettings): PackagedMedia {
        val base = validatedUrl(settings.remoteBaseUrl.trim(), base = true)
        val registerUrl = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/register")
            .addQueryParameter("token", settings.remotePassword)
            .build()
        val body = JSONObject().apply {
            put("url", media.url)
            media.contentType?.takeIf { it.isNotBlank() }?.let { put("content_type", it) }
            put("headers", JSONObject(media.headers.orEmpty()))
        }
        val request = Request.Builder().url(registerUrl)
            .post(body.toString().toRequestBody(JSON_MEDIA)).build()
        // Apply this even to injected clients; both HTTP and scheme redirects are disabled.
        val registrationClient = client.newBuilder()
            .followRedirects(false).followSslRedirects(false).build()
        try {
            registrationClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw StreamRouteException("Remote proxy register failed: HTTP ${response.code}")
                }
                val bytes = response.body?.byteStream()?.use(::readResponse)
                    ?: throw StreamRouteException("Remote proxy returned an invalid response")
                val json = try {
                    JSONObject(String(bytes, Charsets.UTF_8))
                } catch (_: Exception) {
                    throw StreamRouteException("Remote proxy returned an invalid response")
                }
                val proxyUrl = (json.opt("proxy_url") as? String)?.takeIf { it.isNotEmpty() }
                    ?: (json.opt("encrypted_url") as? String).orEmpty()
                validatedUrl(proxyUrl, base = false)
                return PackagedMedia(proxyUrl, media.contentType, null)
            }
        } catch (e: StreamRouteException) {
            throw e
        } catch (_: Exception) {
            // HTTP exceptions can include the registration URL and its password.
            throw StreamRouteException("Couldn't reach the proxy. Check its address and network connection.")
        }
    }

    internal fun validatedUrl(value: String, base: Boolean): HttpUrl {
        val uri = runCatching { URI(value) }.getOrNull()
        val url = value.toHttpUrlOrNull()
        if (uri == null || url == null || uri.host.isNullOrBlank() ||
            uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null ||
            uri.rawAuthority?.contains('@') == true ||
            (base && (uri.rawQuery != null || uri.rawFragment != null))) {
            throw StreamRouteException(if (base) "Configure a valid HTTP or HTTPS proxy URL first"
                else "Remote proxy returned no usable stream URL")
        }
        return url
    }

    /** Read at most the budget plus one detection byte, regardless of Content-Length. */
    internal fun readResponse(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (output.size() < MAX_RESPONSE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size()))
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
        }
        if (input.read() >= 0) throw StreamRouteException("Remote proxy returned an oversized response")
        return output.toByteArray()
    }
}
