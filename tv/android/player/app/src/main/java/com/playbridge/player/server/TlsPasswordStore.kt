package com.playbridge.player.server

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun interface TlsPasswordStore {
    fun getOrCreate(dir: File): CharArray
}

/** Protects the exportable TLS key's PKCS12 password with a non-exportable Android Keystore key. */
internal object AndroidKeystoreTlsPasswordStore : TlsPasswordStore {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "playbridge_tls_password_key"
    private const val FILE_NAME = "playbridge_tls_pw.enc"
    private const val MAGIC = 0x50425450 // PBTP
    private const val VERSION = 1
    private const val PASSWORD_BYTES = 32
    private const val GCM_TAG_BITS = 128

    override fun getOrCreate(dir: File): CharArray {
        val file = AtomicFile(File(dir, FILE_NAME))
        if (file.baseFile.exists()) {
            return decrypt(file.readFully(), getOrCreateKey()).toCharArray()
        }

        val passwordBytes = ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes)
        val password = Base64.getEncoder().withoutPadding().encodeToString(passwordBytes)
        passwordBytes.fill(0)
        write(file, encrypt(password, getOrCreateKey()))
        return password.toCharArray()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun encrypt(password: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return java.io.ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun decrypt(payload: ByteArray, key: SecretKey): String =
        DataInputStream(payload.inputStream()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid TLS password file" }
            require(input.readInt() == VERSION) { "Unsupported TLS password file version" }
            val iv = ByteArray(input.readInt().also { require(it in 12..32) }).also(input::readFully)
            val ciphertext = ByteArray(
                input.readInt().also { require(it in 1..4096) },
            ).also(input::readFully)
            require(input.available() == 0) { "Trailing data in TLS password file" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }

    private fun write(file: AtomicFile, payload: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(payload)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}
