package com.playbridge.sender.cast.dlna

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and parses a UPnP device-description document to locate the
 * AVTransport (and RenderingControl) SOAP control endpoints.
 */
class DeviceDescription(private val http: OkHttpClient) {

    data class Renderer(
        val friendlyName: String,
        val location: String,
        val udn: String?,
        val avTransportControlUrl: String?,
        val renderingControlControlUrl: String?,
        val connectionManagerControlUrl: String? = null,
    ) {
        /** Usable as a target only if it exposes an AVTransport control URL. */
        val isUsable: Boolean get() = avTransportControlUrl != null
    }

    suspend fun fetch(location: String): Renderer? = withContext(Dispatchers.IO) {
        try {
            val xml = http.newCall(Request.Builder().url(location).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "desc HTTP ${resp.code} for $location")
                    return@use null
                }
                resp.body?.string()
            } ?: return@withContext null
            parse(xml, location)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch/parse $location", e)
            null
        }
    }

    private fun parse(xml: String, location: String): Renderer {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }

        var friendlyName: String? = null
        var udn: String? = null
        var urlBase: String? = null

        // Accumulators for the <service> currently being read.
        var inService = false
        var svcType: String? = null
        var svcControl: String? = null
        var avControl: String? = null
        var rcControl: String? = null
        var connectionManagerControl: String? = null

        var currentTag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name.equals("service", true)) {
                        inService = true; svcType = null; svcControl = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (currentTag?.lowercase()) {
                        "friendlyname" -> if (friendlyName == null) friendlyName = text
                        "udn" -> if (udn == null) udn = text
                        "urlbase" -> urlBase = text
                        "servicetype" -> if (inService) svcType = text
                        "controlurl" -> if (inService) svcControl = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("service", true)) {
                        val type = svcType ?: ""
                        when {
                            type.contains("AVTransport", true) && svcControl != null -> avControl = svcControl
                            type.contains("RenderingControl", true) && svcControl != null -> rcControl = svcControl
                            type.contains("ConnectionManager", true) && svcControl != null -> connectionManagerControl = svcControl
                        }
                        inService = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }

        // controlURL may be relative; resolve against <URLBase> when present, else the doc URL.
        val base = urlBase?.takeIf { it.isNotBlank() } ?: location
        val renderer = Renderer(
            friendlyName = friendlyName ?: "Unknown renderer",
            location = location,
            udn = udn,
            avTransportControlUrl = avControl?.let { resolve(base, it) },
            renderingControlControlUrl = rcControl?.let { resolve(base, it) },
            connectionManagerControlUrl = connectionManagerControl?.let { resolve(base, it) },
        )
        renderer.avTransportControlUrl?.let { avUrl ->
            renderer.connectionManagerControlUrl?.let { cmUrl -> connectionManagerUrls[avUrl] = cmUrl }
        }
        return renderer
    }

    private fun resolve(base: String, ref: String): String =
        try {
            URI(base).resolve(ref).toString()
        } catch (e: Exception) {
            ref
        }

    companion object {
        private const val TAG = "DeviceDescription"
        private val connectionManagerUrls = ConcurrentHashMap<String, String>()

        internal fun connectionManagerUrl(avTransportUrl: String): String? = connectionManagerUrls[avTransportUrl]
    }
}
