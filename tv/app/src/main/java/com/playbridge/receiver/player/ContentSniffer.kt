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
    fun getUnsafeOkHttpClient(): okhttp3.OkHttpClient {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            return okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Fetches the first few bytes of [url] to detect content type by signature.
     * Currently detects HLS streams (#EXTM3U header).
     *
     * @return detected MIME type, or null if sniffing failed or type is unknown.
     */
    suspend fun sniffContent(url: String, headers: Map<String, String>?): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = getUnsafeOkHttpClient()
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-50")

                headers?.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true)) {
                        requestBuilder.header(k, v)
                    }
                }

                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null

                    val source = response.body?.source() ?: return@use null

                    val headerBytes = try {
                        source.readByteString(7)
                    } catch (e: Exception) {
                        return@use null
                    }

                    if (headerBytes.utf8().startsWith("#EXTM3U")) {
                        return@use androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sniffing failed: ${e.message}")
            }
            return@withContext null
        }
    }
}
