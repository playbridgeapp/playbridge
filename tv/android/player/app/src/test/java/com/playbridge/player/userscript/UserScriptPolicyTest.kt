package com.playbridge.player.userscript

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserScriptPolicyTest {
    private val scoped = """
        // ==UserScript==
        // @match https://example.com/*
        // ==/UserScript==
        console.log("scoped");
    """.trimIndent()

    private val allSites = "console.log('everywhere');"

    @Test
    fun `hash gate allows only the approved bytes on a matching page`() {
        val bytes = scoped.toByteArray()
        val record = approval(bytes, runsOnAllSites = false, matches = listOf("https://example.com/*"))

        assertEquals(ScriptApprovalState.APPROVED, UserScriptGate.state(UserScriptHashes.sha256(bytes), record))
        assertTrue(UserScriptGate.mayInject(bytes, "https://example.com/watch", record))
        assertFalse(UserScriptGate.mayInject(bytes, "https://other.example/watch", record))
        assertFalse(UserScriptGate.mayInject(bytes, "http://example.com/watch", record))
    }

    @Test
    fun `changed and unknown scripts do not run`() {
        val bytes = scoped.toByteArray()
        val record = approval(bytes, runsOnAllSites = false, matches = listOf("https://example.com/*"))
        val changed = (scoped + "\nconsole.log('edited');").toByteArray()

        assertEquals(ScriptApprovalState.CHANGED, UserScriptGate.state(UserScriptHashes.sha256(changed), record))
        assertEquals(ScriptApprovalState.NEEDS_APPROVAL, UserScriptGate.state(UserScriptHashes.sha256(bytes), null))
        assertFalse(UserScriptGate.mayInject(changed, "https://example.com/watch", record))
        assertFalse(UserScriptGate.mayInject(bytes, "https://example.com/watch", null))
        assertFalse(UserScriptGate.mayInject(bytes, "", record))
    }

    @Test
    fun `all sites approval injects everywhere and a stored flag cannot widen a scoped script`() {
        val bytes = allSites.toByteArray()
        val record = approval(bytes, runsOnAllSites = true, matches = emptyList())

        assertTrue(UserScriptGate.mayInject(bytes, "https://anywhere.example/x", record))
        assertTrue(UserScriptGate.mayInject(bytes, "http://192.168.1.9/a", record))

        val scopedBytes = scoped.toByteArray()
        val widened = approval(scopedBytes, runsOnAllSites = true, matches = emptyList())
        assertFalse(UserScriptGate.mayInject(scopedBytes, "https://other.example/", widened))
        assertTrue(UserScriptGate.mayInject(scopedBytes, "https://example.com/a", widened))
    }

    @Test
    fun `invalid match directives never become an all-sites script`() {
        val content = """
            // ==UserScript==
            // @match not-a-pattern
            // ==/UserScript==
            console.log(1);
        """.trimIndent()
        val bytes = content.toByteArray()
        val record = approval(bytes, runsOnAllSites = false, matches = listOf("not-a-pattern"))

        assertFalse(UserScriptGate.mayInject(bytes, "https://example.com/", record))
    }

    @Test
    fun `sha256 prefix is the first 12 hex chars`() {
        val hash = UserScriptHashes.sha256("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals("ba7816bf8f01", UserScriptHashes.prefix(hash))
    }

    @Test
    fun `toggle off refuses even when no prompt is active`() {
        assertEquals(PhoneInstallRefusal.TOGGLE_OFF, UserScriptInstallPolicy.refusal(false, false))
        assertEquals(PhoneInstallRefusal.TOGGLE_OFF, UserScriptInstallPolicy.refusal(false, true))
        assertEquals(PhoneInstallRefusal.PROMPT_BUSY, UserScriptInstallPolicy.refusal(true, true))
        assertNull(UserScriptInstallPolicy.refusal(true, false))
    }

    @Test
    fun `migration enables the toggle only for an upgrade that already has scripts`() {
        assertEquals(
            UserScriptMigration.Decision(markComplete = true, enableToggle = true),
            UserScriptMigration.decide(alreadyMigrated = false, hasExistingScripts = true),
        )
        assertEquals(
            UserScriptMigration.Decision(markComplete = true, enableToggle = false),
            UserScriptMigration.decide(alreadyMigrated = false, hasExistingScripts = false),
        )
        assertEquals(
            UserScriptMigration.Decision(markComplete = true, enableToggle = false),
            UserScriptMigration.decide(alreadyMigrated = true, hasExistingScripts = true),
        )

        val upgraded = MemoryUserScriptStore(installEnabled = false, migrated = false)
        UserScriptMigration.apply(upgraded, hasExistingScripts = true)
        assertTrue(upgraded.isInstallEnabled())
        assertTrue(upgraded.isMigrationComplete())

        val fresh = MemoryUserScriptStore()
        UserScriptMigration.apply(fresh, hasExistingScripts = false)
        assertFalse(fresh.isInstallEnabled())
        assertTrue(fresh.isMigrationComplete())

        val turnedOff = MemoryUserScriptStore(installEnabled = false, migrated = true)
        UserScriptMigration.apply(turnedOff, hasExistingScripts = true)
        assertFalse(turnedOff.isInstallEnabled())
    }

    @Test
    fun `approval snapshot round trip keeps the hash gate`() {
        val record = StoredApproval(
            sha256 = "abc",
            matches = listOf("https://example.com/*"),
            runsOnAllSites = false,
            approvedAt = 42L,
        )
        val decoded = UserScriptApprovals.decode(UserScriptApprovals.encode(mapOf("clip.js" to record)))
        assertEquals(record, decoded["clip.js"])
        assertTrue(UserScriptApprovals.decode("not json").isEmpty())
    }

    @Test
    fun `toggle off does not write a pending or active script`() = runBlocking {
        val root = Files.createTempDirectory("userscripts").toFile()
        val store = MemoryUserScriptStore(installEnabled = false, migrated = true)
        var prompts = 0
        val controller = controller(root, store) {
            prompts++
            true
        }
        val result = controller.onPhoneMessage("Pixel", "clip.js", scoped)

        assertEquals(PhoneScriptResult.REFUSED_TOGGLE_OFF, result)
        assertEquals(0, prompts)
        assertTrue(root.listFiles().isNullOrEmpty())
        assertNull(store.get("clip.js"))
    }

    @Test
    fun `a second install is rejected and does not replace the pending file`() = runBlocking {
        val root = Files.createTempDirectory("userscripts").toFile()
        val store = MemoryUserScriptStore(installEnabled = true, migrated = true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        val controller = UserScriptController(
            scriptsDir = { root },
            store = store,
            publishPrompt = { _, decision ->
                started.complete(Unit)
                release.invokeOnCompletion { if (!decision.isCompleted) decision.complete(false) }
            },
            clearPrompt = {},
        )
        val first = async { controller.onPhoneMessage("Pixel", "clip.js", scoped) }
        started.await()
        val second = controller.onPhoneMessage("Other", "other.js", allSites)
        release.complete(false)
        assertEquals(PhoneScriptResult.DENIED, first.await())
        assertEquals(PhoneScriptResult.REJECTED_BUSY, second)
        assertFalse(File(root, "other.js").exists())
        assertFalse(File(File(root, USER_SCRIPT_PENDING_DIR), "other.js").exists())
    }

    @Test
    fun `approve moves the pending file and records the hash deny deletes it`() = runBlocking {
        val root = Files.createTempDirectory("userscripts").toFile()
        val store = MemoryUserScriptStore(installEnabled = true, migrated = true)
        val approving = controller(root, store) { true }
        assertEquals(PhoneScriptResult.INSTALLED, approving.onPhoneMessage("Pixel", "clip", scoped))

        val installed = File(root, "clip.js")
        assertTrue(installed.isFile)
        assertFalse(File(File(root, USER_SCRIPT_PENDING_DIR), "clip.js").exists())
        val record = store.get("clip.js")
        assertEquals(UserScriptHashes.sha256(scoped.toByteArray()), record?.sha256)
        assertEquals(listOf("https://example.com/*"), record?.matches)
        assertFalse(record!!.runsOnAllSites)
        assertEquals(ScriptApprovalState.APPROVED, approving.listInstalled().single().state)

        val denying = controller(root, store) { false }
        assertEquals(PhoneScriptResult.DENIED, denying.onPhoneMessage("Pixel", "other.js", allSites))
        assertFalse(File(root, "other.js").exists())
        assertNull(store.get("other.js"))
        assertTrue(installed.isFile)
    }

    @Test
    fun `blank content uninstalls without approval and without the toggle`() = runBlocking {
        val root = Files.createTempDirectory("userscripts").toFile()
        val store = MemoryUserScriptStore(installEnabled = false, migrated = true)
        File(root, "clip.js").writeText(scoped)
        store.put("clip.js", approval(scoped.toByteArray(), false, listOf("https://example.com/*")))
        val controller = controller(root, store) { error("prompt should not run") }

        assertEquals(PhoneScriptResult.UNINSTALLED, controller.onPhoneMessage("Pixel", "clip.js", "  "))
        assertFalse(File(root, "clip.js").exists())
        assertNull(store.get("clip.js"))
    }

    private fun approval(bytes: ByteArray, runsOnAllSites: Boolean, matches: List<String>) = StoredApproval(
        sha256 = UserScriptHashes.sha256(bytes),
        matches = matches,
        runsOnAllSites = runsOnAllSites,
        approvedAt = 1L,
    )

    private fun controller(
        root: java.io.File,
        store: UserScriptApprovalStore = MemoryUserScriptStore(installEnabled = false, migrated = true),
        prompt: suspend (UserScriptReview) -> Boolean,
    ): UserScriptController = UserScriptController(
        scriptsDir = { root },
        store = store,
        publishPrompt = { review, decision ->
            decision.complete(runBlocking { prompt(review) })
        },
        clearPrompt = {},
    )
}
