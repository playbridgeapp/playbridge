package com.playbridge.sender.history

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.proxy.PhoneProxyUrls
import com.playbridge.sender.diagnostics.CastAttemptDiagnostics
import com.playbridge.shared.protocol.decodePlaylistPayloadJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private recast data. The shareable diagnostic report never reads this store. */
data class CastReplaySource(
    val url: String,
    val title: String?,
    val contentType: String?,
    val mediaKind: String?,
    val headers: Map<String, String>,
    val subtitles: List<String>,
    /** Preserves a multi-item native queue; null for a single item or external cast. */
    val playlistPayloadJson: String? = null,
)

internal fun isReplayableMediaUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank() &&
        !PhoneProxyUrls.isRustEmbeddedProxyUrl(url) &&
        !(PhoneProxyUrls.isPrivateLanHost(uri.host) &&
            (uri.path ?: "").matches(Regex("/[0-9a-fA-F]{16}\\.[^/]+"))) &&
        !url.contains("/playbridge-proxy/", ignoreCase = true)
}.getOrDefault(false)

/** The wire command is only inspected locally; it is never copied into diagnostics. */
internal fun replaySourceFromNativeCommand(command: String): CastReplaySource? = runCatching {
    val root = Json.parseToJsonElement(command) as? JsonObject ?: return@runCatching null
    if (root["type"]?.jsonPrimitive?.contentOrNull != "command" ||
        root["action"]?.jsonPrimitive?.contentOrNull != "playlist"
    ) return@runCatching null
    val payload = root["payload"] as? JsonObject ?: return@runCatching null
    val items = payload["items"] as? JsonArray ?: return@runCatching null
    if (items.isEmpty() || items.size > MAX_REPLAY_PLAYLIST_ITEMS) return@runCatching null
    val itemObjects = items.map { it as? JsonObject ?: return@runCatching null }
    val urls = itemObjects.map { it["url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null }
    if (urls.any { !isReplayableMediaUrl(it) }) return@runCatching null
    val first = itemObjects.first()
    val headers = (first["headers"] as? JsonObject)?.mapNotNull { (name, value) ->
        value.jsonPrimitive.contentOrNull?.let { name to it }
    }?.toMap().orEmpty()
    val subtitles = (first["subtitles"] as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.contentOrNull?.takeIf(::isReplayableMediaUrl)
    }.orEmpty()
    CastReplaySource(
        url = urls.first(),
        title = first["title"]?.jsonPrimitive?.contentOrNull,
        contentType = first["contentType"]?.jsonPrimitive?.contentOrNull,
        mediaKind = first["mediaKind"]?.jsonPrimitive?.contentOrNull,
        headers = headers,
        subtitles = subtitles,
        playlistPayloadJson = payload.toString().takeIf { items.size > 1 },
    )
}.getOrNull()

internal fun replaySourceFromExternalMedia(media: MediaItem): CastReplaySource? {
    if (media.isScreenMirror || !isReplayableMediaUrl(media.url)) return null
    return CastReplaySource(
        url = media.url,
        title = media.title,
        contentType = media.mimeType,
        mediaKind = media.mediaKind?.wireValue,
        headers = media.headers,
        subtitles = media.subtitles.map { it.url }.filter(::isReplayableMediaUrl),
    )
}

/** Encrypted, bounded phone-only snapshots; losing the device-bound key merely disables recast. */
class CastReplayStore(context: Context, diagnostics: CastAttemptDiagnostics) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _sources = MutableStateFlow(readStored())
    val sources: StateFlow<Map<String, CastReplaySource>> = _sources.asStateFlow()

    init {
        retainOnly(diagnostics.attempts.value.map { it.id }.toSet())
    }

    @Synchronized
    fun put(id: String, source: CastReplaySource) {
        if (!isReplayableMediaUrl(source.url)) return
        val encrypted = runCatching { encrypt(encode(source).toString()) }.getOrNull() ?: return
        val next = LinkedHashMap(_sources.value)
        next[id] = source
        _sources.value = next
        preferences.edit().putString(id, encrypted).apply()
    }

    @Synchronized
    fun delete(id: String) {
        _sources.value = _sources.value - id
        preferences.edit().remove(id).apply()
    }

    @Synchronized
    fun retainOnly(ids: Set<String>) {
        val stale = preferences.all.keys - ids
        if (stale.isEmpty()) return
        _sources.value = _sources.value.filterKeys { it in ids }
        preferences.edit().apply { stale.forEach(::remove) }.apply()
    }

    @Synchronized
    fun clear() {
        _sources.value = emptyMap()
        preferences.edit().clear().apply()
    }

    private fun readStored(): Map<String, CastReplaySource> = buildMap {
        preferences.all.forEach { (id, value) ->
            if (id.isBlank() || value !is String) return@forEach
            val source = runCatching { decode(JSONObject(decrypt(value))) }.getOrNull()
            if (source != null && isReplayableMediaUrl(source.url)) put(id, source)
        }
    }

    private fun encode(source: CastReplaySource) = JSONObject().apply {
        put("url", source.url)
        put("title", source.title)
        put("contentType", source.contentType)
        put("mediaKind", source.mediaKind)
        put("headers", JSONObject(source.headers))
        put("subtitles", JSONArray(source.subtitles))
        put("playlist", source.playlistPayloadJson)
    }

    private fun decode(json: JSONObject): CastReplaySource? {
        val url = json.optString("url")
        if (!isReplayableMediaUrl(url)) return null
        val headersJson = json.optJSONObject("headers") ?: JSONObject()
        val headers = headersJson.keys().asSequence().associateWith { headersJson.optString(it) }
        val subtitlesJson = json.optJSONArray("subtitles") ?: JSONArray()
        val subtitles = (0 until subtitlesJson.length()).mapNotNull { index ->
            subtitlesJson.optString(index).takeIf(::isReplayableMediaUrl)
        }
        return CastReplaySource(
            url = url,
            title = json.optString("title").takeIf { it.isNotBlank() },
            contentType = json.optString("contentType").takeIf { it.isNotBlank() },
            mediaKind = json.optString("mediaKind").takeIf { it.isNotBlank() },
            headers = headers,
            subtitles = subtitles,
            playlistPayloadJson = json.optString("playlist").takeIf {
                it.isNotBlank() && decodePlaylistPayloadJson(it)?.items?.size in 2..MAX_REPLAY_PLAYLIST_ITEMS
            },
        )
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "cast_replay_snapshots"
        private const val KEY_ALIAS = "playbridge_cast_replay_v1"
    }
}

private const val MAX_REPLAY_PLAYLIST_ITEMS = 100
