package com.playbridge.sender.connection

import okhttp3.CertificatePinner
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trusts the receiver's self-signed cert by SPKI pin rather than CA chain.
 *
 * - Reports the presented cert's pin via [onPresented] — used to capture the
 *   served cert on first pairing and to bind the delivered fingerprint to the
 *   real peer.
 * - When [expectedPin] is non-null, rejects any cert whose SPKI pin differs
 *   (possible MITM). When null (first pairing) it accepts trust-on-first-use.
 *
 * The pin format (`sha256/<base64 SPKI>`) is OkHttp's `CertificatePinner.pin`,
 * which must match what the receivers compute from `cert.publicKey.encoded`.
 */
class PinningTrustManager(
    private val expectedPin: String?,
    private val onPresented: (String) -> Unit,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No server certificate")
        val presented = CertificatePinner.pin(leaf)
        onPresented(presented)
        if (expectedPin != null && presented != expectedPin) {
            throw CertificateException(
                "PlayBridge TLS pin mismatch: expected=$expectedPin presented=$presented"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
