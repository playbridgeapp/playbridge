package com.playbridge.player.server

import android.util.Base64
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import javax.security.auth.x500.X500Principal
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Self-signed TLS identity for the wss:// listener.
 *
 * Uses a *software* EC keypair (not AndroidKeyStore) because Conscrypt can't sign
 * TLS handshakes with a non-exportable AndroidKeyStore key (NONEwithECDSA upcall
 * fails). The cert is built with BouncyCastle and the key+cert are persisted to an
 * app-private PKCS12 file so the SPKI pin is stable across restarts; the file is
 * wiped on uninstall, so a reinstall regenerates the identity (senders re-pair).
 */
object TlsIdentity {
    private const val ENTRY = "playbridge"
    private val PASSWORD = "playbridge".toCharArray()
    private const val FILE_NAME = "playbridge_tls.p12"

    data class Result(val sslContext: SSLContext, val fingerprint: String)

    fun loadOrCreate(dir: File, commonName: String = "PlayBridge TV"): Result {
        val file = File(dir, FILE_NAME)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, PASSWORD) }
        } else {
            ks.load(null, null)
            val keyPair = generateKeyPair()
            val cert = selfSignedCert(keyPair, commonName)
            ks.setKeyEntry(ENTRY, keyPair.private, PASSWORD, arrayOf(cert))
            file.outputStream().use { ks.store(it, PASSWORD) }
        }

        val cert = ks.getCertificate(ENTRY) as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
        return Result(sslContext, spkiPin(cert))
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun selfSignedCert(keyPair: KeyPair, commonName: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000) // ~10 years
        val name = X500Principal("CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** X.509 `publicKey.encoded` is the DER SubjectPublicKeyInfo — the SPKI we pin. */
    private fun spkiPin(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
