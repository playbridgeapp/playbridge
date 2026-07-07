package com.playbridge.player.player

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust helpers for local-network media servers (Plex, Jellyfin, etc.) that
 * commonly serve self-signed certificates.
 *
 * Unlike a trust-all TrustManager, [LocalSelfSignedTrustManager] validates against
 * the system trust store FIRST and only then accepts a single, self-signed,
 * currently-valid, correctly-signed certificate. Untrusted CA chains, expired
 * certs, and tampered certs are still rejected with [CertificateException], so
 * this does not grant blanket trust (and does not trip Play's
 * unsafe-X509TrustManager scan, which flags implementations that never throw).
 */
object LocalTrust {

    /** The platform default trust manager (system CA store). */
    val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

class LocalSelfSignedTrustManager(
    private val delegate: X509TrustManager = LocalTrust.systemTrustManager,
) : X509TrustManager {

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            // System trust failed — accept only a lone self-signed cert that is
            // currently valid and actually signed by its own key.
            if (chain.size != 1) throw systemFailure
            val cert = chain[0]
            if (cert.subjectX500Principal != cert.issuerX500Principal) throw systemFailure
            try {
                cert.checkValidity()
                cert.verify(cert.publicKey)
            } catch (e: Exception) {
                throw CertificateException("Self-signed certificate failed validation", e)
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/**
 * Hostname verification for clients built with [LocalSelfSignedTrustManager].
 *
 * Local-network hosts skip name matching (self-signed LAN certs rarely carry a
 * SAN matching a private IP). Public hosts must pass BOTH the platform default
 * name check AND a system-trust re-validation of the peer chain. The re-check
 * closes a redirect hole: the relaxed trust manager applies connection-wide, so
 * when a local URL redirects to a public host, name matching alone would accept
 * an attacker's self-signed cert whose SAN matches that public hostname.
 */
class LocalHostnameVerifier(
    private val isLocalHost: (String) -> Boolean,
) : HostnameVerifier {

    private val default: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (isLocalHost(hostname)) return true
        if (!default.verify(hostname, session)) return false
        // Public host: the self-signed leniency must not apply — require the
        // presented chain to validate against the system trust store.
        return try {
            val chain = session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .toTypedArray()
            if (chain.isEmpty()) return false
            LocalTrust.systemTrustManager.checkServerTrusted(chain, chain[0].publicKey.algorithm)
            true
        } catch (_: Exception) {
            false
        }
    }
}
