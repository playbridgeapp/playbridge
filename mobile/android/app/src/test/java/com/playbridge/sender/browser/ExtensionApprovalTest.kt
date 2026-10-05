package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionApprovalTest {
    private val owner = Any()
    private fun coordinator() = ExtensionApprovalCoordinator().apply { attach(owner) }
    private fun request(kind: ExtensionApprovalKind = ExtensionApprovalKind.INSTALL) = ExtensionApprovalRequest(
        kind = kind, extensionId = "signed@example", name = "Signed extension",
        permissions = listOf("tabs", "cookies"), origins = listOf("<all_urls>"),
        dataCollection = listOf("technicalAndInteraction"),
    )

    @Test fun websiteDownloadDoesNotInvokeInstallerWithoutNativeApproval() {
        for ((url, type) in listOf(
            "https://example.com/addon.xpi" to null,
            "https://example.com/download" to "application/x-xpinstall",
        )) {
            assertTrue(isExtensionDownload(url, type))
            val gate = coordinator()
            var installs = 0
            val pending = gate.requestWebsiteInstall(url, { true }) { installs++ }!!
            assertEquals(0, installs)
            gate.decide(pending.id, ExtensionApprovalDecision())
            assertEquals(0, installs)
            assertNull(gate.pending)
        }
    }

    @Test fun websiteApprovalDownloadsButInstallationNeedsSeparatePermissionApproval() {
        val gate = coordinator()
        var downloads = 0
        var installed = false
        val download = gate.requestWebsiteInstall("https://example.com/addon.xpi", { true }) {
            downloads++
            gate.request(request()) { installed = it.allowed }
        }!!
        assertEquals(0, downloads)
        gate.decide(download.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(1, downloads)
        assertFalse(installed)
        val permissions = gate.pending!!
        assertEquals("signed@example", permissions.request.extensionId)
        assertEquals(listOf("cookies", "tabs"), permissions.request.permissions.sorted())
        assertEquals(listOf("<all_urls>"), permissions.request.origins)
        gate.decide(permissions.id, ExtensionApprovalDecision(allowed = true))
        assertTrue(installed)
    }

    @Test fun denyingPermissionPromptNeverCompletesInstallation() {
        val gate = coordinator()
        var installed = false
        val pending = gate.request(request()) { installed = it.allowed }!!
        gate.decide(pending.id, ExtensionApprovalDecision())
        assertFalse(installed)
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertFalse(installed)
    }

    @Test fun privateModeAndTechnicalDataAreNotGrantedByDefault() {
        val gate = coordinator()
        var result: ExtensionApprovalDecision? = null
        val pending = gate.request(request()) { result = it }!!
        assertNull(result)
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(ExtensionApprovalDecision(allowed = true), result)
    }

    @Test fun sensitivePermissionsRequireExplicitInstallOptIn() {
        val gate = coordinator()
        var result: ExtensionApprovalDecision? = null
        val pending = gate.request(request()) { result = it }!!
        gate.decide(pending.id, ExtensionApprovalDecision(true, true, true))
        assertEquals(ExtensionApprovalDecision(true, true, true), result)
        val withoutData = gate.request(request().copy(dataCollection = emptyList())) { result = it }!!
        gate.decide(withoutData.id, ExtensionApprovalDecision(true, true, true))
        assertEquals(ExtensionApprovalDecision(true, true, false), result)
    }

    @Test fun updateAndOptionalPermissionsRequireIndependentApproval() {
        for (kind in listOf(ExtensionApprovalKind.UPDATE, ExtensionApprovalKind.OPTIONAL)) {
            val gate = coordinator()
            val results = mutableListOf<ExtensionApprovalDecision>()
            val denied = gate.request(request(kind), onDecision = results::add)!!
            assertTrue(results.isEmpty())
            gate.decide(denied.id, ExtensionApprovalDecision())
            assertEquals(listOf(ExtensionApprovalDecision()), results)
            val approved = gate.request(request(kind), onDecision = results::add)!!
            gate.decide(approved.id, ExtensionApprovalDecision(true, true, true))
            assertEquals(ExtensionApprovalDecision(allowed = true), results.last())
        }
    }

    @Test fun foregroundHostIsRequiredForEveryPermissionRequest() {
        val gate = ExtensionApprovalCoordinator()
        for (kind in ExtensionApprovalKind.entries) {
            var result: ExtensionApprovalDecision? = null
            assertNull(gate.request(request(kind)) { result = it })
            assertEquals(ExtensionApprovalDecision(), result)
        }
        var downloads = 0
        assertNull(gate.requestWebsiteInstall("https://example.com/addon.xpi", { true }) { downloads++ })
        assertEquals(0, downloads)
    }

    @Test fun pauseAndActivityReplacementDenyPendingAndIgnoreLateApproval() {
        val gate = coordinator()
        val results = mutableListOf<ExtensionApprovalDecision>()
        val old = gate.request(request(), onDecision = results::add)!!
        gate.detach(owner)
        assertEquals(listOf(ExtensionApprovalDecision()), results)
        val replacement = Any()
        gate.attach(replacement)
        val current = gate.request(request(), onDecision = results::add)!!
        gate.decide(old.id, ExtensionApprovalDecision(true, true, true))
        assertEquals(current.id, gate.pending!!.id)
        gate.detach(owner) // Old Activity teardown cannot dismiss the replacement's prompt.
        assertEquals(current.id, gate.pending!!.id)
        gate.decide(current.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(listOf(ExtensionApprovalDecision(), ExtensionApprovalDecision(true)), results)
    }

    @Test fun changingOwnerCancelsWithoutGranting() {
        val gate = coordinator()
        var result: ExtensionApprovalDecision? = null
        val pending = gate.request(request()) { result = it }!!
        gate.attach(Any())
        gate.decide(pending.id, ExtensionApprovalDecision(true, true, true))
        assertEquals(ExtensionApprovalDecision(), result)
    }

    @Test fun parallelRequestsCannotReplacePendingIdentityOrScopes() {
        val gate = coordinator()
        var first: ExtensionApprovalDecision? = null
        var second: ExtensionApprovalDecision? = null
        val pending = gate.request(request()) { first = it }!!
        assertNull(gate.request(request().copy(extensionId = "other@example")) { second = it })
        assertNull(first)
        assertEquals(ExtensionApprovalDecision(), second)
        assertEquals(pending, gate.pending)
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(ExtensionApprovalDecision(allowed = true), first)
    }

    @Test fun closedOrNavigatedWebsiteCannotApproveAnOldDownload() {
        val gate = coordinator()
        var current = true
        var downloads = 0
        val pending = gate.requestWebsiteInstall("https://example.com/addon.xpi", { current }) { downloads++ }!!
        current = false
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(0, downloads)
        assertNull(gate.requestWebsiteInstall("https://example.com/other.xpi", { false }) { downloads++ })
        assertEquals(0, downloads)
    }

    @Test fun predicateFailuresFailClosed() {
        val gate = coordinator()
        var result: ExtensionApprovalDecision? = null
        assertNull(gate.request(request(), { error("no document") }) { result = it })
        assertEquals(ExtensionApprovalDecision(), result)
        var fail = false
        val pending = gate.request(request(), { if (fail) error("no document") else true }) { result = it }!!
        fail = true
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(ExtensionApprovalDecision(), result)
    }

    @Test fun scopesAreSnapshottedAndCallbacksCompleteOnce() {
        val gate = coordinator()
        val permissions = mutableListOf("tabs")
        var calls = 0
        val pending = gate.request(request().copy(permissions = permissions)) { calls++ }!!
        permissions += "cookies"
        assertEquals(listOf("tabs"), pending.request.permissions)
        gate.decide(pending.id + 100, ExtensionApprovalDecision(allowed = true))
        assertEquals(0, calls)
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        gate.decide(pending.id, ExtensionApprovalDecision())
        assertEquals(1, calls)
    }

    @Test fun nativeUblockSetupUsesSameConsentGateRatherThanIdBasedAutoGrant() {
        val gate = coordinator()
        var result: ExtensionApprovalDecision? = null
        val pending = gate.request(request().copy(
            extensionId = "uBlock0@raymondhill.net", name = "uBlock Origin",
            source = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
            dataCollection = emptyList(),
        )) { result = it }!!
        assertNull(result)
        gate.decide(pending.id, ExtensionApprovalDecision(allowed = true))
        assertEquals(ExtensionApprovalDecision(allowed = true), result)
    }

    @Test fun extensionDownloadsRecognizeMimeSuffixQueryAndFilename() {
        assertTrue(isExtensionDownload("https://example.com/addon.XPI?download=1", null))
        assertTrue(isExtensionDownload("https://example.com/download", " Application/X-Xpinstall; charset=binary "))
        assertTrue(isExtensionDownload("https://example.com/download", null, "extension.xpi"))
        assertFalse(isExtensionDownload("https://example.com/video.mp4?name=addon.xpi", "video/mp4"))
        assertFalse(isExtensionDownload("https://example.com/file.torrent", "application/x-bittorrent"))
        assertFalse(isExtensionDownload("invalid URL", null))
    }

    @Test fun sourceDisplayOmitsSecretsAndRejectsNonWebSources() {
        assertEquals("https://example.com/addon.xpi", extensionDownloadSource("https://example.com/addon.xpi?token=secret#secret"))
        assertEquals("http://127.0.0.1:8080/addon.xpi", extensionDownloadSource("http://127.0.0.1:8080/addon.xpi"))
        for (url in listOf("file:///tmp/addon.xpi", "content://downloads/addon.xpi", "data:application/x-xpinstall,abc",
            "javascript:alert(1)", "https://user:secret@example.com/addon.xpi", "https://", "invalid URL")) {
            assertNull(extensionDownloadSource(url))
            val gate = coordinator()
            var downloads = 0
            assertNull(gate.requestWebsiteInstall(url, { true }) { downloads++ })
            assertEquals(0, downloads)
            assertNull(gate.pending)
        }
    }
}
