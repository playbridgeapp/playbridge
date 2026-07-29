package com.playbridge.player.logging

import android.util.Log
import com.playbridge.player.BuildConfig
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

/**
 * Full-fidelity network diagnostics for debug logcat only.
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
        Log.d(tag, "$label URL: $url")
        logHeaders(tag, "$label headers", headers.orEmpty())
    }

    fun request(tag: String, label: String, request: Request) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label request: ${request.method} ${request.url}")
        logHeaders(tag, "$label request headers", request.headers)
    }

    fun response(tag: String, label: String, response: Response) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label response: HTTP ${response.code} ${response.request.url}")
        logHeaders(tag, "$label response headers", response.headers)
    }

    fun response(
        tag: String,
        label: String,
        url: String,
        statusCode: Int,
        headers: Map<String, String>,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, "$label response: HTTP $statusCode $url")
        logHeaders(tag, "$label response headers", headers)
    }

    private fun logHeaders(tag: String, label: String, headers: Map<String, String>) {
        Log.d(tag, "$label (${headers.size}):")
        headers.forEach { (name, value) -> Log.d(tag, "$label $name: $value") }
    }

    private fun logHeaders(tag: String, label: String, headers: Headers) {
        Log.d(tag, "$label (${headers.size}):")
        headers.forEach { (name, value) -> Log.d(tag, "$label $name: $value") }
    }
}
