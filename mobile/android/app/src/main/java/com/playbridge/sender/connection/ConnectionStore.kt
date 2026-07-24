package com.playbridge.sender.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.model.SavedReceiverEndpoint
import com.playbridge.shared.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_store")

/**
 * DataStore-backed storage for TV connection info
 */
class ConnectionStore(private val context: Context) {

    companion object {
        private val TV_DEVICE = stringPreferencesKey("tv_device")
        private val DEVICE_HISTORY = stringPreferencesKey("device_history")
        private val SAVED_ENDPOINTS_V2 = stringPreferencesKey("saved_receiver_endpoints_v2")
        private const val KEY_ALIAS = "playbridge_connection_tokens_v1"
        private const val ENCRYPTED_PREFIX = "enc:v1:"
    }

    /**
     * Get stored TV device
     */
    val tvDevice: Flow<TvDevice?> = context.dataStore.data.map { prefs ->
        prefs[TV_DEVICE]?.let {
            try {
                unprotect(protocolJson.decodeFromString<TvDevice>(it))
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Get device history
     */
    val savedEndpoints: Flow<List<SavedReceiverEndpoint>> = context.dataStore.data.map { prefs ->
        readHistory(prefs).map(TvDevice::toSavedEndpoint)
    }

    /** Compatibility view for screens still being migrated to [SavedReceiverEndpoint]. */
    val deviceHistory: Flow<List<TvDevice>> = context.dataStore.data.map(::readHistory)

    /**
     * Save TV device
     */
    suspend fun saveTvDevice(device: TvDevice) {
        context.dataStore.edit { prefs ->
            prefs[TV_DEVICE] = protocolJson.encodeToString(TvDevice.serializer(), protect(device))
        }
    }

    /**
     * Add to history, replacing any stale endpoint for the same receiver UUID.
     */
    suspend fun addToHistory(device: TvDevice) {
        context.dataStore.edit { prefs ->
            val currentHistory = readHistory(prefs)
            val newHistory = ConnectionMerge.upsertHistory(currentHistory, device)
            writeHistory(prefs, newHistory)
        }
    }

    /**
     * Wipe the stored pairing token of the matching history entry (by uuid, falling
     * back to ip/port) without touching any other saved TV. Used when a TV rejects
     * a token or denies pairing, so only THAT device re-pairs on the next tap.
     */
    suspend fun wipeHistoryToken(device: TvDevice) {
        context.dataStore.edit { prefs ->
            val currentHistory = readHistory(prefs)
            if (currentHistory.isEmpty()) return@edit

            val newHistory = currentHistory.map { entry ->
                val matches = ConnectionMerge.isSameDevice(entry, device)
                if (matches) entry.copy(token = "") else entry
            }
            writeHistory(prefs, newHistory)
        }
    }

    /**
     * Remove from history
     */
    suspend fun removeFromHistory(device: TvDevice) {
        context.dataStore.edit { prefs ->
            val currentHistory = readHistory(prefs)
            if (currentHistory.isEmpty()) return@edit

            val newHistory = ConnectionMerge.removeHistoryDevice(currentHistory, device)

            writeHistory(prefs, newHistory)
        }
    }

    /**
     * Clear stored TV device
     */
    suspend fun clearTvDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(TV_DEVICE)
        }
    }

    /**
     * Check if we have a stored TV device
     */
    suspend fun hasTvDevice(): Boolean {
        return context.dataStore.data.first()[TV_DEVICE] != null
    }

    private fun decodeHistory(json: String): List<TvDevice> =
        ConnectionMerge.normalizeHistory(
            protocolJson.decodeFromString<List<TvDevice>>(json).map(::unprotect)
        )

    private fun readHistory(prefs: Preferences): List<TvDevice> {
        prefs[SAVED_ENDPOINTS_V2]?.let { json ->
            runCatching {
                protocolJson.decodeFromString<List<SavedReceiverEndpoint>>(json)
                    .map(TvDevice::fromSavedEndpoint)
                    .map(::unprotect)
            }.getOrNull()?.let(ConnectionMerge::normalizeHistory)?.let { return it }
        }
        return prefs[DEVICE_HISTORY]?.let { json ->
            runCatching { decodeHistory(json) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** Dual-write during the migration window so older builds can still read history. */
    private fun writeHistory(prefs: MutablePreferences, history: List<TvDevice>) {
        val protected = history.map(::protect)
        prefs[DEVICE_HISTORY] = protocolJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(TvDevice.serializer()),
            protected,
        )
        prefs[SAVED_ENDPOINTS_V2] = protocolJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SavedReceiverEndpoint.serializer()),
            protected.map(TvDevice::toSavedEndpoint),
        )
    }

    private fun protect(device: TvDevice): TvDevice =
        if (device.token.isEmpty() || device.token.startsWith(ENCRYPTED_PREFIX)) device
        else device.copy(token = encryptToken(device.token))

    private fun unprotect(device: TvDevice): TvDevice =
        if (!device.token.startsWith(ENCRYPTED_PREFIX)) device
        else device.copy(token = decryptToken(device.token))

    private fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return ENCRYPTED_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptToken(value: String): String = try {
        val packed = Base64.decode(value.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, packed.copyOfRange(0, 12)),
        )
        cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        // Restored backups cannot use the old device-bound key; require re-pairing.
        ""
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
