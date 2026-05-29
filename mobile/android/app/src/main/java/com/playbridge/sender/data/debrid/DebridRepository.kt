package com.playbridge.sender.data.debrid

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DebridRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val PREFS_NAME = "browser_settings"
        const val KEY_DEBRID_PROVIDER = "debrid_provider"

        /** Legacy single-key storage. Kept for backup compatibility and one-time migration. */
        const val KEY_DEBRID_API_KEY = "debrid_api_key"

        const val PROVIDER_NONE = "None"
        const val PROVIDER_REAL_DEBRID = "Real-Debrid"
        const val PROVIDER_ALL_DEBRID = "All-Debrid"
        const val PROVIDER_PREMIUMIZE = "Premiumize"
        const val PROVIDER_TORBOX = "TorBox"

        /** All selectable providers, in display order. */
        val ALL_PROVIDERS = listOf(
            PROVIDER_REAL_DEBRID,
            PROVIDER_ALL_DEBRID,
            PROVIDER_PREMIUMIZE,
            PROVIDER_TORBOX
        )

        /** SharedPreferences key holding the API key for a specific provider. */
        fun apiKeyPrefName(provider: String) = "debrid_api_key_$provider"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean {
        return getActiveProvider() != null
    }

    /** The provider currently selected as active. */
    fun getConfiguredProviderName(): String {
        return prefs().getString(KEY_DEBRID_PROVIDER, PROVIDER_NONE) ?: PROVIDER_NONE
    }

    /** Switch the active provider. The legacy key is kept in sync so existing backups stay correct. */
    fun setActiveProvider(provider: String) {
        prefs().edit()
            .putString(KEY_DEBRID_PROVIDER, provider)
            .putString(KEY_DEBRID_API_KEY, getApiKeyFor(provider))
            .apply()
    }

    /** The API key stored for a specific provider (empty if none). */
    fun getApiKeyFor(provider: String): String {
        if (provider == PROVIDER_NONE) return ""
        val p = prefs()
        val perProvider = p.getString(apiKeyPrefName(provider), null)
        if (!perProvider.isNullOrBlank()) return perProvider
        // One-time migration: the legacy single key belonged to the previously-active provider.
        val legacy = p.getString(KEY_DEBRID_API_KEY, "") ?: ""
        if (legacy.isNotBlank() && provider == getConfiguredProviderName()) {
            p.edit().putString(apiKeyPrefName(provider), legacy).apply()
            return legacy
        }
        return ""
    }

    /** Store the API key for a specific provider. */
    fun saveApiKeyFor(provider: String, apiKey: String) {
        if (provider == PROVIDER_NONE) return
        val trimmed = apiKey.trim()
        prefs().edit().apply {
            putString(apiKeyPrefName(provider), trimmed)
            // Keep the legacy key in sync when editing the active provider (backup compatibility).
            if (provider == getConfiguredProviderName()) putString(KEY_DEBRID_API_KEY, trimmed)
            apply()
        }
    }

    /** The active provider's API key. */
    fun getApiKey(): String = getApiKeyFor(getConfiguredProviderName())

    /** Providers that have a non-blank API key configured, in display order. */
    fun getConfiguredProviders(): List<String> =
        ALL_PROVIDERS.filter { getApiKeyFor(it).isNotBlank() }

    fun saveConfiguration(provider: String, apiKey: String) {
        prefs().edit().putString(KEY_DEBRID_PROVIDER, provider).apply()
        saveApiKeyFor(provider, apiKey)
    }

    fun getActiveProvider(): DebridProvider? {
        val providerName = getConfiguredProviderName()
        val apiKey = getApiKeyFor(providerName)

        if (providerName == PROVIDER_NONE || apiKey.isBlank()) {
            return null
        }

        return when (providerName) {
            PROVIDER_REAL_DEBRID -> RealDebridClient(apiKey, client, json)
            PROVIDER_ALL_DEBRID -> AllDebridClient(apiKey, client, json)
            PROVIDER_PREMIUMIZE -> PremiumizeClient(apiKey, client, json)
            PROVIDER_TORBOX -> TorBoxClient(apiKey, client, json)
            else -> null
        }
    }
}
