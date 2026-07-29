package com.playbridge.sender.cast.dlna

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of the [LocalProxyServer] used for DLNA casting and phone
 * Via-phone packaging. The proxy must outlive any single screen/ViewModel.
 */
object DlnaProxyHolder {

    /**
     * Upstream client tuned like Media3 [DefaultHttpDataSource]: long timeouts,
     * redirects, HTTP/1.1 only (some live CDNs mishandle OkHttp HTTP/2).
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private var server: LocalProxyServer? = null

    /** The running proxy, started on first use. Idempotent. */
    @Synchronized
    fun proxy(context: Context): LocalProxyServer =
        server ?: LocalProxyServer(context.applicationContext.contentResolver)
            .also {
                it.start()
                server = it
            }

    @Synchronized
    fun shutdown() {
        server?.stop()
        server = null
    }
}
