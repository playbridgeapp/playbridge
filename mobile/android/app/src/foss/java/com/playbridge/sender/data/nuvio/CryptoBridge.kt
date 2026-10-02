package com.playbridge.sender.data.nuvio

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function

internal class CryptoBridge(
    private val maxRandBytes: Int = MAX_RAND_BYTES,
    private val maxDataBytes: Int = MAX_DATA_BYTES,
    private val maxPbkdf2Iterations: Int = MAX_PBKDF2_ITERATIONS,
    private val maxPbkdf2KeyBits: Int = MAX_PBKDF2_KEY_BITS,
    private val maxEvalCryptoBudgetBytes: Long = MAX_EVAL_CRYPTO_BUDGET_BYTES,
) {
    companion object {
        const val MAX_RAND_BYTES = 64 * 1024 // 64 KiB
        const val MAX_DATA_BYTES = 2 * 1024 * 1024 // 2 MiB
        const val MAX_PBKDF2_ITERATIONS = 10_000
        const val MAX_PBKDF2_KEY_BITS = 4096
        const val MAX_EVAL_CRYPTO_BUDGET_BYTES = 16L * 1024 * 1024 // 16 MiB per eval
    }

    private var cumulativeBytesProcessed: Long = 0L

    private fun checkDataBudget(len: Int) {
        if (len < 0 || len > maxDataBytes) {
            throw IllegalArgumentException("Data size exceeds limit")
        }
        cumulativeBytesProcessed += len
        if (cumulativeBytesProcessed > maxEvalCryptoBudgetBytes) {
            throw IllegalStateException("Evaluation crypto budget exceeded")
        }
    }

    private fun parseBoundedHex(hex: String?): ByteArray {
        val str = hex ?: ""
        if (str.length > maxDataBytes * 2) {
            throw IllegalArgumentException("Hex string exceeds limit")
        }
        val bytes = pluginHexToByteArray(str)
        checkDataBudget(bytes.size)
        return bytes
    }

    fun register(runtime: QuickJs) {
        runtime.function("__crypto_get_random_values_hex") { args ->
            val length = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            if (length < 0 || length > maxRandBytes) {
                throw IllegalArgumentException("Random length exceeds limit")
            }
            checkDataBudget(length)
            pluginGetRandomValues(length).toHexString()
        }

        runtime.function("__crypto_digest_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = parseBoundedHex(args.getOrNull(1)?.toString())
            pluginDigest(algorithm, data).toHexString()
        }

        runtime.function("__crypto_hmac_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = parseBoundedHex(args.getOrNull(1)?.toString())
            val data = parseBoundedHex(args.getOrNull(2)?.toString())
            pluginHmac(algorithm, key, data).toHexString()
        }

        runtime.function("__crypto_pbkdf2_hex") { args ->
            val password = parseBoundedHex(args.getOrNull(0)?.toString())
            val salt = parseBoundedHex(args.getOrNull(1)?.toString())
            val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
            val keySizeBits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
            val algorithm = args.getOrNull(4)?.toString() ?: "SHA256"

            if (iterations < 1 || iterations > maxPbkdf2Iterations) {
                throw IllegalArgumentException("PBKDF2 iterations exceed limit")
            }
            if (keySizeBits < 8 || keySizeBits > maxPbkdf2KeyBits || keySizeBits % 8 != 0) {
                throw IllegalArgumentException("PBKDF2 key size exceeds limit")
            }
            checkDataBudget(keySizeBits / 8)

            pluginPbkdf2(password, salt, iterations, keySizeBits, algorithm).toHexString()
        }

        runtime.function("__crypto_aes_encrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = parseBoundedHex(args.getOrNull(1)?.toString())
            val iv = parseBoundedHex(args.getOrNull(2)?.toString())
            val data = parseBoundedHex(args.getOrNull(3)?.toString())
            pluginAesEncrypt(mode, key, iv, data).toHexString()
        }

        runtime.function("__crypto_aes_decrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val key = parseBoundedHex(args.getOrNull(1)?.toString())
            val iv = parseBoundedHex(args.getOrNull(2)?.toString())
            val data = parseBoundedHex(args.getOrNull(3)?.toString())
            pluginAesDecrypt(mode, key, iv, data).toHexString()
        }

        runtime.function("__crypto_sign_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val privateKey = parseBoundedHex(args.getOrNull(1)?.toString())
            val data = parseBoundedHex(args.getOrNull(2)?.toString())
            pluginSign(algorithm, privateKey, data).toHexString()
        }

        runtime.function("__crypto_verify_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: ""
            val publicKey = parseBoundedHex(args.getOrNull(1)?.toString())
            val signature = parseBoundedHex(args.getOrNull(2)?.toString())
            val data = parseBoundedHex(args.getOrNull(3)?.toString())
            pluginVerify(algorithm, publicKey, signature, data)
        }

        runtime.function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val data = args.getOrNull(1)?.toString() ?: ""
            checkDataBudget(data.length)
            pluginDigestHex(algorithm, data)
        }

        runtime.function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val key = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString() ?: ""
            checkDataBudget(key.length + data.length)
            pluginHmacHex(algorithm, key, data)
        }

        runtime.function("__crypto_base64_encode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            checkDataBudget(data.length)
            pluginBase64Encode(data)
        }

        runtime.function("__crypto_base64_decode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            checkDataBudget(data.length)
            pluginBase64Decode(data)
        }

        runtime.function("__crypto_utf8_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            checkDataBudget(data.length)
            pluginUtf8ToHex(data)
        }

        runtime.function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            checkDataBudget(data.length / 2)
            pluginHexToUtf8(data)
        }
    }
}

internal fun QuickJs.registerCryptoBridge(bridge: CryptoBridge = CryptoBridge()) {
    bridge.register(this)
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
