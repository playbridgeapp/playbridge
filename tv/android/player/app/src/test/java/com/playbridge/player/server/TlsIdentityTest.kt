package com.playbridge.player.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

class TlsIdentityTest {

    private val testPassword = "test-password-not-from-source".toCharArray()
    private val passwordStore = TlsPasswordStore { testPassword.copyOf() }

    private fun tempDir(): File = Files.createTempDirectory("pb_tls_test").toFile()

    @Test
    fun generatesCertWithSha256SpkiPin() {
        val dir = tempDir()
        try {
            val r = TlsIdentity.loadOrCreate(dir, passwordStore = passwordStore)
            assertTrue(r.fingerprint.startsWith("sha256/"))
            // SHA-256 = 32 bytes → 44 base64 chars (with padding).
            assertEquals("sha256/".length + 44, r.fingerprint.length)
            assertNotNull(r.sslContext)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistsAndReusesIdentityAcrossReloads() {
        val dir = tempDir()
        try {
            val first = TlsIdentity.loadOrCreate(dir, passwordStore = passwordStore)
            val second = TlsIdentity.loadOrCreate(dir, passwordStore = passwordStore)
            assertEquals(first.fingerprint, second.fingerprint)
            assertTrue(File(dir, "playbridge_tls.p12").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun generatesDistinctIdentityInFreshDir() {
        val a = tempDir()
        val b = tempDir()
        try {
            assertNotEquals(
                TlsIdentity.loadOrCreate(a, passwordStore = passwordStore).fingerprint,
                TlsIdentity.loadOrCreate(b, passwordStore = passwordStore).fingerprint,
            )
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacyKeystoreToProtectedPasswordWithoutChangingIdentity() {
        val dir = tempDir()
        try {
            val legacyStore = TlsPasswordStore { "playbridge".toCharArray() }
            val original = TlsIdentity.loadOrCreate(dir, passwordStore = legacyStore)

            val migrated = TlsIdentity.loadOrCreate(dir, passwordStore = passwordStore)

            assertEquals(original.fingerprint, migrated.fingerprint)
            val file = File(dir, "playbridge_tls.p12")
            KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, testPassword) }
                assertNotNull(getKey("playbridge", testPassword))
            }
            val legacyLoad = runCatching {
                KeyStore.getInstance("PKCS12").apply {
                    file.inputStream().use { load(it, "playbridge".toCharArray()) }
                }
            }
            assertTrue("Legacy password must stop opening the migrated keystore", legacyLoad.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
}
