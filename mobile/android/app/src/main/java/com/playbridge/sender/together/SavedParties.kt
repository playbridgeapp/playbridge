package com.playbridge.sender.together

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A device this phone added to a party, so a rejoin still counts it as this phone's own. */
data class SavedOwnDevice(val member: String, val boxKey: String)

/**
 * A party this phone joined recently, kept so it can come back to it after the app was
 * closed: as the host if it started the party, otherwise as the same guest.
 */
data class SavedParty(
    /** The room ID, which is not secret. */
    val roomId: String,
    val relay: String,
    /** This phone started the party. */
    val host: Boolean,
    /** The engine's saved party: the invite and this phone's keys. Secret; never log it. */
    val saved: String,
    val joinedAtMs: Long,
    /** When the room ends, from its ID, or [SavedParties.UNDATED_LIFETIME_MS] after joining. */
    val endsAtMs: Long,
    /** The host's name, once a guest has seen it. */
    val hostName: String? = null,
    val own: List<SavedOwnDevice> = emptyList(),
) {
    override fun toString(): String = "SavedParty(room=$roomId, relay=$relay, host=$host, endsAt=$endsAtMs)"

    fun title(): String = when {
        host -> "Your party"
        !hostName.isNullOrBlank() -> "$hostName's party"
        else -> "Watch party"
    }
}

object SavedParties {
    /** How long a party from before rooms carried their end time is kept: the relay's party length. */
    const val UNDATED_LIFETIME_MS = 8 * 60 * 60_000L
    const val MAX_SAVED = 20

    fun endsAt(roomEndsAtMs: Long?, joinedAtMs: Long): Long = roomEndsAtMs ?: (joinedAtMs + UNDATED_LIFETIME_MS)

    /** The parties that haven't ended, newest first. */
    fun live(parties: Collection<SavedParty>, nowMs: Long): List<SavedParty> =
        parties.filter { it.endsAtMs > nowMs }.sortedByDescending { it.joinedAtMs }.take(MAX_SAVED)

    fun endsIn(party: SavedParty, nowMs: Long): String {
        val minutes = (party.endsAtMs - nowMs) / 60_000L
        return when {
            minutes < 1 -> "Ends in less than a minute"
            minutes < 60 -> "Ends in $minutes min"
            minutes % 60 == 0L -> "Ends in ${minutes / 60} h"
            else -> "Ends in ${minutes / 60} h ${minutes % 60} min"
        }
    }

    internal fun encode(party: SavedParty): String = JSONObject().apply {
        put("room", party.roomId)
        put("relay", party.relay)
        put("host", party.host)
        put("saved", party.saved)
        put("joined_at", party.joinedAtMs)
        put("ends_at", party.endsAtMs)
        party.hostName?.let { put("host_name", it) }
        put(
            "own",
            JSONArray().apply {
                party.own.forEach { put(JSONObject().put("member", it.member).put("box_pk", it.boxKey)) }
            },
        )
    }.toString()

    internal fun decode(text: String): SavedParty? = runCatching {
        val o = JSONObject(text)
        val own = o.optJSONArray("own") ?: JSONArray()
        SavedParty(
            roomId = o.getString("room").takeIf { it.isNotBlank() } ?: return null,
            relay = o.optString("relay"),
            host = o.optBoolean("host"),
            saved = o.getString("saved").takeIf { it.isNotBlank() } ?: return null,
            joinedAtMs = o.getLong("joined_at"),
            endsAtMs = o.getLong("ends_at"),
            hostName = o.optString("host_name").takeIf { it.isNotBlank() },
            own = (0 until own.length()).mapNotNull { index ->
                val device = own.optJSONObject(index) ?: return@mapNotNull null
                val member = device.optString("member").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val box = device.optString("box_pk").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SavedOwnDevice(member, box)
            },
        )
    }.getOrNull()
}

/** Encrypts saved parties at rest. */
interface SavedPartyCipher {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

/** AES-GCM under a key that never leaves Android Keystore, so a backup or copy can't be read. */
class KeystoreSavedPartyCipher : SavedPartyCipher {
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "playbridge_watch_together_saved_v1"
    }
}

/**
 * The parties this phone can rejoin, one encrypted entry per room. Ended parties are
 * dropped whenever the list is read or changed.
 */
class SavedPartyStore(
    private val preferences: SharedPreferences,
    private val cipher: SavedPartyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val all = LinkedHashMap<String, SavedParty>()
    private val mutableParties = MutableStateFlow<List<SavedParty>>(emptyList())
    val parties: StateFlow<List<SavedParty>> = mutableParties.asStateFlow()

    init {
        preferences.all.forEach { (roomId, value) ->
            val party = (value as? String)
                ?.let { runCatching { cipher.open(it) }.getOrNull() }
                ?.let(SavedParties::decode)
            if (party != null && party.roomId == roomId) all[roomId] = party
        }
        prune()
    }

    @Synchronized
    fun get(roomId: String): SavedParty? = all[roomId]?.takeIf { it.endsAtMs > clock() }

    @Synchronized
    fun put(party: SavedParty) {
        val sealed = runCatching { cipher.seal(SavedParties.encode(party)) }.getOrNull() ?: return
        all[party.roomId] = party
        preferences.edit().putString(party.roomId, sealed).apply()
        prune()
    }

    @Synchronized
    fun update(roomId: String, change: (SavedParty) -> SavedParty) {
        val current = all[roomId] ?: return
        val next = change(current)
        if (next != current) put(next)
    }

    @Synchronized
    fun remove(roomId: String) {
        if (all.remove(roomId) == null && !preferences.contains(roomId)) return
        preferences.edit().remove(roomId).apply()
        publish()
    }

    /** Drops ended parties and all but the newest [SavedParties.MAX_SAVED]. */
    @Synchronized
    fun prune() {
        val keep = SavedParties.live(all.values, clock()).map { it.roomId }.toSet()
        val stale = (all.keys + preferences.all.keys) - keep
        if (stale.isNotEmpty()) {
            stale.forEach(all::remove)
            preferences.edit().apply { stale.forEach(::remove) }.apply()
        }
        publish()
    }

    private fun publish() {
        mutableParties.value = SavedParties.live(all.values, clock())
    }

    companion object {
        /** Excluded from backups in `backup_rules.xml` and `data_extraction_rules.xml`. */
        const val PREFS_NAME = "watch_together_saved"

        fun create(context: Context): SavedPartyStore = SavedPartyStore(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            KeystoreSavedPartyCipher(),
        )
    }
}
