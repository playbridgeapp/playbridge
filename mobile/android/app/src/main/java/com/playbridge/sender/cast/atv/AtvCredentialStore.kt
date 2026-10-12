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
            identity = value
            persist()
        }
    }

    fun upsert(record: AtvTvRecord) {
        val fingerprint = record.fingerprint.lowercase()
        if (!isFingerprint(fingerprint)) return
        synchronized(lock) {
            val existing = tvs.find { it.fingerprint == fingerprint }
            val merged = AtvTvRecord(
                fingerprint = fingerprint,
                mac = (record.mac ?: existing?.mac)?.uppercase(),
                serverName = record.serverName ?: existing?.serverName,
                lastIp = record.lastIp ?: existing?.lastIp,
                receiverIds = existing?.receiverIds.orEmpty() + record.receiverIds,
            )
            tvs = tvs.filter { it.fingerprint != fingerprint } + merged
            recordsState.value = tvs
            persist()
        }
    }

    fun updateLastIp(fingerprint: String, ip: String) {
        synchronized(lock) {
            val current = tvs.find { it.fingerprint == fingerprint.lowercase() } ?: return
            tvs = tvs.filter { it.fingerprint != current.fingerprint } + current.copy(lastIp = ip)
            recordsState.value = tvs
            persist()
        }
    }

    /** Removes one television. The install identity stays. */
    fun forget(fingerprint: String) {
        synchronized(lock) {
            tvs = tvs.filter { it.fingerprint != fingerprint.lowercase() }
            recordsState.value = tvs
            persist()
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
                        mac = item.optString("mac").takeIf { it.isNotBlank() }?.uppercase(),
                        serverName = item.optString("server_name").takeIf { it.isNotBlank() },
                        lastIp = item.optString("last_ip").takeIf { it.isNotBlank() },
                        receiverIds = buildSet {
                            if (ids != null) for (idIndex in 0 until ids.length()) add(ids.optString(idIndex))
                        },
                    ),
                )
            }
        }
        recordsState.value = tvs
    }

    private fun persist() {
        val document = JSONObject()
        identity?.let { saved ->
            document.put("identity", JSONObject().put("cert_pem", saved.certPem).put("key_pem", saved.keyPem))
        }
        val array = JSONArray()
        tvs.forEach { record ->
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
