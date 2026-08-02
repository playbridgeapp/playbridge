package com.playbridge.sender.cast

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun hlsRangeHeader(offset: Long, length: Long): String? = when {
    offset < 0L -> null
    length > 0L -> "bytes=$offset-${offset + length - 1L}"
    offset > 0L -> "bytes=$offset-"
    else -> null
}

internal fun decodeHlsInitializationVector(value: String): ByteArray? {
    val normalized = value
        .removePrefix("0x")
        .removePrefix("0X")
        .lowercase(Locale.US)
    if (normalized.isEmpty() || normalized.length > 32 ||
        normalized.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val padded = normalized.padStart(32, '0')
    return ByteArray(16) { index ->
        padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun decryptHlsAes128(payload: ByteArray, key: ByteArray, ivText: String): ByteArray? {
    if (key.size != 16 || payload.isEmpty()) return null
    val iv = decodeHlsInitializationVector(ivText) ?: return null
    return runCatching {
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(payload)
        }
    }.getOrNull()
}
