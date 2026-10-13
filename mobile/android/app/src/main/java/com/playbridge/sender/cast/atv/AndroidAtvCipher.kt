package com.playbridge.sender.cast.atv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Key lookup/rotation boundary; local AES-GCM tests never touch Android Keystore. */
internal interface AtvKeystoreKeys {
    fun get(create: Boolean): SecretKey
    fun rotate()
}

/** Android Keystore AES-GCM; the persisted IV/ciphertext format remains unchanged. */
internal class AndroidAtvCipher(private val keys: AtvKeystoreKeys = AndroidAtvKeystoreKeys()) : AtvCipher {
    private var hasCiphertext = false

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        initialize { cipher.init(Cipher.ENCRYPT_MODE, keys.get(create = !hasCiphertext)) }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        hasCiphertext = true
        return iv + encrypted
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        hasCiphertext = true
        require(blob.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        initialize { cipher.init(Cipher.DECRYPT_MODE, keys.get(create = false), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    override fun rotateKey() {
        keys.rotate()
    }

    private fun initialize(block: () -> Unit) {
        try {
            block()
        } catch (_: InvalidKeyException) {
            throw AtvKeyUnavailableException()
        } catch (_: UnrecoverableKeyException) {
            throw AtvKeyUnavailableException()
        }
    }
}

private class AndroidAtvKeystoreKeys : AtvKeystoreKeys {
    override fun rotate() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(KEY_ALIAS)
        get(create = true)
    }

    override fun get(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw AtvKeyUnavailableException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "playbridge_atv_remote_v1"
    }
}
