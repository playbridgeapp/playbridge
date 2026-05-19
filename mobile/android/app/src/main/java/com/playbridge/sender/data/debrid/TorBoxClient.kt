package com.playbridge.sender.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")

    /**
     * Parse the common TorBox JSON envelope and return the data element, or throw on error.
     */
    private fun checkSuccess(body: String): kotlinx.serialization.json.JsonElement {
        val root = json.parseToJsonElement(body).jsonObject
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!success) {
            val detail = root["detail"]?.jsonPrimitive?.content
                ?: root["error"]?.jsonPrimitive?.content
                ?: "Unknown TorBox error"
            throw DebridException(detail)
        }
        return root["data"] ?: throw DebridException("No data field in response")
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
    private fun parseTorrent(obj: kotlinx.serialization.json.JsonObject): DebridTorrentInfo {
        val id = obj["id"]?.jsonPrimitive?.content ?: ""
        val name = obj["name"]?.jsonPrimitive?.content ?: "Unknown"
        val hash = obj["hash"]?.jsonPrimitive?.content ?: ""
        val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val progress = (obj["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 100.0
        val state = obj["download_state"]?.jsonPrimitive?.content
            ?: obj["status"]?.jsonPrimitive?.content
        val status = mapStatus(state)

        // TorBox nests files under "files" as an array
        val filesArray = obj["files"]?.jsonArray
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
        // Use /mylist with id parameter — TorBox returns a single-element list
        val request = requestBuilder("$baseUrl/api/torrents/mylist?id=$id").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            throw DebridException("Failed to get torrent info: ${response.code} $body")
        }

        val data = checkSuccess(body)
        // When id is specified, data may be an object or a single-element array
        val torrentObj: kotlinx.serialization.json.JsonObject = when {
            data is kotlinx.serialization.json.JsonObject -> data
            data is kotlinx.serialization.json.JsonArray && data.size > 0 ->
                data[0].jsonObject
            else -> throw DebridException("Torrent $id not found")
        }
        return@withContext parseTorrent(torrentObj)
    }

    override suspend fun getTorrents(page: Int): List<DebridTorrentInfo> = withContext(Dispatchers.IO) {
        // TorBox supports offset-based pagination. We use page * 50 as offset.
        val offset = (page - 1) * 50
        val request = requestBuilder("$baseUrl/api/torrents/mylist?offset=$offset&limit=50")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")

        if (!response.isSuccessful) {
            throw DebridException("Failed to list torrents: ${response.code} $body")
        }

        val data = checkSuccess(body)
        val array = when (data) {
            is kotlinx.serialization.json.JsonArray -> data
            else -> return@withContext emptyList()
        }

        return@withContext array.map { elem -> parseTorrent(elem.jsonObject) }
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
