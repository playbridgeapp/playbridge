package com.playbridge.sender.browser

import android.content.Context
import androidx.core.content.edit
import com.playbridge.shared.network.MediaNetworkPolicy
import java.net.IDN
import java.net.URI
import java.util.Locale

/** Persists website-cast consent and exact website-to-private-origin grants. */
object PageCastConsentStore {
    private const val PREFS_NAME = "page_cast_consent"
    private const val KEY_APPROVED_ORIGINS = "approved_origins"
    private const val KEY_PRIVATE_ORIGIN_GRANTS = "private_origin_grants"
    private const val GRANT_SEPARATOR = "|"

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

    /** A concise, user-facing website or media-origin label without the URL scheme. */
    fun displayName(origin: String): String = normalizeOrigin(origin)?.let { normalized ->
        runCatching {
            val uri = URI(normalized)
            val asciiHost = uri.host ?: return@runCatching origin
            val unicodeHost = IDN.toUnicode(asciiHost)
            val host = if (unicodeHost.equals(asciiHost, ignoreCase = true)) asciiHost else "$unicodeHost ($asciiHost)"
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
            .getStringSet(KEY_APPROVED_ORIGINS, emptySet()).orEmpty().toSet()

    fun approvedPrivateOrigins(context: Context, websiteOrigin: String): Set<String> {
        val website = normalizeOrigin(websiteOrigin) ?: return emptySet()
        return privateOriginGrants(context).mapNotNullTo(linkedSetOf()) { encoded ->
            val separator = encoded.indexOf(GRANT_SEPARATOR)
            if (separator <= 0 || encoded.substring(0, separator) != website) null
            else encoded.substring(separator + GRANT_SEPARATOR.length)
        }
    }

    fun unapprovedPrivateOrigins(
        context: Context,
        websiteOrigin: String,
        requestedOrigins: Collection<String>,
    ): Set<String> {
        val normalized = MediaNetworkPolicy.normalizePrivateOrigins(requestedOrigins) ?: return requestedOrigins.toSet()
        return normalized - approvedPrivateOrigins(context, websiteOrigin)
    }

    /** Website origins that currently hold at least one exact private-origin grant. */
    fun localNetworkOrigins(context: Context): Set<String> = privateOriginGrants(context).mapNotNullTo(linkedSetOf()) {
        it.substringBefore(GRANT_SEPARATOR).takeIf(String::isNotBlank)
    }

    fun approve(context: Context, origin: String) {
        val normalized = normalizeOrigin(origin) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet(KEY_APPROVED_ORIGINS, prefs.getStringSet(KEY_APPROVED_ORIGINS, emptySet()).orEmpty() + normalized)
        }
    }

    fun approvePrivateOrigins(context: Context, websiteOrigin: String, privateOrigins: Collection<String>) {
        val website = normalizeOrigin(websiteOrigin) ?: return
        val normalized = MediaNetworkPolicy.normalizePrivateOrigins(privateOrigins) ?: return
        val additions = normalized.mapTo(linkedSetOf()) { "$website$GRANT_SEPARATOR$it" }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet(KEY_PRIVATE_ORIGIN_GRANTS, privateOriginGrants(context) + additions)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_APPROVED_ORIGINS)
            remove(KEY_PRIVATE_ORIGIN_GRANTS)
        }
    }

    fun clearLocalNetwork(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_PRIVATE_ORIGIN_GRANTS)
        }
    }

    private fun privateOriginGrants(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PRIVATE_ORIGIN_GRANTS, emptySet()).orEmpty().toSet()
}
