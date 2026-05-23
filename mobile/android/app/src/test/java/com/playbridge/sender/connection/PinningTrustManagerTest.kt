package com.playbridge.sender.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinningTrustManagerTest {

    // Fixed EC P-256 self-signed cert + its SPKI pin, computed once with openssl.
    // This is the cross-platform pin contract vector: if pin computation drifts
    // (here or on any receiver), the contract test below fails.
    private val certPem = """
        -----BEGIN CERTIFICATE-----
        MIIBiDCCAS+gAwIBAgIUd656Q1u8YYYScSglGWTa6KhhIPEwCgYIKoZIzj0EAwIw
        GjEYMBYGA1UEAwwPUGxheUJyaWRnZSBUZXN0MB4XDTI2MDUyMzExMTYwM1oXDTM2
        MDUyMDExMTYwM1owGjEYMBYGA1UEAwwPUGxheUJyaWRnZSBUZXN0MFkwEwYHKoZI
        zj0CAQYIKoZIzj0DAQcDQgAEQf90wxBpUQ9N+oGdStfQ07/QMLKkH5x3FrgzI4Qe
        QJgj7chfma9zNy0zry6zRjPMgaxitd27A8I9sZQ4w/mVA6NTMFEwHQYDVR0OBBYE
        FPP9LpgCubsyshdUjiwGVqbM/VgbMB8GA1UdIwQYMBaAFPP9LpgCubsyshdUjiwG
        VqbM/VgbMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgCBCTl40n
        rbL3+NSPio/08tL9V967GLZse5G3QF+XoBQCIFVDGT0Hek/4QPqxA1pd34f5h7Vi
        +AbHSlbKR4BkNVzT
        -----END CERTIFICATE-----
    """.trimIndent()

    private val expectedPin = "sha256/f9K58qxacKqmDI40NtNzW9e04W3hiTTPktkYy9xx7pA="

    private fun chain(): Array<X509Certificate> {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
        return arrayOf(cert)
    }

    /** Pin contract: the pin computed for the cert equals the known vector. */
    @Test
    fun pinComputationMatchesKnownVector() {
        var captured: String? = null
        PinningTrustManager(expectedPin = null) { captured = it }
            .checkServerTrusted(chain(), "ECDHE_ECDSA")
        assertEquals(expectedPin, captured)
    }

    /** Reconnect: a cert whose pin matches the stored pin is accepted. */
    @Test
    fun matchingPinIsAccepted() {
        var captured: String? = null
        PinningTrustManager(expectedPin) { captured = it }
            .checkServerTrusted(chain(), "ECDHE_ECDSA")
        assertEquals(expectedPin, captured)
    }

    /** Possible MITM: a cert whose pin differs from the stored pin is rejected. */
    @Test
    fun mismatchedPinIsRejected() {
        val wrongPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        try {
            PinningTrustManager(wrongPin) {}
                .checkServerTrusted(chain(), "ECDHE_ECDSA")
            fail("expected CertificateException for pin mismatch")
        } catch (e: CertificateException) {
            // expected
        }
    }

    /** First pairing (TOFU): null expected pin accepts any cert and captures it. */
    @Test
    fun firstPairTofuAcceptsAndCaptures() {
        var captured: String? = null
        PinningTrustManager(expectedPin = null) { captured = it }
            .checkServerTrusted(chain(), "ECDHE_ECDSA")
        assertEquals(expectedPin, captured)
    }

    @Test(expected = CertificateException::class)
    fun emptyChainIsRejected() {
        PinningTrustManager(null) {}.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
    }
}
