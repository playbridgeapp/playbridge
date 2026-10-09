package com.playbridge.sender.cast.dlna

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Rust filter_upstream_headers policy, scoped to the originally published media URL. */
internal object LocalProxyHeaderPolicy {
    fun forTarget(headers: Map<String, String>, targetUrl: String, credentialUrl: String): Map<String, String> {
        val target = targetUrl.toHttpUrlOrNull()
        val original = credentialUrl.toHttpUrlOrNull()
        val sameOrigin = target != null && original != null &&
            target.scheme == original.scheme && target.host == original.host && target.port == original.port
        return buildMap {
            headers.forEach { (name, value) ->
                val lower = name.lowercase()
                if (lower in setOf("host", "connection", "content-length", "accept-encoding", "range") ||
                    lower.startsWith(':')) return@forEach
                if (sameOrigin || lower in setOf("user-agent", "accept", "accept-language")) {
                    put(name, value)
                } else if (lower == "referer" || lower == "origin") {
                    value.toHttpUrlOrNull()?.let { url ->
                        val origin = origin(url)
                        put(name, if (lower == "referer") "$origin/" else origin)
                    }
                }
            }
        }
    }

    fun minimalRetryHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
        it.lowercase() in setOf("user-agent", "referer", "origin", "cookie", "authorization")
    }

    private fun origin(url: HttpUrl): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://$host$port"
    }
}
