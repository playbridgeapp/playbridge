package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.proxy.CastableMedia
import com.playbridge.sender.cast.proxy.PackagedMedia
import com.playbridge.sender.cast.proxy.StreamProxySettings
import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.cast.proxy.StreamRouteService
import com.playbridge.sender.model.CastProtocol

/** Packages media for the route explicitly selected by the user. */
object CastPreparation {

    /** PlayBridge understands sender metadata; third-party receivers default to Via phone. */
    fun defaultRoute(protocol: CastProtocol): StreamRouteMode = when (protocol) {
        CastProtocol.PLAYBRIDGE -> StreamRouteMode.DIRECT
        CastProtocol.GOOGLE_CAST,
        CastProtocol.ROKU,
        CastProtocol.DLNA,
        CastProtocol.WEB_BROWSER,
        -> StreamRouteMode.VIA_PHONE
    }

    /** External protocols cannot attach arbitrary browser request headers to Direct loads. */
    fun headersForDirect(protocol: CastProtocol, headers: Map<String, String>?): Map<String, String>? {
        if (headers.isNullOrEmpty()) return null
        return when (protocol) {
            CastProtocol.PLAYBRIDGE -> headers
            CastProtocol.WEB_BROWSER,
            CastProtocol.GOOGLE_CAST,
            CastProtocol.ROKU,
            CastProtocol.DLNA,
            -> null
        }
    }

    /**
     * A filtered HLS master is a data URI and therefore phone-only. Direct and Via proxy can
     * use an HTTP child playlist when it is muxed. For demuxed variants, preserve the selected
     * route by returning the original master (Auto quality) rather than dropping audio.
     */
    fun resolveItemForPackaging(
        evaluatedUrl: String,
        packagedCandidateUrl: String,
        requestedMode: StreamRouteMode,
        hasSeparateAudio: Boolean = false,
        originalMasterUrl: String? = null,
    ): Pair<String, StreamRouteMode> {
        if (!packagedCandidateUrl.startsWith("data:", ignoreCase = true)) {
            return packagedCandidateUrl to requestedMode
        }
        if (requestedMode == StreamRouteMode.VIA_PHONE) {
            return packagedCandidateUrl to StreamRouteMode.VIA_PHONE
        }
        val childIsHttp = evaluatedUrl.startsWith("http://") ||
            evaluatedUrl.startsWith("https://")
        if (!hasSeparateAudio && childIsHttp) {
            return evaluatedUrl to requestedMode
        }
        val httpMaster = originalMasterUrl?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        return if (hasSeparateAudio && httpMaster != null) {
            httpMaster to requestedMode
        } else {
            packagedCandidateUrl to StreamRouteMode.VIA_PHONE
        }
    }

    suspend fun prepare(
        streamRouteService: StreamRouteService,
        media: CastableMedia,
        requested: StreamRouteMode,
        protocol: CastProtocol,
        settings: StreamProxySettings,
    ): PreparedCastItem {
        val mode = when {
            requested == StreamRouteMode.DIRECT && isPhoneOnly(media) -> StreamRouteMode.VIA_PHONE
            else -> requested
        }
        val toPackage = if (mode == StreamRouteMode.DIRECT) {
            media.copy(headers = headersForDirect(protocol, media.headers))
        } else {
            media
        }
        val packaged: PackagedMedia = streamRouteService.packageForCast(toPackage, mode, settings)
        val finalMode = mode
        return PreparedCastItem(
            url = packaged.url,
            headers = if (finalMode == StreamRouteMode.DIRECT) packaged.headers else null,
            contentType = packaged.contentType ?: media.contentType,
            effectiveRoute = EffectiveStreamRoute(
                mode = finalMode,
                policyReason = if (finalMode != requested) {
                    "phone_only_media"
                } else {
                    "user_selected_${finalMode.prefsValue}"
                },
            ),
        )
    }

    private fun isPhoneOnly(media: CastableMedia): Boolean {
        val url = media.url.lowercase()
        return url.startsWith("content://") ||
            url.startsWith("file://") ||
            url.startsWith("data:") ||
            url.startsWith("blob:") ||
            !media.playlistBody.isNullOrBlank() ||
            !media.audioUrl.isNullOrBlank()
    }
}
