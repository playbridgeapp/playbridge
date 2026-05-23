package com.playbridge.player.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TlsIdentityTest {

    private fun tempDir(): File = Files.createTempDirectory("pb_tls_test").toFile()

    @Test
    fun generatesCertWithSha256SpkiPin() {
        val dir = tempDir()
        try {
            val r = TlsIdentity.loadOrCreate(dir)
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
            val first = TlsIdentity.loadOrCreate(dir)
            val second = TlsIdentity.loadOrCreate(dir)
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
                TlsIdentity.loadOrCreate(a).fingerprint,
                TlsIdentity.loadOrCreate(b).fingerprint,
            )
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }
}
