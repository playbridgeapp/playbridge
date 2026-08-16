package com.playbridge.sender.browser

import android.content.Context
import androidx.core.content.edit
import java.net.IDN
import java.net.URI
import java.util.Locale

/** Persists the user's explicit permission for a web origin to start a cast. */
object PageCastConsentStore {
    private const val PREFS_NAME = "page_cast_consent"
    private const val KEY_APPROVED_ORIGINS = "approved_origins"
    private const val KEY_LOCAL_NETWORK_ORIGINS = "local_network_origins"

    fun normalizeOrigin(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null || uri.path !in listOf("", "/") || uri.query != null || uri.fragment != null) return null
            val host = uri.host?.let(IDN::toASCII)?.lowercase(Locale.ROOT) ?: return null
            val port = uri.port
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            "$scheme://$host" + if (port >= 0 && !defaultPort) ":$port" else ""
        }.getOrNull()
    }

    /** A concise, user-facing website label without the URL scheme. */
    fun displayName(origin: String): String = normalizeOrigin(origin)?.let { normalized ->
        runCatching {
            val uri = URI(normalized)
            val asciiHost = uri.host ?: return@runCatching origin
            val unicodeHost = IDN.toUnicode(asciiHost)
            val host = if (unicodeHost.equals(asciiHost, ignoreCase = true)) {
                asciiHost
            } else {
                "$unicodeHost ($asciiHost)"
            }
            if (uri.port >= 0) "$host:${uri.port}" else host
        }.getOrDefault(origin)
    } ?: origin

    fun connectionLabel(origin: String): String =
        if (normalizeOrigin(origin)?.startsWith("https://") == true) "Secure site" else "Not secure"

    fun isApproved(context: Context, origin: String): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        return approvedOrigins(context).contains(normalized)
    }

    fun approvedOrigins(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_APPROVED_ORIGINS, emptySet())
            .orEmpty()
            .toSet()

    fun isLocalNetworkApproved(context: Context, origin: String): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        return localNetworkOrigins(context).contains(normalized)
    }

    fun localNetworkOrigins(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_LOCAL_NETWORK_ORIGINS, emptySet())
            .orEmpty()
            .toSet()

    fun approve(context: Context, origin: String) {
        val normalized = normalizeOrigin(origin) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet(
                KEY_APPROVED_ORIGINS,
                prefs.getStringSet(KEY_APPROVED_ORIGINS, emptySet()).orEmpty() + normalized,
            )
        }
    }

    fun approveLocalNetwork(context: Context, origin: String) {
        val normalized = normalizeOrigin(origin) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet(
                KEY_LOCAL_NETWORK_ORIGINS,
                prefs.getStringSet(KEY_LOCAL_NETWORK_ORIGINS, emptySet()).orEmpty() + normalized,
            )
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_APPROVED_ORIGINS)
            remove(KEY_LOCAL_NETWORK_ORIGINS)
        }
    }

    fun clearLocalNetwork(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_LOCAL_NETWORK_ORIGINS)
        }
    }
}
