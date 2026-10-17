package com.playbridge.sender.cast.atv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One client identity for this install, plus one record per paired television. */
internal data class AtvClientIdentity(val certPem: String, val keyPem: String) {
    override fun toString(): String = "AtvClientIdentity(redacted)"
}

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
    /** Only after verified re-pairing of an unreadable store. No plaintext fallback. */
    fun rotateKey() { throw AtvStorageException() }
}

internal const val ATV_SAVE_ERROR = "Couldn't save the pairing"
internal const val ATV_REMOVE_ERROR = "Couldn't remove the TV"
internal class AtvStorageException : Exception(ATV_SAVE_ERROR)
internal class AtvKeyUnavailableException : Exception("Pair again")

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
    private var tvIdentities: Map<String, AtvClientIdentity> = emptyMap()
    private var readBlocked = false
    private val recordsState = MutableStateFlow<List<AtvTvRecord>>(emptyList())
    val records: StateFlow<List<AtvTvRecord>> = recordsState.asStateFlow()
    private val recoveryState = MutableStateFlow(false)
    val recoveryRequired: StateFlow<Boolean> = recoveryState.asStateFlow()

    init {
        load()
    }

    fun identityOrNull(fingerprint: String? = null): AtvClientIdentity? = synchronized(lock) {
        (fingerprint?.let { tvIdentities[it.lowercase()] } ?: identity).takeUnless { recoveryState.value }
    }

    /** An in-memory identity can still be verified during recovery; never use it to connect. */
    fun pairingIdentityOrNull(): AtvClientIdentity? = synchronized(lock) {
        retryBlockedRead()
        identity
    }

    fun saveIdentity(value: AtvClientIdentity) {
        synchronized(lock) {
            requireReadable()
            persist(savedIdentity = value)
            identity = value
        }
    }

    /** Called only after verified pairing. A new pin replaces this receiver's previous association. */
    fun upsert(record: AtvTvRecord) = saveRecord(record, null, verified = false)

    /** Recovery replaces unreadable credentials only after the TV verified this identity. */
    fun saveVerifiedPairing(value: AtvClientIdentity, record: AtvTvRecord, preserveOtherPairings: Boolean = false) =
        saveRecord(record, value, verified = true, preserveOtherPairings = preserveOtherPairings)

    private fun saveRecord(record: AtvTvRecord, value: AtvClientIdentity?, verified: Boolean, preserveOtherPairings: Boolean = false) {
        val fingerprint = record.fingerprint.lowercase()
        if (!isFingerprint(fingerprint)) return
        synchronized(lock) {
            retryBlockedRead()
            val recovering = recoveryState.value
            if (!verified) requireReadable()
            // Runtime key loss can retain a readable in-memory identity/records. Preserve
            // unrelated TVs when that same client identity is verified again.
            val previous = if (recovering && identity != value) emptyList() else tvs
            val replaced = previous.filter { it.fingerprint == fingerprint || it.receiverIds.any(record.receiverIds::contains) }
            val existing = replaced.lastOrNull()
            val merged = AtvTvRecord(
                fingerprint = fingerprint,
                mac = (record.mac ?: existing?.mac)?.uppercase(),
                serverName = record.serverName ?: existing?.serverName,
                lastIp = record.lastIp ?: existing?.lastIp,
                receiverIds = replaced.flatMap { it.receiverIds }.toSet() + record.receiverIds,
            )
            val updated = previous.filter { it !in replaced } + merged
            val retained = tvIdentities.toMutableMap()
            if (preserveOtherPairings && !recovering && value != identity) {
                identity?.let { old -> previous.forEach { retained.putIfAbsent(it.fingerprint, old) } }
            }
            // A successfully re-paired TV now uses the new install identity.
            replaced.forEach { retained.remove(it.fingerprint) }
            val savedIdentities = retained.filterKeys { pin -> updated.any { it.fingerprint == pin } }
            persist(savedIdentity = value ?: identity, savedTvs = updated, rotateKey = recovering, savedTvIdentities = savedIdentities)
            tvIdentities = savedIdentities
            if (value != null) identity = value
            tvs = updated
            recordsState.value = tvs
            recoveryState.value = false
        }
    }

    fun updateLastIp(fingerprint: String, ip: String) {
        synchronized(lock) {
            requireReadable()
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
            requireReadable()
            val updated = tvs.filter { it.fingerprint != fingerprint.lowercase() }
            persist(savedTvs = updated)
            tvIdentities = tvIdentities.filterKeys { pin -> updated.any { it.fingerprint == pin } }
            tvs = updated
            recordsState.value = tvs
        }
    }

    private fun load() {
        readBlocked = false
        val bytes = try {
            file.read()
        } catch (_: Exception) {
            readBlocked = true
            recoveryState.value = true
            return
        } ?: run {
            recoveryState.value = false
            return
        }
        val plain = try {
            cipher.decrypt(bytes)
        } catch (_: Exception) {
            recoveryState.value = true
            return
        }
        val json = try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            recoveryState.value = true
            return
        }
        val identityJson = json.optJSONObject("identity")
        identity = identityJson?.let {
            val cert = it.optString("cert_pem")
            val key = it.optString("key_pem")
            if (cert.isBlank() || key.isBlank()) null else AtvClientIdentity(cert, key)
        }
        val overrides = json.opt("tv_identities")
        if (overrides != null && overrides != JSONObject.NULL && overrides !is JSONObject) {
            recoveryState.value = true
            return
        }
        val retained = mutableMapOf<String, AtvClientIdentity>()
        val saved = overrides as? JSONObject
        if ((saved?.length() ?: 0) > 256) { recoveryState.value = true; return }
        saved?.keys()?.forEach { pin ->
            val item = saved.optJSONObject(pin)
            val cert = item?.optString("cert_pem").orEmpty()
            val key = item?.optString("key_pem").orEmpty()
            if (!isFingerprint(pin) || pin != pin.lowercase() || cert.isBlank() || key.isBlank() || cert.length > 65_536 || key.length > 65_536) {
                recoveryState.value = true
                return
            }
            retained[pin] = AtvClientIdentity(cert, key)
        }
        tvIdentities = retained
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
        if (tvIdentities.keys.any { pin -> tvs.none { it.fingerprint == pin } }) {
            recoveryState.value = true
            return
        }
        recordsState.value = tvs
        recoveryState.value = false
    }

    private fun retryBlockedRead() {
        if (!readBlocked) return
        // A read failure says nothing about the key's usability. Do not rotate it
        // or overwrite a possibly healthy file until a fresh read has succeeded.
        load()
        if (readBlocked) throw AtvStorageException()
    }

    private fun requireReadable() {
        retryBlockedRead()
        if (recoveryState.value) throw AtvStorageException()
    }

    private fun persist(savedIdentity: AtvClientIdentity? = identity, savedTvs: List<AtvTvRecord> = tvs, rotateKey: Boolean = false,
        savedTvIdentities: Map<String, AtvClientIdentity> = tvIdentities) {
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
        val retained = JSONObject()
        savedTvIdentities.filterKeys { pin -> savedTvs.any { it.fingerprint == pin } }.forEach { (pin, old) ->
            retained.put(pin, JSONObject().put("cert_pem", old.certPem).put("key_pem", old.keyPem))
        }
        if (retained.length() > 0) document.put("tv_identities", retained)
        try {
            if (rotateKey) cipher.rotateKey()
            val blob = cipher.encrypt(document.toString().toByteArray(Charsets.UTF_8))
            file.write(blob)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AtvKeyUnavailableException) {
            recoveryState.value = true
            throw AtvStorageException()
        } catch (_: Exception) {
            throw AtvStorageException()
        }
    }
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
