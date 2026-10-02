package com.playbridge.sender.data.nuvio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed approval state. Hashes detect code changes; they do not prove
 * authenticity. Credentials stay in the scraper row, never here.
 */
internal class NuvioApprovalStore(
    private val nuvioRoot: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val revisionState = MutableStateFlow(0)
    val revisions: StateFlow<Int> = revisionState

    @Serializable
    data class Record(
        val approvedCodeSha256: String? = null,
        val pendingCodeSha256: String? = null,
        val approvedHosts: List<String> = emptyList(),
        val blockedHosts: List<String> = emptyList(),
    )

    @Serializable
    private data class FileModel(val records: Map<String, Record> = emptyMap())

    fun get(repoUrl: String, scraperId: String): Record = synchronized(lock) {
        read()[key(repoUrl, scraperId)] ?: Record()
    }

    fun update(repoUrl: String, scraperId: String, transform: (Record) -> Record): Boolean = synchronized(lock) {
        val all = read().toMutableMap()
        val next = transform(all[key(repoUrl, scraperId)] ?: Record()).normalized()
        all[key(repoUrl, scraperId)] = next
        write(all)
    }

    fun addBlockedHost(repoUrl: String, scraperId: String, host: String) {
        val normalized = NuvioDestinationPolicy.normalizeHost(host)
        if (!NuvioDestinationPolicy.isRecordableHost(normalized)) return
        update(repoUrl, scraperId) { record ->
            if (normalized in record.approvedHosts || normalized in record.blockedHosts) record
            else record.copy(blockedHosts = (record.blockedHosts + normalized).take(NuvioLimits.MAX_BLOCKED_HOSTS))
        }
    }

    fun approveHost(repoUrl: String, scraperId: String, host: String): Boolean {
        val normalized = NuvioDestinationPolicy.normalizeHost(host)
        if (!NuvioDestinationPolicy.isRecordableHost(normalized)) return false
        var accepted = false
        val persisted = update(repoUrl, scraperId) { record ->
            if (normalized in record.approvedHosts) {
                accepted = true
                record
            } else if (record.approvedHosts.distinct().size >= NuvioLimits.MAX_APPROVED_HOSTS) {
                accepted = false
                record
            } else {
                accepted = true
                record.copy(
                    approvedHosts = (record.approvedHosts + normalized).distinct(),
                    blockedHosts = record.blockedHosts.filterNot { it == normalized },
                )
            }
        }
        return accepted && persisted
    }

    fun revokeHost(repoUrl: String, scraperId: String, host: String) {
        val normalized = NuvioDestinationPolicy.normalizeHost(host)
        update(repoUrl, scraperId) { record ->
            record.copy(approvedHosts = record.approvedHosts.filterNot { it == normalized })
        }
    }

    fun remove(repoUrl: String, scraperId: String) = synchronized(lock) {
        val all = read().toMutableMap()
        if (all.remove(key(repoUrl, scraperId)) != null) write(all)
    }

    fun removeRepo(repoUrl: String) = synchronized(lock) {
        val prefix = repoUrl + "\n"
        val all = read().filterKeys { !it.startsWith(prefix) }
        write(all)
    }

    private fun key(repoUrl: String, scraperId: String) = repoUrl + "\n" + scraperId

    private fun read(): Map<String, Record> {
        val file = stateFile()
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyMap()
        return try {
            val text = file.readText()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return emptyMap()
            json.decodeFromString<FileModel>(text).records.entries
                .take(MAX_RECORDS)
                .associate { it.toPair() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun write(records: Map<String, Record>): Boolean {
        if (records.size > MAX_RECORDS) return false
        val text = try {
            json.encodeToString(FileModel(records))
        } catch (_: Exception) {
            return false
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return false
        return try {
            val file = stateFile()
            val parent = file.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(text)
            val replaced = if (tmp.renameTo(file)) {
                true
            } else {
                file.writeText(text)
                tmp.delete()
                file.isFile && file.length() <= MAX_FILE_BYTES
            }
            if (!replaced) {
                tmp.delete()
                return false
            }
            revisionState.value = revisionState.value + 1
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stateFile(): File = File(File(nuvioRoot, "state"), "approvals.json")

    private companion object {
        const val MAX_FILE_BYTES = 256L * 1024
        const val MAX_RECORDS = 256
    }

    private fun Record.normalized(): Record = copy(
        approvedHosts = approvedHosts.map { NuvioDestinationPolicy.normalizeHost(it) }
            .filter { NuvioDestinationPolicy.isRecordableHost(it) }
            .distinct()
            .take(NuvioLimits.MAX_APPROVED_HOSTS),
        blockedHosts = blockedHosts.map { NuvioDestinationPolicy.normalizeHost(it) }
            .filter { NuvioDestinationPolicy.isRecordableHost(it) && it !in approvedHosts }
            .distinct()
            .take(NuvioLimits.MAX_BLOCKED_HOSTS),
    )
}
