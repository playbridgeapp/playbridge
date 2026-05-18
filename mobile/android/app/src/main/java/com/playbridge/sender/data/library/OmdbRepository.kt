package com.playbridge.sender.data.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OmdbRepository(private val context: Context) {

    companion object {
        private const val TAG = "OmdbRepository"
        private const val BASE_URL = "https://www.omdbapi.com"
        private const val PREFS_NAME = "browser_settings"
        private const val KEY_OMDB_API = "omdb_api_key"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getApiKey(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_OMDB_API, null)
        return if (key.isNullOrBlank()) null else key
    }

    fun isConfigured(): Boolean = getApiKey() != null

    suspend fun getDetailsByImdbId(imdbId: String): OmdbResponse? {
        val apiKey = getApiKey() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/?apikey=$apiKey&i=$imdbId"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val omdbData = json.decodeFromString<OmdbResponse>(body)
                    if (omdbData.response == "True") omdbData else null
                } else {
                    Log.e(TAG, "OMDB request failed: ${response.code} for $url")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "OMDB request error", e)
                null
            }
        }
    }
}
