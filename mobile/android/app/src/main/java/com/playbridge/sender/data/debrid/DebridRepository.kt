package com.playbridge.sender.data.debrid

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DebridRepository(private val context: Context) {
    companion object {
        const val PREFS_NAME = "browser_settings"
        const val KEY_DEBRID_PROVIDER = "debrid_provider"
        const val KEY_DEBRID_API_KEY = "debrid_api_key"

        const val PROVIDER_NONE = "None"
        const val PROVIDER_REAL_DEBRID = "Real-Debrid"
        const val PROVIDER_ALL_DEBRID = "All-Debrid"
        const val PROVIDER_PREMIUMIZE = "Premiumize"
        const val PROVIDER_TORBOX = "TorBox"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        return getActiveProvider() != null
    }

    fun getConfiguredProviderName(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEBRID_PROVIDER, PROVIDER_NONE) ?: PROVIDER_NONE
    }

    fun getApiKey(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEBRID_API_KEY, "") ?: ""
    }

    fun saveConfiguration(provider: String, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DEBRID_PROVIDER, provider)
            .putString(KEY_DEBRID_API_KEY, apiKey)
            .apply()
    }

    fun getActiveProvider(): DebridProvider? {
        val providerName = getConfiguredProviderName()
        val apiKey = getApiKey()

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
