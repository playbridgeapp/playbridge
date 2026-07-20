package com.playbridge.sender.cast.roku

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

data class RokuPlayerStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
)

class RokuClient(
    private val http: OkHttpClient = OkHttpClient(),
) {
    fun launchMedia(
        host: String,
        port: Int = 8060,
        url: String,
        title: String? = null,
    ): Boolean {
        try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val encodedTitle = title?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
            val targetUrl = "http://$host:$port/launch/15985?contentID=$encodedUrl&mediaType=movie&title=$encodedTitle"
            val req = Request.Builder()
                .url(targetUrl)
                .post("".toRequestBody(null))
                .build()
            http.newCall(req).execute().use { resp ->
                return resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Roku launchMedia failed to $host:$port: ${e.message}")
            return false
        }
    }

    fun sendKeypress(
        host: String,
        port: Int = 8060,
        key: String,
    ): Boolean {
        try {
            val targetUrl = "http://$host:$port/keypress/$key"
            val req = Request.Builder()
                .url(targetUrl)
                .post("".toRequestBody(null))
                .build()
            http.newCall(req).execute().use { resp ->
                return resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Roku sendKeypress $key failed to $host:$port: ${e.message}")
            return false
        }
    }

    fun getMediaPlayerStatus(
        host: String,
        port: Int = 8060,
    ): RokuPlayerStatus? {
        try {
            val targetUrl = "http://$host:$port/query/media-player"
            val req = Request.Builder().url(targetUrl).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                return parsePlayerStatus(bodyStr)
            }
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        private const val TAG = "RokuClient"

        fun parsePlayerStatus(xml: String): RokuPlayerStatus? {
            return try {
                val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val doc = db.parse(ByteArrayInputStream(xml.toByteArray()))
                val root = doc.documentElement
                val state = root.getAttribute("state").ifEmpty { "none" }

                var posMs = 0L
                var durMs = 0L

                val posNodes = root.getElementsByTagName("position")
                if (posNodes.length > 0) {
                    val txt = posNodes.item(0).textContent.replace("ms", "").trim()
                    posMs = txt.toLongOrNull() ?: 0L
                }

                val durNodes = root.getElementsByTagName("duration")
                if (durNodes.length > 0) {
                    val txt = durNodes.item(0).textContent.replace("ms", "").trim()
                    durMs = txt.toLongOrNull() ?: 0L
                }

                RokuPlayerStatus(state = state, positionMs = posMs, durationMs = durMs)
            } catch (e: Exception) {
                null
            }
        }
    }
}
