package com.playbridge.sender.cast.atv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One client identity for this install, plus one record per paired television. */
internal data class AtvClientIdentity(val certPem: String, val keyPem: String)

internal data class AtvTvRecord(
    val fingerprint: String,
    val mac: String?,
    val serverName: String?,
    val lastIp: String?,
    val receiverIds: Set<String>,
)

/** AES/GCM (or a test double). Implementations must not log plaintext. */
internal interface AtvCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** App-private file. Bytes are ciphertext. */
internal interface AtvSecretFile {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal class AtvCredentialStore(
    private val file: AtvSecretFile,
    private val cipher: AtvCipher,
) {
    private val lock = Any()
    private var identity: AtvClientIdentity? = null
    private var tvs: List<AtvTvRecord> = emptyList()
    private val recordsState = MutableStateFlow<List<AtvTvRecord>>(emptyList())
    val records: StateFlow<List<AtvTvRecord>> = recordsState.asStateFlow()

    init {
        load()
    }

    fun identityOrNull(): AtvClientIdentity? = synchronized(lock) { identity }

    fun saveIdentity(value: AtvClientIdentity) {
        synchronized(lock) {
            persist(savedIdentity = value)
            identity = value
        }
    }

    /** Called only after verified pairing. A new pin replaces this receiver's previous association. */
    fun upsert(record: AtvTvRecord) {
        val fingerprint = record.fingerprint.lowercase()
        if (!isFingerprint(fingerprint)) return
        synchronized(lock) {
            val replaced = tvs.filter { it.fingerprint == fingerprint || it.receiverIds.any(record.receiverIds::contains) }
            val existing = replaced.lastOrNull()
            val merged = AtvTvRecord(
                fingerprint = fingerprint,
                mac = (record.mac ?: existing?.mac)?.uppercase(),
                serverName = record.serverName ?: existing?.serverName,
                lastIp = record.lastIp ?: existing?.lastIp,
                receiverIds = replaced.flatMap { it.receiverIds }.toSet() + record.receiverIds,
            )
            val updated = tvs.filter { it !in replaced } + merged
            persist(savedTvs = updated)
            tvs = updated
            recordsState.value = tvs
        }
    }

    fun updateLastIp(fingerprint: String, ip: String) {
        synchronized(lock) {
            val current = tvs.find { it.fingerprint == fingerprint.lowercase() } ?: return
            if (current.lastIp == ip) return
            val updated = tvs.map { if (it.fingerprint == current.fingerprint) it.copy(lastIp = ip) else it }
            persist(savedTvs = updated)
            tvs = updated
            recordsState.value = tvs
        }
    }

    /** Removes one television. The install identity stays. */
    fun forget(fingerprint: String) {
        synchronized(lock) {
            val updated = tvs.filter { it.fingerprint != fingerprint.lowercase() }
            persist(savedTvs = updated)
            tvs = updated
            recordsState.value = tvs
        }
    }

    private fun load() {
        val bytes = try {
            file.read()
        } catch (_: Exception) {
            null
        } ?: return
        val plain = try {
            cipher.decrypt(bytes)
        } catch (_: Exception) {
            return
        }
        val json = try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val identityJson = json.optJSONObject("identity")
        identity = identityJson?.let {
            val cert = it.optString("cert_pem")
            val key = it.optString("key_pem")
            if (cert.isBlank() || key.isBlank()) null else AtvClientIdentity(cert, key)
        }
        val array = json.optJSONArray("tvs") ?: JSONArray()
        tvs = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fingerprint = item.optString("fingerprint").lowercase()
                if (!isFingerprint(fingerprint)) continue
                val ids = item.optJSONArray("receiver_ids")
                add(
                    AtvTvRecord(
                        fingerprint = fingerprint,
                        mac = item.optionalString("mac")?.uppercase(),
                        serverName = item.optionalString("server_name"),
                        lastIp = item.optionalString("last_ip"),
                        receiverIds = buildSet {
                            if (ids != null) for (idIndex in 0 until ids.length()) {
                                ids.optString(idIndex).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        },
                    ),
                )
            }
        }
        // Version 1 appended verified re-pairings. The last saved association owns each id.
        // Keep single legacy pairings (including their install identity) unchanged. Never
        // infer ownership from a MAC or an IP. The version 2 document is saved on the next write.
        tvs = tvs.fold(emptyList()) { migrated, record ->
            val replaced = migrated.filter { it.fingerprint == record.fingerprint || it.receiverIds.any(record.receiverIds::contains) }
            migrated.filter { it !in replaced } + record.copy(receiverIds = replaced.flatMap { it.receiverIds }.toSet() + record.receiverIds)
        }
        recordsState.value = tvs
    }

    private fun persist(savedIdentity: AtvClientIdentity? = identity, savedTvs: List<AtvTvRecord> = tvs) {
        val document = JSONObject().put("version", 2)
        savedIdentity?.let { saved ->
            document.put("identity", JSONObject().put("cert_pem", saved.certPem).put("key_pem", saved.keyPem))
        }
        val array = JSONArray()
        savedTvs.forEach { record ->
            array.put(
                JSONObject()
                    .put("fingerprint", record.fingerprint)
                    .put("mac", record.mac ?: JSONObject.NULL)
                    .put("server_name", record.serverName ?: JSONObject.NULL)
                    .put("last_ip", record.lastIp ?: JSONObject.NULL)
                    .put("receiver_ids", JSONArray(record.receiverIds.toList())),
            )
        }
        document.put("tvs", array)
        val blob = cipher.encrypt(document.toString().toByteArray(Charsets.UTF_8))
        file.write(blob)
    }
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
