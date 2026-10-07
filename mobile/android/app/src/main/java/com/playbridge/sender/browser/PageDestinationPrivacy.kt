package com.playbridge.sender.browser

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Page-facing playback destination identity.
 *
 * Websites without casting consent learn only kind and connection state. After consent,
 * a receiver's raw `protocol:stableId` is replaced with
 * HMAC-SHA256(installSecret, origin || NUL || endpointKey) so two origins cannot
 * correlate the same device. `this-device` stays the literal id; it is not a device secret.
 * The install secret is generated once and is not cleared with website consent.
 */
internal object PageDestinationPrivacy {
    const val THIS_DEVICE = "this-device"
    const val UNAVAILABLE = "unavailable"
    private const val PREFS_NAME = "page_destination_privacy"
    private const val KEY_INSTALL_SECRET = "install_secret"
    private const val SECRET_BYTES = 32

    fun installSecret(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_SECRET, null)?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        }
        if (existing != null && existing.size == SECRET_BYTES) return existing
        val created = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_INSTALL_SECRET, Base64.getEncoder().encodeToString(created)).apply()
        return created
    }

    fun idForOrigin(secret: ByteArray, origin: String, endpointKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(origin.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(endpointKey.toByteArray(Charsets.UTF_8))
        return mac.doFinal().toHex()
    }
}

internal fun pagePlaybackDestination(
    rawId: String,
    rawName: String,
    kind: String,
    connected: Boolean,
    origin: String?,
    consented: Boolean,
    installSecret: ByteArray,
): JSONObject {
    val disclosed = consented && !origin.isNullOrBlank()
    val id: Any = when {
        !disclosed -> JSONObject.NULL
        rawId == PageDestinationPrivacy.THIS_DEVICE || rawId == PageDestinationPrivacy.UNAVAILABLE -> rawId
        else -> PageDestinationPrivacy.idForOrigin(installSecret, checkNotNull(origin), rawId)
    }
    return JSONObject()
        .put("id", id)
        .put("name", if (disclosed) rawName else JSONObject.NULL)
        .put("kind", kind)
        .put("connected", connected)
}

/** True when [pageId] is the id this origin is allowed to use for [rawId]. Raw endpoint keys never match. */
internal fun pageDestinationIdMatches(
    pageId: String,
    rawId: String,
    origin: String,
    installSecret: ByteArray,
): Boolean {
    if (pageId == PageDestinationPrivacy.THIS_DEVICE) return rawId == PageDestinationPrivacy.THIS_DEVICE
    if (rawId == PageDestinationPrivacy.THIS_DEVICE || rawId == PageDestinationPrivacy.UNAVAILABLE || rawId.isEmpty()) {
        return false
    }
    return pageId == PageDestinationPrivacy.idForOrigin(installSecret, origin, rawId)
}

internal enum class PageDestinationGesture {
    OPEN_PICKER,
    SELECT_THIS_DEVICE,
    REJECT_GESTURE,
    REJECT_INVALID,
}

/**
 * Mirrors the iOS native gesture gate. Opening the picker rejects only an explicit inactive
 * gesture (a missing signal is the platform that does not report user activation; the picker
 * itself is the confirmation). Switching to this device, which disconnects a receiver,
 * requires an attested active gesture. A connected receiver is never disconnected for a
 * website: the native picker opens instead.
 */
internal fun pageDestinationGesture(
    hasDestinationId: Boolean,
    destinationId: String?,
    userActivation: Boolean?,
    receiverConnected: Boolean,
): PageDestinationGesture {
    if (!hasDestinationId) {
        return if (userActivation == false) PageDestinationGesture.REJECT_GESTURE else PageDestinationGesture.OPEN_PICKER
    }
    if (destinationId != PageDestinationPrivacy.THIS_DEVICE) return PageDestinationGesture.REJECT_INVALID
    if (userActivation != true) return PageDestinationGesture.REJECT_GESTURE
    return if (receiverConnected) PageDestinationGesture.OPEN_PICKER else PageDestinationGesture.SELECT_THIS_DEVICE
}

internal fun jsonBooleanOrNull(value: Any?): Boolean? = value as? Boolean

private val HEX = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
    }
}
