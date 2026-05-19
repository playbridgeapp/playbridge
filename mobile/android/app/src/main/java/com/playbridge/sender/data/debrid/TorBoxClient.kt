package com.playbridge.sender.data.debrid

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TorBox debrid client.
 *
 * API base: https://api.torbox.app/v1
 * Auth: Authorization: Bearer <api_key>
 *
 * Key differences from other providers:
 * - No classic "unrestrict" step; instead you call /api/torrents/requestdl
 *   with torrent_id + file_id to get a direct CDN link.
 * - Files are nested inside the torrent object returned by /mylist and /torrentinfo.
 * - "selectFiles" is not required; TorBox downloads all files automatically.
 * - Deletion uses POST /api/torrents/controltorrent with {"torrent_id": id, "operation": "delete"}.
 */
class TorBoxClient(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json
) : DebridProvider {

    override val name: String = "TorBox"
    private val baseUrl = "https://api.torbox.app/v1"

    companion object {
        private const val TAG = "TorBoxClient"
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")

    /**
     * Parse the common TorBox JSON envelope and return the data element, or throw on error.
     * Logs the raw body and parsed outcome to Logcat under the tag "TorBoxClient".
     */
    private fun checkSuccess(body: String): kotlinx.serialization.json.JsonElement {
        Log.d(TAG, "Raw API response (first 2000 chars): ${body.take(2000)}")
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}\nBody was: $body")
            throw DebridException("Failed to parse TorBox response: ${e.message}")
        }
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!success) {
            val detail = root["detail"]?.jsonPrimitive?.content
                ?: root["error"]?.jsonPrimitive?.content
                ?: "Unknown TorBox error"
            Log.e(TAG, "TorBox API reported failure. detail='$detail' | full root=$root")
            throw DebridException(detail)
        }
        val data = root["data"]
        Log.d(TAG, "TorBox success=true, data type=${data?.let { it::class.simpleName } ?: "null"}, data=${data.toString().take(500)}")
        return data ?: throw DebridException("No data field in response")
    }

    /**
     * Map TorBox download_state strings to our common [TorrentStatus].
     */
    private fun mapStatus(state: String?): TorrentStatus = when (state?.lowercase()) {
        "completed", "cached", "uploading" -> TorrentStatus.READY
        "downloading", "metadl", "checkingResumeData", "checkingresume",
        "stalledDL", "stalled", "paused", "queued" -> TorrentStatus.DOWNLOADING
        "error", "missingFiles" -> TorrentStatus.ERROR
        else -> TorrentStatus.UNKNOWN
    }

    /**
     * Build a [DebridTorrentInfo] from a TorBox torrent JSON object.
     * Works for both the list endpoint and the detail endpoint.
     */
    private fun parseTorrent(obj: JsonObject): DebridTorrentInfo {
        val id = obj["id"]?.jsonPrimitive?.content ?: ""
        val name = obj["name"]?.jsonPrimitive?.content ?: "Unknown"
        val hash = obj["hash"]?.jsonPrimitive?.content ?: ""
        val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val progress = (obj["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 100.0
        val state = obj["download_state"]?.jsonPrimitive?.content
            ?: obj["status"]?.jsonPrimitive?.content
        val status = mapStatus(state)

        // TorBox nests files under "files" as an array.
        // IMPORTANT: obj["files"] returns JsonNull (not Kotlin null) when the JSON value is null,
        // so we must guard against JsonNull explicitly before calling .jsonArray.
        val filesElement = obj["files"]
        val filesArray: JsonArray? = when (filesElement) {
            is JsonArray -> filesElement
            null, is JsonNull -> {
                Log.d(TAG, "parseTorrent: torrent '$name' (id=$id) has no files array (value=$filesElement)")
                null
            }
            else -> {
                Log.w(TAG, "parseTorrent: torrent '$name' (id=$id) 'files' field is unexpected type ${filesElement::class.simpleName}: $filesElement")
                null
            }
        }
        val files = filesArray?.mapIndexed { index, elem ->
            val fObj = elem.jsonObject
            DebridFile(
                id = fObj["id"]?.jsonPrimitive?.content ?: index.toString(),
                path = fObj["name"]?.jsonPrimitive?.content ?: "File $index",
                bytes = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                selected = 1, // TorBox always makes all files available
                link = "" // Links are resolved on-demand via requestdl
            )
        } ?: emptyList()

        Log.d(TAG, "parseTorrent: id=$id name='$name' state=$state status=$status files=${files.size}")

        return DebridTorrentInfo(
            id = id,
            filename = name,
            hash = hash,
            bytes = size,
            progress = progress,
            status = status,
            files = files
        )
    }

    // ── DebridProvider implementation ─────────────────────────────────────────

    override suspend fun addMagnet(magnetUri: String): String = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("magnet", magnetUri)
            .add("seed", "1") // auto-seed preference
            .build()

        val request = requestBuilder("$baseUrl/api/torrents/createtorrent")
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            throw DebridException("Failed to add magnet: ${response.code} $body")
        }

        val data = checkSuccess(body).jsonObject
        return@withContext data["torrent_id"]?.jsonPrimitive?.content
            ?: data["id"]?.jsonPrimitive?.content
            ?: throw DebridException("No torrent ID returned by TorBox")
    }

    override suspend fun addTorrent(torrentBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "file.torrent",
                torrentBytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull())
            )
            .addFormDataPart("seed", "1")
            .build()

        val request = requestBuilder("$baseUrl/api/torrents/createtorrent")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            throw DebridException("Failed to add torrent file: ${response.code} $body")
        }

        val data = checkSuccess(body).jsonObject
        return@withContext data["torrent_id"]?.jsonPrimitive?.content
            ?: data["id"]?.jsonPrimitive?.content
            ?: throw DebridException("No torrent ID returned by TorBox")
    }

    override suspend fun getTorrentInfo(id: String): DebridTorrentInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "getTorrentInfo: fetching info for id=$id")
        // Use /mylist with id parameter — TorBox returns a single-element list
        val request = requestBuilder("$baseUrl/api/torrents/mylist?id=$id").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "getTorrentInfo HTTP error: code=${response.code} body=$body")
            throw DebridException("Failed to get torrent info: ${response.code} $body")
        }

        val data = checkSuccess(body)
        // When id is specified, data may be an object or a single-element array
        val torrentObj: JsonObject = when {
            data is JsonObject -> data
            data is JsonArray && data.size > 0 -> data[0].jsonObject
            data is JsonNull || (data is JsonArray && data.isEmpty()) -> {
                Log.e(TAG, "getTorrentInfo: no torrent found for id=$id, data=$data")
                throw DebridException("Torrent $id not found")
            }
            else -> {
                Log.e(TAG, "getTorrentInfo: unexpected data type ${data::class.simpleName} for id=$id")
                throw DebridException("Torrent $id not found")
            }
        }
        return@withContext parseTorrent(torrentObj)
    }

    override suspend fun getTorrents(page: Int): List<DebridTorrentInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getTorrents: page=$page")
        // TorBox supports offset-based pagination. We use page * 50 as offset.
        val offset = (page - 1) * 50
        val request = requestBuilder("$baseUrl/api/torrents/mylist?offset=$offset&limit=50")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "getTorrents HTTP error: code=${response.code} body=${body.take(500)}")
            throw DebridException("Failed to list torrents: ${response.code} $body")
        }

        val data = checkSuccess(body)
        val array: JsonArray = when (data) {
            is JsonArray -> {
                Log.d(TAG, "getTorrents: received ${data.size} items for page=$page")
                data
            }
            is JsonNull -> {
                // TorBox returns null data when the list is empty
                Log.d(TAG, "getTorrents: data is null (empty list) for page=$page")
                return@withContext emptyList()
            }
            else -> {
                Log.w(TAG, "getTorrents: unexpected data type ${data::class.simpleName} for page=$page, value=${data.toString().take(200)}")
                return@withContext emptyList()
            }
        }

        return@withContext array.mapIndexed { index, elem ->
            try {
                parseTorrent(elem.jsonObject)
            } catch (e: Exception) {
                Log.e(TAG, "getTorrents: failed to parse torrent at index $index: ${e.message}\nelem=${elem.toString().take(300)}")
                throw e
            }
        }
    }

    /**
     * TorBox does not require file selection — all files are downloaded.
     */
    override suspend fun selectFiles(id: String, fileIds: List<String>) {
        // No-op: TorBox auto-selects all files
    }

    /**
     * TorBox uses /api/torrents/requestdl with torrent_id + file_id.
     * For the [link] parameter we encode torrentId:fileId as a compound key,
     * since both are required for the API call.
     *
     * The compound format is: "<torrent_id>:<file_id>"
     * This is set on [DebridFile.link] when we resolve links for individual files.
     */
    override suspend fun unrestrictLink(link: String): DebridUnrestrictedLink =
        withContext(Dispatchers.IO) {
            // link is expected to be "torrentId:fileId"
            val parts = link.split(":")
            if (parts.size < 2) {
                throw DebridException("Invalid TorBox link format (expected torrentId:fileId): $link")
            }
            val torrentId = parts[0]
            val fileId = parts[1]

            val url = "$baseUrl/api/torrents/requestdl" +
                "?token=$apiKey" +
                "&torrent_id=$torrentId" +
                "&file_id=$fileId" +
                "&zip_link=false"

            val request = requestBuilder(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw DebridException("Empty response")

            if (!response.isSuccessful) {
                throw DebridException("Failed to request DL link: ${response.code} $body")
            }

            // data is the CDN URL string directly
            val root = json.parseToJsonElement(body).jsonObject
            val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!success) {
                val detail = root["detail"]?.jsonPrimitive?.content
                    ?: root["error"]?.jsonPrimitive?.content
                    ?: "TorBox error"
                throw DebridException(detail)
            }

            val downloadUrl = root["data"]?.jsonPrimitive?.content
                ?: throw DebridException("No download URL returned by TorBox")

            return@withContext DebridUnrestrictedLink(
                id = fileId,
                filename = fileId,
                mimeType = "",
                filesize = 0L,
                downloadUrl = downloadUrl
            )
        }

    /**
     * Returns compound "torrentId:fileId" strings for each file, so callers can
     * pass them directly to [unrestrictLink].
     */
    override suspend fun getRestrictedLinks(id: String): List<String> = withContext(Dispatchers.IO) {
        val info = getTorrentInfo(id)
        return@withContext info.files.map { file -> "$id:${file.id}" }
    }

    /**
     * Deletes a torrent using the controltorrent endpoint.
     * POST /api/torrents/controltorrent { "torrent_id": id, "operation": "delete" }
     */
    override suspend fun deleteTorrent(id: String): Unit = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("torrent_id", id)
            .add("operation", "delete")
            .build()

        val request = requestBuilder("$baseUrl/api/torrents/controltorrent")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                val body = response.body?.string()
                throw DebridException("Failed to delete torrent: ${response.code} $body")
            }
        }
    }
}
