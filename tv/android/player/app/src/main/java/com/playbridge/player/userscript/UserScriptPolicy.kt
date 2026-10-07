package com.playbridge.player.userscript

import com.playbridge.shared.protocol.protocolJson
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** First 12 hex chars of the SHA-256, shown on the TV and written to logs. Never log the script. */
const val USER_SCRIPT_HASH_PREFIX_LENGTH = 12

const val USER_SCRIPT_PREFS = "user_scripts"
const val USER_SCRIPT_SNAPSHOT = "user_script_approvals.json"
const val USER_SCRIPT_PENDING_DIR = "user-script-pending"
const val USER_SCRIPT_APPROVAL_TIMEOUT_MS = 60_000L

enum class ScriptApprovalState {
    APPROVED,
    NEEDS_APPROVAL,
    CHANGED,
}

enum class PhoneInstallRefusal {
    TOGGLE_OFF,
    PROMPT_BUSY,
}

enum class PhoneScriptResult {
    INSTALLED,
    DENIED,
    UNINSTALLED,
    REFUSED_TOGGLE_OFF,
    REJECTED_BUSY,
}

/**
 * What the TV shows before a script is allowed to run. [matches] are the valid `@match`
 * patterns. [runsOnAllSites] is true only when the script declared no `@match` lines.
 */
data class UserScriptReview(
    val senderName: String?,
    val scriptName: String,
    val sizeBytes: Int,
    val hashPrefix: String,
    val matches: List<String>,
    val runsOnAllSites: Boolean,
)

data class InstalledScriptSummary(
    val name: String,
    val sizeBytes: Int,
    val hashPrefix: String,
    val matches: List<String>,
    val runsOnAllSites: Boolean,
    val state: ScriptApprovalState,
) {
    fun toReview(senderName: String?): UserScriptReview = UserScriptReview(
        senderName = senderName,
        scriptName = name,
        sizeBytes = sizeBytes,
        hashPrefix = hashPrefix,
        matches = matches,
        runsOnAllSites = runsOnAllSites,
    )
}

@Serializable
data class StoredApproval(
    val sha256: String,
    val matches: List<String> = emptyList(),
    val runsOnAllSites: Boolean = false,
    val approvedAt: Long = 0L,
)

@Serializable
data class ApprovalSnapshot(
    val scripts: Map<String, StoredApproval> = emptyMap(),
)

data class ParsedHeader(
    val matchPatterns: List<MatchPattern>,
    /** Every `@match` value inside the header, including ones that are not valid patterns. */
    val rawMatches: List<String>,
    /** True only when the script has no `@match` lines. Invalid-only lines are not all-sites. */
    val runsOnAllSites: Boolean,
)

object UserScriptHashes {
    fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun prefix(hash: String): String = hash.take(USER_SCRIPT_HASH_PREFIX_LENGTH)
}

object UserScriptNames {
    fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        val withExt = when {
            cleaned.isBlank() || cleaned == "." || cleaned == ".." -> "user.js"
            cleaned.endsWith(".js") -> cleaned
            else -> "$cleaned.js"
        }
        if (withExt == "." || withExt == ".." || withExt.contains('/') || withExt.contains('\\')) {
            return "user.js"
        }
        return withExt
    }
}

/**
 * Chrome / Tampermonkey `@match` patterns: scheme `*`/`http`/`https`, host `*` or
 * `*.example.com` or exact, path glob where `*` matches any string including `/`.
 * Query and fragment are not part of the match. `*` scheme is http or https only.
 */
data class MatchPattern(
    val raw: String,
    val scheme: String,
    val host: String,
    val path: String,
) {
    fun matches(url: String): Boolean {
        val page = parsePageUrl(url) ?: return false
        if (!schemeMatches(scheme, page.scheme)) return false
        if (!hostMatches(host, page.host)) return false
        return pathMatches(path, page.path)
    }

    companion object {
        fun parse(raw: String): MatchPattern? {
            val trimmed = raw.trim()
            if (trimmed.equals("<all_urls>", ignoreCase = true)) {
                return MatchPattern(trimmed, "*", "*", "/*")
            }
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep <= 0) return null
            val scheme = trimmed.substring(0, schemeSep).lowercase(Locale.ROOT)
            if (scheme != "*" && scheme != "http" && scheme != "https") return null
            val rest = trimmed.substring(schemeSep + 3)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val host = rest.substring(0, slash).lowercase(Locale.ROOT)
            val path = rest.substring(slash)
            if (!isValidHost(host) || !isValidPath(path)) return null
            return MatchPattern(trimmed, scheme, host, path)
        }

        internal fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.contains('/') || host.contains(':') || host.contains(' ')) return false
            if (host == "*") return true
            if ('*' in host) {
                if (!host.startsWith("*.")) return false
                val suffix = host.substring(2)
                if (suffix.isEmpty() || '*' in suffix || '.' !in suffix) return false
                return true
            }
            return true
        }

        internal fun isValidPath(path: String): Boolean = path.startsWith("/") && '\n' !in path && '\r' !in path
    }
}

internal data class PageUrl(val scheme: String, val host: String, val path: String)

internal fun parsePageUrl(raw: String): PageUrl? {
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        null
    }
    if (uri != null && !uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return PageUrl(uri.scheme.lowercase(Locale.ROOT), uri.host.lowercase(Locale.ROOT), path)
    }
    val match = FALLBACK_URL.find(raw) ?: return null
    val scheme = match.groupValues[1].lowercase(Locale.ROOT)
    var hostPort = match.groupValues[2]
    val at = hostPort.lastIndexOf('@')
    if (at >= 0) hostPort = hostPort.substring(at + 1)
    val host = hostPort.substringBeforeLast(':').takeIf { hostPort.count { it == ':' } == 1 }
        ?: hostPort.substringBefore(':')
    if (host.isBlank()) return null
    val path = match.groupValues[3].ifEmpty { "/" }
    return PageUrl(scheme, host.lowercase(Locale.ROOT), path)
}

private val FALLBACK_URL = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]+)([^?#]*)""")

internal fun schemeMatches(patternScheme: String, urlScheme: String): Boolean {
    val scheme = urlScheme.lowercase(Locale.ROOT)
    return when (patternScheme) {
        "*" -> scheme == "http" || scheme == "https"
        else -> scheme == patternScheme
    }
}

internal fun hostMatches(patternHost: String, urlHost: String): Boolean {
    val host = urlHost.lowercase(Locale.ROOT)
    if (patternHost == "*") return true
    if (patternHost.startsWith("*.")) {
        val suffix = patternHost.substring(2)
        return host == suffix || host.endsWith(".$suffix")
    }
    return host == patternHost
}

/** `*` matches any sequence, including empty and `/`. Other characters are literal. */
internal fun pathMatches(pattern: String, path: String): Boolean {
    var p = 0
    var t = 0
    var starP = -1
    var starT = -1
    while (t < path.length) {
        if (p < pattern.length && pattern[p] == path[t]) {
            p++
            t++
            continue
        }
        if (p < pattern.length && pattern[p] == '*') {
            starP = p
            starT = t
            p++
            continue
        }
        if (starP >= 0) {
            p = starP + 1
            starT++
            t = starT
            continue
        }
        return false
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}

object UserScriptHeaderParser {
    private val headerStart = Regex("""^//\s*==UserScript==\s*$""")
    private val headerEnd = Regex("""^//\s*==/UserScript==\s*$""")
    private val matchLine = Regex("""^//\s*@match(?:\s+(.*))?$""")

    fun parse(content: String): ParsedHeader {
        val lines = content.removePrefix("\uFEFF").split('\n')
        var inHeader = false
        var sawMatch = false
        val raw = mutableListOf<String>()
        val patterns = mutableListOf<MatchPattern>()
        for (rawLine in lines) {
            val line = rawLine.trim().removeSuffix("\r")
            if (!inHeader) {
                if (headerStart.matches(line)) inHeader = true
                continue
            }
            if (headerEnd.matches(line)) break
            val found = matchLine.matchEntire(line) ?: continue
            val value = found.groupValues[1].trim()
            if (value.isEmpty()) continue
            sawMatch = true
            raw += value
            MatchPattern.parse(value)?.let { patterns += it }
        }
        return ParsedHeader(
            matchPatterns = patterns,
            rawMatches = raw,
            runsOnAllSites = !sawMatch,
        )
    }
}

object UserScriptGate {
    fun state(contentHash: String, record: StoredApproval?): ScriptApprovalState {
        if (record == null) return ScriptApprovalState.NEEDS_APPROVAL
        return if (record.sha256.equals(contentHash, ignoreCase = true)) {
            ScriptApprovalState.APPROVED
        } else {
            ScriptApprovalState.CHANGED
        }
    }

    /**
     * A script runs only when the file bytes hash to the approved value and the top-level
     * page matches a `@match` pattern. No `@match` means all pages, which is only reachable
     * after the TV owner approved that exact content.
     * The header is re-parsed from the approved bytes so a widened stored flag cannot
     * expand where the script runs.
     */
    fun mayInject(content: ByteArray, pageUrl: String, record: StoredApproval?): Boolean {
        if (record == null || pageUrl.isBlank()) return false
        val hash = UserScriptHashes.sha256(content)
        if (!record.sha256.equals(hash, ignoreCase = true)) return false
        val header = UserScriptHeaderParser.parse(content.toString(Charsets.UTF_8))
        if (header.runsOnAllSites) return true
        return header.matchPatterns.any { it.matches(pageUrl) }
    }
}

object UserScriptInstallPolicy {
    fun refusal(toggleEnabled: Boolean, promptInProgress: Boolean): PhoneInstallRefusal? {
        if (!toggleEnabled) return PhoneInstallRefusal.TOGGLE_OFF
        if (promptInProgress) return PhoneInstallRefusal.PROMPT_BUSY
        return null
    }
}

object UserScriptMigration {
    data class Decision(val markComplete: Boolean, val enableToggle: Boolean)

    fun decide(alreadyMigrated: Boolean, hasExistingScripts: Boolean): Decision {
        if (alreadyMigrated) return Decision(markComplete = true, enableToggle = false)
        return Decision(markComplete = true, enableToggle = hasExistingScripts)
    }

    /** First launch after upgrade: enable the toggle only when scripts are already on disk. */
    fun apply(store: UserScriptApprovalStore, hasExistingScripts: Boolean) {
        if (store.isMigrationComplete()) return
        val decision = decide(alreadyMigrated = false, hasExistingScripts = hasExistingScripts)
        if (decision.enableToggle) store.setInstallEnabled(true)
        if (decision.markComplete) store.markMigrationComplete()
    }
}

interface UserScriptApprovalStore {
    fun isInstallEnabled(): Boolean
    fun setInstallEnabled(enabled: Boolean)
    fun isMigrationComplete(): Boolean
    fun markMigrationComplete()
    fun get(name: String): StoredApproval?
    fun put(name: String, record: StoredApproval)
    fun remove(name: String)
    fun all(): Map<String, StoredApproval>
}

class MemoryUserScriptStore(
    installEnabled: Boolean = false,
    migrated: Boolean = false,
) : UserScriptApprovalStore {
    private val records = linkedMapOf<String, StoredApproval>()
    private var enabled = installEnabled
    private var migrationComplete = migrated

    override fun isInstallEnabled(): Boolean = enabled
    override fun setInstallEnabled(enabled: Boolean) { this.enabled = enabled }
    override fun isMigrationComplete(): Boolean = migrationComplete
    override fun markMigrationComplete() { migrationComplete = true }
    override fun get(name: String): StoredApproval? = records[name]
    override fun put(name: String, record: StoredApproval) { records[name] = record }
    override fun remove(name: String) { records.remove(name) }
    override fun all(): Map<String, StoredApproval> = records.toMap()
}

object UserScriptApprovals {
    fun encode(records: Map<String, StoredApproval>): String =
        protocolJson.encodeToString(ApprovalSnapshot.serializer(), ApprovalSnapshot(records))

    fun decode(text: String): Map<String, StoredApproval> {
        if (text.isBlank()) return emptyMap()
        return try {
            protocolJson.decodeFromString(ApprovalSnapshot.serializer(), text).scripts
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Fresh disk read so the browser process sees approvals written by the main process. */
    fun load(file: File): Map<String, StoredApproval> {
        if (!file.isFile) return emptyMap()
        return try {
            decode(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
