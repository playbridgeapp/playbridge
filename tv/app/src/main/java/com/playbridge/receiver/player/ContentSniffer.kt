package com.playbridge.receiver.player

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

private const val TAG = "ContentSniffer"

/**
 * Handles HTTP client creation and pre-flight content-type sniffing
 * (reads the first few bytes of a URL to detect HLS streams).
 *
 * SSL certificate validation is bypassed only for local-network URLs
 * (RFC 1918 private IPs, loopback, and .local hostnames) to support
 * self-signed certs on local servers (Plex, Jellyfin, etc.).
 */
class ContentSniffer {

    /**
     * Returns true if the URL is on a local/private network address where
     * self-signed certificates are common and acceptable.
     *
     * Only numeric IP addresses are checked against private ranges — hostname
     * resolution via DNS is a network operation and must not run on the main
     * thread. Non-numeric hostnames are matched only by name (.local, localhost).
     */
    fun isLocalUrl(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false

        if (host == "localhost" || host.endsWith(".local")) return true

        // Avoid DNS: only attempt InetAddress parsing for numeric IPs.
        val looksLikeIp = host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        if (!looksLikeIp) return false

        return try {
            val addr = InetAddress.getByName(host)
            val bytes = addr.address
            when (bytes.size) {
                4 -> // IPv4 private ranges
                    bytes[0] == 10.toByte() ||                                        // 10.0.0.0/8
                    (bytes[0] == 172.toByte() && bytes[1] in 16..31) ||              // 172.16.0.0/12
                    (bytes[0] == 192.toByte() && bytes[1] == 168.toByte()) ||        // 192.168.0.0/16
                    bytes[0] == 127.toByte()                                          // 127.0.0.0/8
                16 -> // IPv6 loopback (::1) or private (fc00::/7)
                    addr.isLoopbackAddress ||
                    bytes[0] == 0xfc.toByte() || bytes[0] == 0xfd.toByte()
                else -> false
            }
        } catch (e: UnknownHostException) {
            false
        }
    }

    /**
     * Creates an OkHttpClient. For local-network URLs, SSL certificate
     * validation is disabled to support self-signed certificates.
     * Public URLs always use standard certificate validation.
     */
    fun getOkHttpClient(headers: Map<String, String>? = null, trustAllCerts: Boolean = false): okhttp3.OkHttpClient {
        try {
            val builder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)

            if (trustAllCerts) {
                val trustManagers = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustManagers, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManagers[0] as javax.net.ssl.X509TrustManager)
                       .hostnameVerifier { _, _ -> true }
            }

            if (headers != null && headers.isNotEmpty()) {
                builder.addInterceptor { chain ->
                    val requestBuilder = chain.request().newBuilder()
                    val url = chain.request().url.toString()
                    headers.forEach { (key, value) ->
                        // Use .header() instead of .addHeader() to overwrite any existing
                        // duplicate headers that ExoPlayer might have set (like double User-Agent)
                        requestBuilder.header(key, value)
                    }
                    val finalRequest = requestBuilder.build()
                    Log.d("OkHttpInterceptor", "Intercepted request to $url with headers: ${finalRequest.headers}")
                    chain.proceed(finalRequest)
                }
            }

            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /** Kept for binary compatibility with existing callers. */
    fun getUnsafeOkHttpClient(headers: Map<String, String>? = null): okhttp3.OkHttpClient =
        getOkHttpClient(headers, trustAllCerts = true)

    /**
     * Attempts to infer the content type from the URL extension.
     * If unknown, fetches the first few bytes to detect content type by signature.
     *
     * @return detected MIME type, or null if sniffing failed or type is unknown.
     */
    suspend fun sniffContent(url: String, headers: Map<String, String>?): String? {
        // First try to infer from URL to avoid unnecessary network request and
        // prevent ExoPlayer from falling back to byte sniffing (which can crash FLVExtractor)
        val inferredMimeType = inferMimeTypeFromUrl(url)
        if (inferredMimeType != null) {
            Log.i(TAG, "Inferred MIME type from URL: $inferredMimeType")
            return inferredMimeType
        }

        return withContext(Dispatchers.IO) {
            try {
                // Pass headers to the client so the interceptor applies them
                // This ensures cookies and auth headers are sent during the sniff
                val client = getOkHttpClient(headers, trustAllCerts = isLocalUrl(url))
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-50")

                // The interceptor will handle the rest, but we just make the request
                val request = requestBuilder.build()
                val result = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Sniffing failed: HTTP Error ${response.code} ${response.message}")
                        return@use null
                    }

                    val source = response.body?.source()
                    if (source == null) {
                        Log.w(TAG, "Sniffing failed: Response body source is null")
                        return@use null
                    }

                    val headerBytes = try {
                        source.readByteString(16)
                    } catch (e: Exception) {
                        Log.w(TAG, "Sniffing failed: Could not read 16 bytes for signature. Reason: ${e.message}")
                        return@use null
                    }
                    
                    val bytes = headerBytes.toByteArray()
                    val headerText = headerBytes.utf8()

                    Log.d(TAG, "Sniffed first 16 bytes. Checking signatures...")

                    // 1. Check for HLS Playlist (#EXTM3U)
                    if (headerText.startsWith("#EXTM3U")) {
                        Log.d(TAG, "Sniffed signature: APPLICATION_M3U8")
                        return@use androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    }

                    if (bytes.size >= 4) {
                        // 2. Check for Matroska / WebM (EBML Header: 1A 45 DF A3)
                        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) {
                            Log.d(TAG, "Sniffed signature: VIDEO_MATROSKA (MKV/WebM)")
                            return@use androidx.media3.common.MimeTypes.VIDEO_MATROSKA
                        }

                        // 3. Check for FLV (FLV\x01)
                        if (bytes[0] == 'F'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                            bytes[2] == 'V'.code.toByte() && bytes[3] == 0x01.toByte()) {
                            Log.d(TAG, "Sniffed signature: VIDEO_FLV")
                            return@use androidx.media3.common.MimeTypes.VIDEO_FLV
                        }
                    }

                    // 4. Check for MP4 (ftyp signature usually starts at byte 4)
                    if (bytes.size >= 8) {
                        if (bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) {
                            Log.d(TAG, "Sniffed signature: VIDEO_MP4")
                            return@use androidx.media3.common.MimeTypes.VIDEO_MP4
                        }
                    }

                    // 5. Check for AVI (RIFF)
                    if (bytes.size >= 4) {
                        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                            Log.d(TAG, "Sniffed signature: VIDEO_AVI")
                            return@use androidx.media3.common.MimeTypes.VIDEO_AVI
                        }
                    }

                    Log.d(TAG, "Sniffing complete: Unknown payload signature.")
                    return@use null
                }
                if (result != null) {
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sniffing failed with exception", e)
            }
            return@withContext null
        }
    }

    private fun inferMimeTypeFromUrl(url: String): String? {
        try {
            // Check both the base URL path and query parameters (like ?n=filename.mp4)
            val uri = android.net.Uri.parse(url)
            val path = uri.path?.lowercase() ?: ""
            val nParam = uri.getQueryParameter("n")?.lowercase() ?: ""

            val checkString = if (nParam.isNotEmpty() && nParam.contains(".")) nParam else path

            when {
                checkString.endsWith(".mp4") -> return androidx.media3.common.MimeTypes.VIDEO_MP4
                checkString.endsWith(".mkv") -> return androidx.media3.common.MimeTypes.VIDEO_MATROSKA
                checkString.endsWith(".webm") -> return androidx.media3.common.MimeTypes.VIDEO_WEBM
                checkString.endsWith(".avi") -> return androidx.media3.common.MimeTypes.VIDEO_AVI
                checkString.endsWith(".wmv") -> return "video/x-ms-wmv"
                checkString.endsWith(".flv") -> return androidx.media3.common.MimeTypes.VIDEO_FLV
                checkString.endsWith(".m3u8") || checkString.endsWith(".m3u") -> return androidx.media3.common.MimeTypes.APPLICATION_M3U8
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse URL for MIME inference: ${e.message}")
        }
        return null
    }
}
