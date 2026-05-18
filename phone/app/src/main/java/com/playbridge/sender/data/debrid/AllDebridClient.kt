package com.playbridge.sender.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

class AllDebridClient(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val json: Json
) : DebridProvider {

    override val name: String = "All-Debrid"
    private val baseUrl = "https://api.alldebrid.com/v4"
    private val agent = "PlayBridgeApp"

    private fun buildUrl(path: String, additionalParams: String = ""): String {
        val sep = if (path.contains("?")) "&" else "?"
        return "$baseUrl$path${sep}agent=$agent&apikey=$apiKey$additionalParams"
    }

    override suspend fun addMagnet(magnetUri: String): String = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("magnets[]", magnetUri)
            .build()
            
        val request = Request.Builder()
            .url(buildUrl("/magnet/upload"))
            .post(formBody)
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        if (jsonObj["status"]?.jsonPrimitive?.content != "success") {
            throw DebridException("Failed to add magnet: $body")
        }
        
        val dataObj = jsonObj["data"]?.jsonObject
        val magnetsArr = dataObj?.get("magnets")?.jsonArray
        val firstMagnet = magnetsArr?.firstOrNull()?.jsonObject
        
        return@withContext firstMagnet?.get("id")?.jsonPrimitive?.content ?: throw DebridException("No ID returned")
    }

    override suspend fun addTorrent(torrentBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files[]", 
                "file.torrent", 
                torrentBytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull())
            )
            .build()
            
        val request = Request.Builder()
            .url(buildUrl("/magnet/upload/file"))
            .post(requestBody)
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        if (jsonObj["status"]?.jsonPrimitive?.content != "success") {
            throw DebridException("Failed to add torrent: $body")
        }
        
        val dataObj = jsonObj["data"]?.jsonObject
        val filesArr = dataObj?.get("files")?.jsonArray
        val firstFile = filesArr?.firstOrNull()?.jsonObject
        
        return@withContext firstFile?.get("id")?.jsonPrimitive?.content ?: throw DebridException("No ID returned")
    }

    override suspend fun getTorrentInfo(id: String): DebridTorrentInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl("/magnet/status", "&id=$id"))
            .get()
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        if (jsonObj["status"]?.jsonPrimitive?.content != "success") {
            throw DebridException("Failed to get status: $body")
        }
        
        val dataObj = jsonObj["data"]?.jsonObject
        val magnetsObj = dataObj?.get("magnets")?.jsonObject ?: throw DebridException("No magnets object")
        
        val adStatus = magnetsObj["statusCode"]?.jsonPrimitive?.intOrNull ?: 0
        // AllDebrid status codes: 0-3 queued/processing, 4 downloading, 5 ready, 6/7/8/9 error
        val status = when (adStatus) {
            4 -> TorrentStatus.DOWNLOADING
            5, 10, 11 -> TorrentStatus.READY  // 5 is ready, 11 is returning unrestrict links? AD logic differs.
            6, 7, 8, 9, 10 -> TorrentStatus.ERROR
            else -> TorrentStatus.DOWNLOADING // Treat queued as downloading
        }
        
        val linksArray = magnetsObj["links"]?.jsonArray
        val filesList = mutableListOf<DebridFile>()
        
        // AllDebrid returns actual links instead of raw files in status if it's ready.
        if (linksArray != null) {
            for ((index, elem) in linksArray.withIndex()) {
                val fObj = elem.jsonObject
                filesList.add(
                    DebridFile(
                        id = index.toString(),
                        path = fObj["filename"]?.jsonPrimitive?.content ?: "File $index",
                        bytes = fObj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        selected = 1 // Already selected by default on AD
                    )
                )
            }
        }
        
        return@withContext DebridTorrentInfo(
            id = id,
            filename = magnetsObj["filename"]?.jsonPrimitive?.content ?: "Unknown",
            hash = magnetsObj["hash"]?.jsonPrimitive?.content ?: "",
            bytes = magnetsObj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            progress = (magnetsObj["downloaded"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 
                       (magnetsObj["size"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 } ?: 1.0) * 100,
            status = status,
            files = filesList
        )
    }

    override suspend fun getTorrents(page: Int): List<DebridTorrentInfo> = withContext(Dispatchers.IO) {
        // AllDebrid returns all magnets in a single call — no server-side pagination.
        if (page > 1) return@withContext emptyList()
        val request = Request.Builder()
            .url(buildUrl("/magnet/status"))
            .get()
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        if (jsonObj["status"]?.jsonPrimitive?.content != "success") {
            throw DebridException("Failed to get status: $body")
        }
        
        val dataObj = jsonObj["data"]?.jsonObject
        val magnetsArr = dataObj?.get("magnets")?.jsonArray ?: return@withContext emptyList()
        
        return@withContext magnetsArr.map { elem ->
            val magnetsObj = elem.jsonObject
            val adStatus = magnetsObj["statusCode"]?.jsonPrimitive?.intOrNull ?: 0
            val status = when (adStatus) {
                4 -> TorrentStatus.DOWNLOADING
                5, 10, 11 -> TorrentStatus.READY
                6, 7, 8, 9, 10 -> TorrentStatus.ERROR
                else -> TorrentStatus.DOWNLOADING
            }
            
            DebridTorrentInfo(
                id = magnetsObj["id"]?.jsonPrimitive?.content ?: "",
                filename = magnetsObj["filename"]?.jsonPrimitive?.content ?: "Unknown",
                hash = magnetsObj["hash"]?.jsonPrimitive?.content ?: "",
                bytes = magnetsObj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                progress = (magnetsObj["downloaded"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 
                           (magnetsObj["size"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 } ?: 1.0) * 100,
                status = status,
                files = emptyList()
            )
        }
    }

    override suspend fun selectFiles(id: String, fileIds: List<String>): Unit = withContext(Dispatchers.IO) {
        // AllDebrid does not require selecting files, it automatically downloads all.
    }

    override suspend fun unrestrictLink(link: String): DebridUnrestrictedLink = withContext(Dispatchers.IO) {
        // Some AllDebrid responses give raw unrestrictable link. Let's call /link/unlock
        val request = Request.Builder()
            .url(buildUrl("/link/unlock", "&link=$link"))
            .get()
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw DebridException("Empty response")
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        if (jsonObj["status"]?.jsonPrimitive?.content != "success") {
            throw DebridException("Failed to unrestrict: $body")
        }
        
        val dataObj = jsonObj["data"]?.jsonObject ?: throw DebridException("No data")
        
        return@withContext DebridUnrestrictedLink(
            id = dataObj["id"]?.jsonPrimitive?.content ?: "",
            filename = dataObj["filename"]?.jsonPrimitive?.content ?: "",
            mimeType = "",
            filesize = dataObj["filesize"]?.jsonPrimitive?.longOrNull ?: 0L,
            downloadUrl = dataObj["link"]?.jsonPrimitive?.content ?: ""
        )
    }

    override suspend fun getRestrictedLinks(id: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl("/magnet/status", "&id=$id"))
            .get()
            .build()
            
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        
        val jsonObj = json.parseToJsonElement(body).jsonObject
        val dataObj = jsonObj["data"]?.jsonObject
        val magnetsObj = dataObj?.get("magnets")?.jsonObject
        val linksArray = magnetsObj?.get("links")?.jsonArray ?: return@withContext emptyList()
        
        return@withContext linksArray.map { it.jsonObject["link"]?.jsonPrimitive?.content ?: "" }
    }

    override suspend fun deleteTorrent(id: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl("/magnet/delete", "&id=$id"))
            .get() // Actually /magnet/delete on AD is GET
            .build()
            
        client.newCall(request).execute().close()
    }
}
