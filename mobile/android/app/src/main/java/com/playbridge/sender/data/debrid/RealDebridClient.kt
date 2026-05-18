package com.playbridge.sender.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RealDebridClient(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json
) : DebridProvider {

    override val name: String = "Real-Debrid"
    private val baseUrl = "https://api.real-debrid.com/rest/1.0"

    private fun requestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
    }

    override suspend fun addMagnet(magnetUri: String): String = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("magnet", magnetUri)
            .build()
            
        val request = requestBuilder("$baseUrl/torrents/addMagnet")
            .post(formBody)
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        if (!response.isSuccessful) {
            throw DebridException("Failed to add magnet: ${response.code} $body")
        }
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        return@withContext jsonObj["id"]?.jsonPrimitive?.content ?: throw DebridException("No torrent ID returned")
    }

    override suspend fun addTorrent(torrentBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val requestBody = torrentBytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull())
            
        val request = requestBuilder("$baseUrl/torrents/addTorrent")
            .put(requestBody)
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        if (!response.isSuccessful) {
            throw DebridException("Failed to add torrent file: ${response.code} $body")
        }
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        return@withContext jsonObj["id"]?.jsonPrimitive?.content ?: throw DebridException("No torrent ID returned")
    }

    override suspend fun getTorrentInfo(id: String): DebridTorrentInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder("$baseUrl/torrents/info/$id").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        if (!response.isSuccessful) {
            throw DebridException("Failed to get torrent info: ${response.code} $body")
        }
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        
        val statusStr = jsonObj["status"]?.jsonPrimitive?.content ?: "unknown"
        val status = when (statusStr) {
            "waiting_files_selection" -> TorrentStatus.WAITING_FILES_SELECTION
            "downloading", "queued" -> TorrentStatus.DOWNLOADING
            "downloaded" -> TorrentStatus.READY
            "error", "magnet_error", "virus" -> TorrentStatus.ERROR
            else -> TorrentStatus.UNKNOWN
        }
        
        val filesList = mutableListOf<DebridFile>()
        val rdFiles = jsonObj["files"]?.jsonArray
        if (rdFiles != null) {
            for (elem in rdFiles) {
                val fObj = elem.jsonObject
                filesList.add(
                    DebridFile(
                        id = fObj["id"]?.jsonPrimitive?.content ?: "0",
                        path = fObj["path"]?.jsonPrimitive?.content ?: "",
                        bytes = fObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        selected = fObj["selected"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                )
            }
        }
        
        // When unrestrict links are available, RD provides them as a list of REST API links corresponding to selected files
        val linksArray = jsonObj["links"]?.jsonArray
        val linksList = mutableListOf<String>()
        if (linksArray != null) {
            for (elem in linksArray) {
                linksList.add(elem.jsonPrimitive.content)
            }
        }

        // Map RD links array back to the files that were selected
        // RD links list corresponds 1:1 with the selected files in order.
        var linkIndex = 0
        val mappedFiles = filesList.map { f ->
            if (f.selected == 1 && linkIndex < linksList.size) {
                val restrictedLink = linksList[linkIndex]
                linkIndex++
                f.copy(link = restrictedLink)
            } else {
                f
            }
        }

        return@withContext DebridTorrentInfo(
            id = jsonObj["id"]?.jsonPrimitive?.content ?: id,
            filename = jsonObj["filename"]?.jsonPrimitive?.content ?: "Unknown",
            hash = jsonObj["hash"]?.jsonPrimitive?.content ?: "",
            bytes = jsonObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            progress = jsonObj["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            status = status,
            files = mappedFiles
        )
    }

    override suspend fun getTorrents(page: Int): List<DebridTorrentInfo> = withContext(Dispatchers.IO) {
        val request = requestBuilder("$baseUrl/torrents?page=$page&limit=50").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        if (!response.isSuccessful) {
            throw DebridException("Failed to get torrents: ${response.code} $body")
        }
        
        val jsonArray = json.parseToJsonElement(body).jsonArray
        return@withContext jsonArray.map { elem ->
            val jsonObj = elem.jsonObject
            val statusStr = jsonObj["status"]?.jsonPrimitive?.content ?: "unknown"
            val status = when (statusStr) {
                "waiting_files_selection" -> TorrentStatus.WAITING_FILES_SELECTION
                "downloading", "queued" -> TorrentStatus.DOWNLOADING
                "downloaded" -> TorrentStatus.READY
                "error", "magnet_error", "virus" -> TorrentStatus.ERROR
                else -> TorrentStatus.UNKNOWN
            }
            
            DebridTorrentInfo(
                id = jsonObj["id"]?.jsonPrimitive?.content ?: "",
                filename = jsonObj["filename"]?.jsonPrimitive?.content ?: "Unknown",
                hash = jsonObj["hash"]?.jsonPrimitive?.content ?: "",
                bytes = jsonObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                progress = jsonObj["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                status = status,
                files = emptyList() // The list endpoint usually doesn't have detailed files
            )
        }
    }

    // A helper method to get the raw string array of restricted links
    override suspend fun getRestrictedLinks(id: String): List<String> = withContext(Dispatchers.IO) {
        val request = requestBuilder("$baseUrl/torrents/info/$id").get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val jsonObj = json.parseToJsonElement(body).jsonObject
        
        val linksArray = jsonObj["links"]?.jsonArray ?: return@withContext emptyList()
        return@withContext linksArray.map { it.jsonPrimitive.content }
    }

    override suspend fun selectFiles(id: String, fileIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val fileIdsStr = fileIds.joinToString(",")
        val formBody = FormBody.Builder()
            .add("files", fileIdsStr.ifEmpty { "all" })
            .build()
            
        val request = requestBuilder("$baseUrl/torrents/selectFiles/$id")
            .post(formBody)
            .build()
            
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string()
            throw DebridException("Failed to select files: ${response.code} $body")
        }
    }

    override suspend fun unrestrictLink(link: String): DebridUnrestrictedLink = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("link", link)
            .build()
            
        val request = requestBuilder("$baseUrl/unrestrict/link")
            .post(formBody)
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        if (!response.isSuccessful) {
            throw DebridException("Failed to unrestrict link: ${response.code} $body")
        }
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        
        return@withContext DebridUnrestrictedLink(
            id = jsonObj["id"]?.jsonPrimitive?.content ?: "",
            filename = jsonObj["filename"]?.jsonPrimitive?.content ?: "",
            mimeType = jsonObj["mimeType"]?.jsonPrimitive?.content ?: "",
            filesize = jsonObj["filesize"]?.jsonPrimitive?.longOrNull ?: 0L,
            downloadUrl = jsonObj["download"]?.jsonPrimitive?.content ?: ""
        )
    }

    override suspend fun deleteTorrent(id: String): Unit = withContext(Dispatchers.IO) {
        val request = requestBuilder("$baseUrl/torrents/delete/$id")
            .delete()
            .build()
            
        client.newCall(request).execute().use { response -> 
            if (!response.isSuccessful) {
                // Ignore 404s for deletion
                if (response.code != 404) {
                    val body = response.body?.string()
                    throw DebridException("Failed to delete torrent: ${response.code} $body")
                }
            }
        }
    }
}
