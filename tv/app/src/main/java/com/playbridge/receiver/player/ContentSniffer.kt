package com.playbridge.receiver.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ContentSniffer"

/**
 * Handles SSL-bypassing HTTP client creation and pre-flight content-type
 * sniffing (reads the first few bytes of a URL to detect HLS streams).
 */
class ContentSniffer {

    /**
     * Creates an OkHttpClient that trusts all certificates.
     * Required for local-network / self-signed servers.
     */
    fun getUnsafeOkHttpClient(headers: Map<String, String>? = null): okhttp3.OkHttpClient {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            val builder = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                
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
                val client = getUnsafeOkHttpClient(headers)
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
                        source.readByteString(7)
                    } catch (e: Exception) {
                        Log.w(TAG, "Sniffing failed: Could not read 7 bytes for signature. Reason: ${e.message}")
                        return@use null
                    }
                    
                    val headerText = headerBytes.utf8()
                    Log.d(TAG, "Sniffed first 7 bytes: '$headerText'")

                    if (headerText.startsWith("#EXTM3U")) {
                        Log.d(TAG, "Sniffed successfully as APPLICATION_M3U8")
                        return@use androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    } else {
                        Log.d(TAG, "Sniffing complete: Not an M3U8 payload")
                        return@use null
                    }
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
