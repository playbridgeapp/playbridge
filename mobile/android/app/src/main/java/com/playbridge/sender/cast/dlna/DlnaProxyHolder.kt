package com.playbridge.sender.cast.dlna

import android.content.Context

/**
 * Process-wide owner of the [LocalProxyServer] used for DLNA casting and phone
 * Via-phone packaging. The proxy must outlive any single screen/ViewModel.
 */
object DlnaProxyHolder {

    private var server: LocalProxyServer? = null

    /** The running proxy, started on first use. Idempotent. */
    @Synchronized
    fun proxy(context: Context): LocalProxyServer =
        server ?: LocalProxyServer(context.applicationContext.contentResolver)
            .also {
                it.start()
                server = it
            }

    /** Whether a running proxy publishes [url]; never starts the proxy. */
    @Synchronized
    fun servesIfRunning(url: String): Boolean = server?.serves(url) == true

    @Synchronized
    fun shutdown() {
        server?.stop()
        server = null
    }
}
