package com.playbridge.player.logging

import android.util.Log
import com.playbridge.player.BuildConfig
import com.playbridge.shared.logging.redactUrlForLog
import okhttp3.Request
import okhttp3.Response

/**
 * Debug-only network diagnostics. URLs are redacted and only header names are printed.
 *
 * Do not route these messages through [FileLogger]: its output is persisted and can be
 * downloaded over the LAN. Release builds compile the calls but never emit their values.
 */
object DebugNetworkLogger {
    fun urlAndHeaders(
        tag: String,
        label: String,
        url: String,
        headers: Map<String, String>?,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label URL: ${redactUrlForLog(url)}")
        logHeaderNames(tag, "$label headers", headers.orEmpty().keys)
    }

    fun request(tag: String, label: String, request: Request) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label request: ${request.method} ${redactUrlForLog(request.url.toString())}")
        logHeaderNames(tag, "$label request headers", request.headers.names())
    }

    fun response(tag: String, label: String, response: Response) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label response: HTTP ${response.code} ${redactUrlForLog(response.request.url.toString())}")
        logHeaderNames(tag, "$label response headers", response.headers.names())
    }

    fun response(
        tag: String,
        label: String,
        url: String,
        statusCode: Int,
        headers: Map<String, String>,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label response: HTTP $statusCode ${redactUrlForLog(url)}")
        logHeaderNames(tag, "$label response headers", headers.keys)
    }

    private fun logHeaderNames(tag: String, label: String, names: Collection<String>) {
        Log.d(tag, "$label header names (${names.size}): ${headerNamesForLog(names)}")
    }
}

/** Header names only. Values stay out of logcat. */
internal fun headerNamesForLog(names: Iterable<String>): String =
    names.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
