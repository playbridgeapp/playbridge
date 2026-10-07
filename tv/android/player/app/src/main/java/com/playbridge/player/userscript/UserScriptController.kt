package com.playbridge.player.userscript

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Phone install, TV-settings approval, and removal for browser user scripts.
 * Pending files live in a subdirectory the page engine does not scan. A script is
 * moved into the active directory only after the TV owner approves it, and only
 * then is its hash recorded.
 */
class UserScriptController(
    private val scriptsDir: () -> File?,
    private val store: UserScriptApprovalStore,
    private val publishPrompt: (UserScriptReview, CompletableDeferred<Boolean>) -> Unit,
    private val clearPrompt: () -> Unit,
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = USER_SCRIPT_APPROVAL_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var promptBusy = false
    private var activeName: String? = null
    private var activeDecision: CompletableDeferred<Boolean>? = null
    private val superseded = mutableSetOf<String>()

    fun ensureMigrated() {
        if (store.isMigrationComplete()) return
        val dir = scriptsDir() ?: return
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".js") } ?: return
        UserScriptMigration.apply(store, files.isNotEmpty())
    }

    fun isInstallEnabled(): Boolean = store.isInstallEnabled()

    fun setInstallEnabled(enabled: Boolean) {
        store.setInstallEnabled(enabled)
    }

    fun listInstalled(): List<InstalledScriptSummary> {
        val dir = scriptsDir() ?: return emptyList()
        val approvals = store.all()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".js") }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> summary(file, approvals[file.name]) }
            ?: emptyList()
    }

    fun completeActive(approved: Boolean) {
        activeDecision?.complete(approved)
    }

    suspend fun onPhoneMessage(senderName: String?, rawName: String, content: String): PhoneScriptResult {
        ensureMigrated()
        val safeName = UserScriptNames.sanitize(rawName)
        if (content.isBlank()) {
            return uninstall(safeName)
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = UserScriptHashes.sha256(bytes)
        val refusal = mutex.withLock {
            val decision = UserScriptInstallPolicy.refusal(store.isInstallEnabled(), promptBusy)
            if (decision == null) {
                promptBusy = true
                superseded.remove(safeName)
            }
            decision
        }
        if (refusal == PhoneInstallRefusal.TOGGLE_OFF) {
            logEvent("install refused (toggle off)", safeName, bytes.size, hash, senderName)
            return PhoneScriptResult.REFUSED_TOGGLE_OFF
        }
        if (refusal == PhoneInstallRefusal.PROMPT_BUSY) {
            logEvent("install rejected (prompt busy)", safeName, bytes.size, hash, senderName)
            return PhoneScriptResult.REJECTED_BUSY
        }
        val pending = pendingFile(safeName)
        return try {
            pending?.parentFile?.mkdirs()
            pending?.writeBytes(bytes)
            val header = UserScriptHeaderParser.parse(content)
            val review = UserScriptReview(
                senderName = senderName?.takeIf { it.isNotBlank() },
                scriptName = safeName,
                sizeBytes = bytes.size,
                hashPrefix = UserScriptHashes.prefix(hash),
                matches = header.matchPatterns.map { it.raw },
                runsOnAllSites = header.runsOnAllSites,
            )
            val decision = CompletableDeferred<Boolean>()
            mutex.withLock {
                activeName = safeName
                activeDecision = decision
            }
            logEvent("approval prompt", safeName, bytes.size, hash, senderName)
            publishPrompt(review, decision)
            val approved = try {
                withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
            } finally {
                clearPrompt()
                if (!decision.isCompleted) decision.complete(false)
            }
            commitInstall(safeName, bytes, hash, header, approved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            pending?.delete()
            throw e
        } finally {
            mutex.withLock {
                promptBusy = false
                if (activeName == safeName) {
                    activeName = null
                    activeDecision = null
                }
            }
        }
    }

    suspend fun approveInstalled(name: String): Boolean = mutex.withLock {
        val safeName = UserScriptNames.sanitize(name)
        val file = activeFile(safeName) ?: return@withLock false
        if (!file.isFile) return@withLock false
        val bytes = file.readBytes()
        val header = UserScriptHeaderParser.parse(bytes.toString(Charsets.UTF_8))
        store.put(safeName, approvalRecord(bytes, header))
        logEvent("approved from settings", safeName, bytes.size, UserScriptHashes.sha256(bytes), null)
        true
    }

    suspend fun removeInstalled(name: String): Boolean {
        val safeName = UserScriptNames.sanitize(name)
        return uninstall(safeName) == PhoneScriptResult.UNINSTALLED
    }

    private suspend fun uninstall(safeName: String): PhoneScriptResult {
        mutex.withLock {
            superseded += safeName
            if (activeName == safeName) activeDecision?.complete(false)
            val removed = activeFile(safeName)?.delete() == true
            pendingFile(safeName)?.delete()
            store.remove(safeName)
            log("User script uninstall: name=$safeName removed=$removed")
        }
        return PhoneScriptResult.UNINSTALLED
    }

    private suspend fun commitInstall(
        safeName: String,
        bytes: ByteArray,
        hash: String,
        header: ParsedHeader,
        approved: Boolean,
    ): PhoneScriptResult = mutex.withLock {
        val pending = pendingFile(safeName)
        val aborted = safeName in superseded
        superseded.remove(safeName)
        if (!approved || aborted || pending == null || !pending.isFile || !pending.readBytes().contentEquals(bytes)) {
            pending?.delete()
            logEvent(if (aborted) "install aborted" else "denied", safeName, bytes.size, hash, null)
            return@withLock PhoneScriptResult.DENIED
        }
        val target = activeFile(safeName) ?: run {
            pending.delete()
            logEvent("install failed (no scripts dir)", safeName, bytes.size, hash, null)
            return@withLock PhoneScriptResult.DENIED
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        pending.delete()
        store.put(safeName, approvalRecord(bytes, header))
        logEvent("approved", safeName, bytes.size, hash, null)
        PhoneScriptResult.INSTALLED
    }

    private fun approvalRecord(bytes: ByteArray, header: ParsedHeader): StoredApproval = StoredApproval(
        sha256 = UserScriptHashes.sha256(bytes),
        matches = header.rawMatches,
        runsOnAllSites = header.runsOnAllSites,
        approvedAt = clock(),
    )

    private fun summary(file: File, record: StoredApproval?): InstalledScriptSummary? {
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        val hash = UserScriptHashes.sha256(bytes)
        val header = UserScriptHeaderParser.parse(bytes.toString(Charsets.UTF_8))
        return InstalledScriptSummary(
            name = file.name,
            sizeBytes = bytes.size,
            hashPrefix = UserScriptHashes.prefix(hash),
            matches = header.matchPatterns.map { it.raw },
            runsOnAllSites = header.runsOnAllSites,
            state = UserScriptGate.state(hash, record),
        )
    }

    private fun activeFile(name: String): File? {
        val dir = scriptsDir() ?: return null
        return File(dir, name)
    }

    private fun pendingFile(name: String): File? {
        val dir = scriptsDir() ?: return null
        return File(File(dir, USER_SCRIPT_PENDING_DIR), name)
    }

    private fun logEvent(action: String, name: String, size: Int, hash: String, sender: String?) {
        val from = sender?.takeIf { it.isNotBlank() }?.let { " from=$it" }.orEmpty()
        log("User script $action: name=$name size=$size hash=${UserScriptHashes.prefix(hash)}$from")
    }
}
