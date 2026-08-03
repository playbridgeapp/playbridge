package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.model.CastProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastPreparationTest {

    @Test
    fun thirdPartyProtocolsDefaultViaPhoneWhilePlayBridgeDefaultsDirect() {
        assertEquals(
            StreamRouteMode.DIRECT,
            CastPreparation.defaultRoute(CastProtocol.PLAYBRIDGE),
        )
        for (protocol in listOf(
            CastProtocol.GOOGLE_CAST,
            CastProtocol.ROKU,
            CastProtocol.DLNA,
            CastProtocol.WEB_BROWSER,
        )) {
            assertEquals(StreamRouteMode.VIA_PHONE, CastPreparation.defaultRoute(protocol))
        }
    }

    @Test
    fun playBridgeKeepsHeadersOnDirect() {
        val headers = mapOf("Cookie" to "a=b", "Referer" to "https://site")
        val out = CastPreparation.headersForDirect(CastProtocol.PLAYBRIDGE, headers)
        assertEquals(headers, out)
    }

    @Test
    fun externalProtocolsStripHeadersOnDirect() {
        val headers = mapOf("Cookie" to "a=b", "Authorization" to "Bearer x")
        for (protocol in listOf(
            CastProtocol.GOOGLE_CAST,
            CastProtocol.ROKU,
            CastProtocol.DLNA,
            CastProtocol.WEB_BROWSER,
        )) {
            assertNull(CastPreparation.headersForDirect(protocol, headers))
        }
    }

    @Test
    fun effectiveStreamRouteCarriesTheUserSelection() {
        val direct = EffectiveStreamRoute(
            mode = StreamRouteMode.DIRECT,
            policyReason = "user_selected_direct",
        )
        assertEquals(StreamRouteMode.DIRECT, direct.mode)
    }
}
