package com.playbridge.sender.data.nuvio

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.io.path.createTempDirectory

class NuvioSecurityPolicyTest {
    @Test
    fun privateAndReservedIpv4AreBlocked() {
        assertTrue(blocked("127.0.0.1"))
        assertTrue(blocked("10.1.2.3"))
        assertTrue(blocked("172.16.0.1"))
        assertTrue(blocked("192.168.1.1"))
        assertTrue(blocked("169.254.169.254"))
        assertTrue(blocked("100.64.0.1"))
        assertTrue(blocked("0.0.0.0"))
        assertTrue(blocked("192.0.2.1"))
        assertTrue(blocked("198.51.100.1"))
        assertTrue(blocked("203.0.113.1"))
        assertTrue(blocked("224.0.0.1"))
        assertFalse(blocked("8.8.8.8"))
        assertFalse(blocked("1.1.1.1"))
    }

    @Test
    fun ipv6AllowsOnlyGlobalUnicastAndBlocksTransitionRanges() {
        assertFalse(NuvioDestinationPolicy.isBlockedAddress(v6(0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0x20, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0x20, 0x02, 0x08, 0x08, 0x08, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(NuvioDestinationPolicy.isBlockedAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)))
        val mapped = NuvioDestinationPolicy.literalAddress("::ffff:8.8.8.8")
        assertTrue(mapped is Inet6Address)
        assertTrue(mapped != null && NuvioDestinationPolicy.isBlockedAddress(mapped))
    }

    @Test
    fun blockedHostnamesIncludeMetadataAndLocalSuffixes() {
        assertTrue(NuvioDestinationPolicy.isBlockedHostname("localhost"))
        assertTrue(NuvioDestinationPolicy.isBlockedHostname("printer.local"))
        assertTrue(NuvioDestinationPolicy.isBlockedHostname("metadata.google.internal"))
        assertTrue(NuvioDestinationPolicy.isBlockedHostname("2130706433"))
        assertFalse(NuvioDestinationPolicy.isBlockedHostname("cdn.example"))
    }

    @Test
    fun httpsDowngradeAndCrossOriginHeadersAreRejectedOrStripped() {
        val https = "https://plugins.example/repo/a".toHttpUrl()
        val http = "http://plugins.example/repo/a".toHttpUrl()
        val otherPort = "https://plugins.example:8443/repo/a".toHttpUrl()
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "sid=secret",
            "X-Api-Key" to "secret-key",
            "Accept" to "application/json",
        )
        assertTrue(NuvioRedirectPolicy.isDowngrade(https, http))
        assertNull(NuvioRedirectPolicy.prepare(https, http, headers, "body", install = false, initial = https))
        val crossPort = NuvioRedirectPolicy.prepare(https, otherPort, headers, "credential-body", install = false, initial = https)
        assertEquals(mapOf("Accept" to "application/json"), crossPort?.headers)
        assertNull(crossPort?.body)
        assertTrue(crossPort?.forceGet == true)
        assertNull(NuvioRedirectPolicy.prepare(https, otherPort, headers, null, install = true, initial = https))
    }

    @Test
    fun scriptPathsRejectTraversalUnicodeNamesAndKnownHashCollisions() {
        val root = tempRoot()
        val store = NuvioScriptStore(root)
        assertNull(NuvioDestinationPolicy.safeScriptFilename("caf\u00e9.js"))
        assertNull(NuvioDestinationPolicy.safeScriptFilename("../evil.js"))
        assertNull(NuvioDestinationPolicy.safeScriptFilename("providers/%2e%2e/castle.js"))
        assertNull(NuvioDestinationPolicy.safeScriptFilename("providers//castle.js"))
        assertNull(NuvioDestinationPolicy.safeScriptFilename("/providers/castle.js"))
        assertNull(NuvioDestinationPolicy.safeScriptFilename("https://evil.example/castle.js"))
        assertNull(NuvioDestinationPolicy.safeScraperId("n\u00fcvio"))
        assertEquals("alpha.js", NuvioDestinationPolicy.safeScriptFilename("alpha.js"))
        assertEquals("providers/castle.js", NuvioDestinationPolicy.safeScriptFilename("providers/castle.js"))
        assertEquals("providers/allanime.js", NuvioDestinationPolicy.safeScriptFilename("providers/allanime.js"))
        assertFalse(store.writeActive("https://plugins.example/a", "../evil", "nope"))
        assertFalse(store.writeActive("https://plugins.example/a", "n\u00fcvio", "nope"))
        assertFalse(File(root.parentFile, "evil.js").exists())
        val left = "https://plugins.example/Aa/manifest.json"
        val right = "https://plugins.example/BB/manifest.json"
        assertEquals(left.hashCode(), right.hashCode())
        assertTrue(NuvioDestinationPolicy.repoDirectoryName(left) != NuvioDestinationPolicy.repoDirectoryName(right))
        val legacy = File(root, NuvioDestinationPolicy.legacyDirectoryName(left))
        legacy.mkdirs()
        File(legacy, "alpha.js").writeText("secret-code")
        assertNull(store.readActive(left, "alpha", listOf(left, right)))
        assertNull(store.readActive(right, "alpha", listOf(left, right)))
        assertTrue(store.writeActive(left, "alpha", "approved"))
        assertEquals("approved", store.readActive(left, "alpha", listOf(left, right)))
        root.deleteRecursively()
    }

    @Test
    fun approvalCapDoesNotDropPendingHost() {
        val root = tempRoot()
        val store = NuvioApprovalStore(root)
        val repo = "https://plugins.example/manifest.json"
        repeat(NuvioLimits.MAX_APPROVED_HOSTS) { index ->
            assertTrue(store.approveHost(repo, "alpha", "cdn$index.example"))
        }
        store.addBlockedHost(repo, "alpha", "waiting.example")
        assertFalse(store.approveHost(repo, "alpha", "waiting.example"))
        assertTrue(store.get(repo, "alpha").blockedHosts.contains("waiting.example"))
        assertEquals(NuvioLimits.MAX_APPROVED_HOSTS, store.get(repo, "alpha").approvedHosts.size)
        root.deleteRecursively()
    }

    @Test
    fun failedApprovalWriteDoesNotReportSuccessOrWipeExisting() {
        val root = tempRoot()
        val store = NuvioApprovalStore(root)
        val repo = "https://plugins.example/manifest.json"
        assertTrue(store.approveHost(repo, "alpha", "cdn0.example"))
        val stateDir = File(root, "state")
        assertTrue(stateDir.setWritable(false))
        try {
            assertFalse(store.approveHost(repo, "alpha", "cdn1.example"))
        } finally {
            stateDir.setWritable(true)
        }
        assertEquals(listOf("cdn0.example"), store.get(repo, "alpha").approvedHosts)
        root.deleteRecursively()
    }

    @Test
    fun oversizedApprovalFileFailsClosed() {
        val root = tempRoot()
        val file = File(root, "state/approvals.json")
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(300 * 1024) { 'x'.code.toByte() })
        val store = NuvioApprovalStore(root)
        assertTrue(store.get("https://plugins.example/manifest.json", "alpha").approvedHosts.isEmpty())
        root.deleteRecursively()
    }

    private fun tempRoot(): File = createTempDirectory("nuvio-security").toFile()

    private fun blocked(literal: String): Boolean =
        NuvioDestinationPolicy.isBlockedAddress(InetAddress.getByName(literal))

    private fun v6(vararg values: Int): InetAddress =
        Inet6Address.getByAddress(null, values.map { it.toByte() }.toByteArray(), -1)

}
