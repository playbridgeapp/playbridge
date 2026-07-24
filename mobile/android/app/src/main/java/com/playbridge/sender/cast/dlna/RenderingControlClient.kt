package com.playbridge.sender.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Minimal UPnP RenderingControl client for receiver master-volume changes. */
class RenderingControlClient(
    private val controlUrl: String,
    private val http: OkHttpClient,
) {
    suspend fun setVolume(percent: Int) {
        val desired = percent.coerceIn(0, 100)
        val args = "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredVolume>$desired</DesiredVolume>"
        if (action("SetVolume", args) == null) throw IOException("DLNA SetVolume failed")
    }

    suspend fun getVolume(): Int? {
        val args = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
        return action("GetVolume", args)
            ?.let { response ->
                Regex("<CurrentVolume>(.*?)</CurrentVolume>", RegexOption.DOT_MATCHES_ALL)
                    .find(response)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.toIntOrNull()
            }
            ?.coerceIn(0, 100)
    }

    private suspend fun action(name: String, args: String): String? = withContext(Dispatchers.IO) {
        val body = SOAP_HEAD +
            "<u:$name xmlns:u=\"$SERVICE\">$args</u:$name>" +
            SOAP_TAIL
        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", "\"$SERVICE#$name\"")
            .post(body.toRequestBody(CONTENT_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "$name -> HTTP ${response.code}")
                    return@use null
                }
                text
            }
        } catch (error: Exception) {
            Log.w(TAG, "$name failed: ${error.message}")
            null
        }
    }

    companion object {
        private const val TAG = "RenderingControl"
        private const val SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
        private val CONTENT_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
        private const val SOAP_HEAD =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
        private const val SOAP_TAIL = "</s:Body></s:Envelope>"
    }
}
