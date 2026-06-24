package com.playbridge.sender.downloads.engine

import mozilla.components.concept.fetch.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the [Response] GeckoView already fetched for an intercepted download.
 *
 * Why this exists: hosts like gofile.io gate the real file behind a session
 * cookie/token. Re-fetching the URL ourselves loses that session and gets back a tiny
 * HTML "login" page (observed: HTTP 200, ~12 KB). But GeckoView fetched the file *with*
 * the browser's full session and hands us the live response stream. Consuming that stream
 * directly is the bulletproof path; [FileStrategy] falls back to an HTTP re-fetch only when
 * no pre-fetched response is available (or it fails).
 *
 * The stream is single-use and holds an open connection, so entries are taken (removed) by
 * the worker and discarded if the user dismisses the download.
 */
object BrowserResponseStore {

    private val store = ConcurrentHashMap<String, Response>()

    fun put(url: String, response: Response) {
        // Replacing an existing entry — close the old connection first.
        store.put(url, response)?.let { runCatching { it.close() } }
    }

    /** Remove and return the response for [url], transferring ownership to the caller. */
    fun take(url: String): Response? = store.remove(url)

    fun discard(url: String) {
        store.remove(url)?.let { runCatching { it.close() } }
    }
}
