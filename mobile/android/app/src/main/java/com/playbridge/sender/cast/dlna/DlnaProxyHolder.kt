package com.playbridge.sender.cast.dlna

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of the [LocalProxyServer] used for DLNA casting. The proxy
 * must outlive any single screen/ViewModel (a cast keeps playing while the user
 * navigates), so it lives here rather than in a ViewModel.
 *
 * A foreground service (added in a later step) will keep the process alive while a
 * cast is active so playback survives screen-off; for now the proxy runs as a
 * plain daemon-threaded server.
 */
object DlnaProxyHolder {

    /** Shared OkHttp for both proxy upstream fetches and AVTransport SOAP. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var server: LocalProxyServer? = null

    /** The running proxy, started on first use. Idempotent. */
    @Synchronized
    fun proxy(context: Context): LocalProxyServer =
        server ?: LocalProxyServer(httpClient, context.applicationContext.contentResolver)
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
