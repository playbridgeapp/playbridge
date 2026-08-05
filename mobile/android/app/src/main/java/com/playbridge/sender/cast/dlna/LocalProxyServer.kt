package com.playbridge.sender.cast.dlna

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Local HTTP proxy that lets a DLNA renderer (or TV browser via Via phone) fetch
 * media the phone has the rights or headers for.
 *
 * Upstream remote fetches use **HttpURLConnection** — the same stack Media3
 * [androidx.media3.datasource.DefaultHttpDataSource] uses for on-phone ExoPlayer.
 * OkHttp/reqwest TLS fingerprints are rejected by some live CDNs (403) even when
 * ExoPlayer plays the same URL successfully.
 *
 * Raw ServerSocket (no NanoHTTPD/Ktor) — matches the hand-rolled SSDP/AVTransport
 * layer and adds no dependency.
 */
class LocalProxyServer(
    private val resolver: ContentResolver,
) {
    sealed interface Entry {
        data class Remote(val url: String, val headers: Map<String, String>, val mime: String?) : Entry
        data class Local(val uri: Uri, val mime: String?) : Entry
    }

    // accessOrder=true + removeEldestEntry => LRU eviction; synchronized for the accept threads.
    private val originToToken: MutableMap<String, String> = Collections.synchronizedMap(HashMap())
    private val lastGoodPlaylist: MutableMap<String, CachedPlaylist> =
        Collections.synchronizedMap(HashMap())
    private val entries: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
                if (size <= MAX_ENTRIES) return false
                // Keep origin→token map consistent when LRU drops a remote entry.
                val dropped = eldest.value
                if (dropped is Entry.Remote) {
                    originToToken.remove(dropped.url)
                }
                lastGoodPlaylist.remove(eldest.key)
                return true
            }
        },
    )
    private var server: ServerSocket? = null
    @Volatile private var running = false
    var port: Int = 0
        private set

    /** True when the current HLS stream is live (media playlist without #EXT-X-ENDLIST). */
    @Volatile
    var isLiveStream = false
        private set

    /**
     * Total duration (ms) of the current HLS VOD, summed from the media playlist's
     * #EXTINF tags; 0 when unknown or live. `MediaMetadataRetriever` can't open an
     * .m3u8, so this is the only reliable HLS duration source — see DlnaCastTarget.
     */
    @Volatile
    var vodDurationMs = 0L
        private set

    private data class CachedPlaylist(val atMs: Long, val body: ByteArray)

    fun start(): Int {
        if (running) return port
        val s = ServerSocket(0)
        server = s
        port = s.localPort
        running = true
        thread(name = "dlna-proxy", isDaemon = true) { acceptLoop(s) }
        Log.d(TAG, "Proxy listening on $port (lan=${lanIp()})")
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        entries.clear()
        originToToken.clear()
        lastGoodPlaylist.clear()
    }

    /** Register a remote web stream; returns the proxy URL to hand the renderer. */
    fun publish(url: String, headers: Map<String, String>, mime: String?): String {
        isLiveStream = false // re-learned when an HLS media playlist is served
        vodDurationMs = 0L
        cachedLanIp = lanIp() // refresh once per cast; register() reuses it
        return register(Entry.Remote(url, filterHeaders(headers), mime), guessExt(url, mime))
    }

    /** Register a local file (content:// / file Uri); returns the proxy URL. */
    fun publishLocal(uri: Uri, mime: String?): String {
        isLiveStream = false
        vodDurationMs = 0L
        cachedLanIp = lanIp()
        return register(Entry.Local(uri, mime), extForMime(mime))
    }

    /**
     * Drop browser-context / hop-by-hop headers that break third-party CDN fetches when
     * replaying a captured page request from the phone proxy (not the page origin).
     * Origin is intentionally kept: origin-protected same-site CDNs return 403 without
     * it (Desktop forwards Origin to the CDN too).
     */
    private fun filterHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { k ->
            val lk = k.lowercase()
            !lk.startsWith("sec-fetch") &&
                !lk.startsWith("sec-ch") &&
                lk != "host" &&
                lk != "accept-encoding" &&
                lk != "connection" &&
                lk != "range" &&
                lk != "content-length" &&
                lk != "content-type" &&
                lk != "transfer-encoding" &&
                lk != "te" &&
                lk != "upgrade" &&
                lk != "http2-settings"
        }

    /**
     * LAN IP cached per publish(): register() runs once per URL in a playlist rewrite —
     * enumerating every NetworkInterface per segment line (thousands for a long VOD,
     * re-done on every live-playlist refresh) is measurable syscall churn.
     */
    @Volatile private var cachedLanIp: String? = null

    /**
     * Register [entry] under a stable token. Remote origins reuse the same token so live
     * HLS playlist rewrites do not mint thousands of unique URLs per refresh (LRU thrash
     * + hls.js levelParsingError when a level URL's mapping disappears mid-session).
     */
    private fun register(entry: Entry, ext: String): String {
        val ip = cachedLanIp ?: lanIp().also { cachedLanIp = it }
        if (entry is Entry.Remote) {
            synchronized(entries) {
                val existing = originToToken[entry.url]
                if (existing != null && entries.containsKey(existing)) {
                    // Touch LRU + refresh headers/mime for this origin.
                    entries[existing] = entry
                    return "http://$ip:$port/$existing$ext"
                }
                val token = UUID.randomUUID().toString().replace("-", "").take(16)
                entries[token] = entry
                originToToken[entry.url] = token
                return "http://$ip:$port/$token$ext"
            }
        }
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        entries[token] = entry
        return "http://$ip:$port/$token$ext"
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try {
                s.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            thread(isDaemon = true) {
                runCatching { handle(socket) }.onFailure { Log.d(TAG, "conn ended: ${it.message}") }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use { sock ->
        // A renderer that opens a connection and never finishes its headers would
        // otherwise pin this daemon thread forever on readLine().
        sock.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return // "GET /<token>.mp4 HTTP/1.1"
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1]

        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).equals("Range", true)) {
                range = line.substring(idx + 1).trim()
            }
        }

        val out = sock.getOutputStream()
        // Browser cast is cross-origin (receiver port ≠ proxy port). Range/segment
        // fetches can preflight; answer OPTIONS without hitting upstream.
        if (method == "OPTIONS") {
            writeCorsPreflight(out)
            return
        }
        val token = path.trimStart('/').substringBefore('.').substringBefore('?')
        when (val entry = entries[token]) {
            null -> writeStatus(out, 404, "Not Found")
            is Entry.Remote -> serveRemote(token, entry, method, range, out)
            is Entry.Local -> serveLocal(entry, method, range, out)
        }
    }

    private fun serveRemote(
        token: String,
        entry: Entry.Remote,
        method: String,
        range: String?,
        out: OutputStream,
    ) {
        val wantPlaylist = isLikelyPlaylistMeta(entry.mime, null, entry.url)
        val upstream = try {
            openUpstream(entry, range, retries = if (wantPlaylist) 3 else 1)
        } catch (e: Exception) {
            Log.w(TAG, "upstream open failed: ${e.message}")
            if (serveStalePlaylistIfAny(token, out, "open failed: ${e.message}")) return
            writeError(out, 502, "Upstream error: ${e.message}")
            return
        }
        try {
            val ctype = upstream.contentType
            val finalUrl = upstream.finalUrl
            val contentLength = upstream.contentLength
            if (method == "HEAD") {
                writeHead(
                    out,
                    code = upstream.code,
                    reason = upstream.message,
                    mime = entry.mime ?: ctype ?: "video/mp4",
                    contentLength = contentLength,
                    contentRange = upstream.contentRange,
                )
                return
            }

            val maybePlaylist = isLikelyPlaylistMeta(entry.mime, ctype, finalUrl) &&
                (contentLength < 0L || contentLength <= MAX_PLAYLIST_BUFFER_BYTES)

            if (maybePlaylist || wantPlaylist) {
                val bodyBytes = readAtMost(upstream.inputStream, MAX_PLAYLIST_BUFFER_BYTES)
                if (bodyBytes == null) {
                    writeError(out, 502, "Playlist too large to buffer")
                    return
                }
                val bodyText = runCatching { String(bodyBytes, Charsets.UTF_8) }.getOrNull().orEmpty()
                val looksLikeHls = bodyText.trimStart().startsWith("#EXTM3U")
                if (looksLikeHls) {
                    Log.d(TAG, "playlist HTTP ${upstream.code} ($ctype) bytes=${bodyBytes.size}")
                    val rewritten = rewritePlaylist(bodyText, finalUrl, entry.headers).toByteArray()
                    lastGoodPlaylist[token] = CachedPlaylist(System.currentTimeMillis(), rewritten)
                    writeBufferedResponse(
                        out,
                        code = 200,
                        reason = "OK",
                        contentType = "application/vnd.apple.mpegurl",
                        body = rewritten,
                        extraHeaders = listOf("Cache-Control: no-cache"),
                    )
                    return
                }
                // Live playlists refresh every few seconds; a single 403/HTML blip used to
                // kill hls.js with levelParsingError. Prefer last good playlist briefly.
                if (serveStalePlaylistIfAny(
                        token,
                        out,
                        "HTTP ${upstream.code} ct=$ctype bytes=${bodyBytes.size}",
                    )
                ) {
                    return
                }
                Log.w(
                    TAG,
                    "upstream HTTP ${upstream.code} ct=$ctype bytes=${bodyBytes.size} " +
                        "keys=${entry.headers.keys.joinToString()} (not #EXTM3U)",
                )
                writeError(
                    out,
                    502,
                    "Upstream HTTP ${upstream.code}: not an HLS playlist",
                )
                return
            }

            if (upstream.code !in 200..299 && upstream.inputStream == null) {
                writeError(out, 502, "Upstream HTTP ${upstream.code}")
                return
            }
            streamUpstreamBody(
                out,
                code = upstream.code,
                reason = upstream.message,
                mime = entry.mime ?: ctype ?: "application/octet-stream",
                contentLength = contentLength,
                contentRange = upstream.contentRange,
                input = upstream.inputStream,
            )
        } finally {
            upstream.close()
        }
    }

    /** Serve last successfully rewritten playlist if fresh enough (live resilience). */
    private fun serveStalePlaylistIfAny(token: String, out: OutputStream, why: String): Boolean {
        val cached = lastGoodPlaylist[token] ?: return false
        val age = System.currentTimeMillis() - cached.atMs
        if (age > STALE_PLAYLIST_MAX_AGE_MS) return false
        Log.w(TAG, "serving stale playlist age=${age}ms after $why")
        writeBufferedResponse(
            out,
            code = 200,
            reason = "OK",
            contentType = "application/vnd.apple.mpegurl",
            body = cached.body,
            extraHeaders = listOf("Cache-Control: no-cache", "X-PlayBridge-Stale: 1"),
        )
        return true
    }

    private fun isLikelyPlaylistMeta(mime: String?, ctype: String?, finalUrl: String): Boolean {
        if (mime?.contains("mpegurl", true) == true) return true
        if (ctype?.contains("mpegurl", true) == true) return true
        if (ctype?.contains("m3u8", true) == true) return true
        if (finalUrl.substringBefore('?').endsWith(".m3u8", true)) return true
        return false
    }

    /**
     * Open origin with HttpURLConnection (Media3/ExoPlayer stack). On 401/403/429,
     * retry with a minimal header set. [retries] > 1 adds brief backoff for live
     * playlist refreshes that occasionally flake.
     */
    private fun openUpstream(
        entry: Entry.Remote,
        range: String?,
        retries: Int = 1,
    ): UpstreamResponse {
        fun connect(headers: Map<String, String>): UpstreamResponse {
            val conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 45_000
                requestMethod = "GET"
                useCaches = false
                doInput = true
                headers.forEach { (k, v) ->
                    if (k.equals("Range", ignoreCase = true)) return@forEach
                    runCatching { setRequestProperty(k, v) }
                }
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    setRequestProperty("User-Agent", DEFAULT_UA)
                }
                if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                    setRequestProperty("Accept", "*/*")
                }
                range?.let { setRequestProperty("Range", it) }
            }
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            val stream = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (_: Exception) {
                null
            }
            return UpstreamResponse(
                code = code,
                message = conn.responseMessage ?: if (code == 206) "Partial Content" else "OK",
                contentType = conn.contentType,
                contentLength = conn.contentLengthLong,
                contentRange = conn.getHeaderField("Content-Range"),
                finalUrl = conn.url?.toString() ?: entry.url,
                inputStream = stream,
                connection = conn,
            )
        }

        val minimal = entry.headers.filterKeys { k ->
            val lk = k.lowercase()
            lk == "user-agent" || lk == "referer" || lk == "cookie" || lk == "authorization"
        }.toMutableMap()
        if (minimal.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            minimal["User-Agent"] = DEFAULT_UA
        }

        var last: UpstreamResponse? = null
        val attempts = maxOf(1, retries)
        for (i in 0 until attempts) {
            val headersForAttempt = if (i == 0) entry.headers else minimal
            val resp = connect(headersForAttempt)
            if (resp.code in 200..299 || resp.code == 206 || range != null) return resp
            last?.close()
            last = resp
            if (resp.code !in listOf(401, 403, 429, 500, 502, 503) && i > 0) break
            if (i + 1 < attempts) {
                Log.w(TAG, "upstream HTTP ${resp.code} attempt ${i + 1}/$attempts; retrying")
                resp.close()
                last = null
                try {
                    Thread.sleep(150L * (i + 1))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return last ?: connect(entry.headers)
    }

    private data class UpstreamResponse(
        val code: Int,
        val message: String,
        val contentType: String?,
        val contentLength: Long,
        val contentRange: String?,
        val finalUrl: String,
        val inputStream: InputStream?,
        private val connection: HttpURLConnection,
    ) {
        fun close() {
            runCatching { inputStream?.close() }
            runCatching { connection.disconnect() }
        }
    }

    /** Read up to [max] bytes; returns null if the stream is larger (caller must not OOM). */
    private fun readAtMost(input: InputStream?, max: Int): ByteArray? {
        if (input == null) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            total += n
        }
        // One more byte means the body exceeds the playlist budget.
        if (input.read() >= 0) return null
        return out.toByteArray()
    }

    private fun streamUpstreamBody(
        out: OutputStream,
        code: Int,
        reason: String,
        mime: String,
        contentLength: Long,
        contentRange: String?,
        input: InputStream?,
    ) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $mime\r\n")
        if (contentLength >= 0) sb.append("Content-Length: $contentLength\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("Accept-Ranges: bytes\r\n")
        appendCorsHeaders(sb)
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        input?.use { src ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun writeBufferedResponse(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        contentRange: String? = null,
        extraHeaders: List<String> = emptyList(),
    ) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("Accept-Ranges: bytes\r\n")
        appendCorsHeaders(sb)
        extraHeaders.forEach { sb.append(it).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeHead(
        out: OutputStream,
        code: Int,
        reason: String,
        mime: String,
        contentLength: Long,
        contentRange: String?,
    ) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $mime\r\n")
        if (contentLength >= 0) sb.append("Content-Length: $contentLength\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("Accept-Ranges: bytes\r\n")
        appendCorsHeaders(sb)
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun appendCorsHeaders(sb: StringBuilder) {
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append(
            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n",
        )
    }

    private fun writeError(out: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        val sb = StringBuilder("HTTP/1.1 $code Error\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        appendCorsHeaders(sb)
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeCorsPreflight(out: OutputStream) {
        val sb = StringBuilder("HTTP/1.1 204 No Content\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Max-Age: 86400\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun serveLocal(entry: Entry.Local, method: String, range: String?, out: OutputStream) {
        val pfd = resolver.openFileDescriptor(entry.uri, "r")
        if (pfd == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val total = pfd.statSize
        val mime = entry.mime ?: resolver.getType(entry.uri) ?: "video/mp4"
        val r = parseRange(range, total)

        val sb = StringBuilder()
        val start: Long
        val length: Long
        if (r == null) {
            start = 0L
            length = total
            sb.append("HTTP/1.1 200 OK\r\n").append("Content-Type: $mime\r\n")
            if (total >= 0) sb.append("Content-Length: $total\r\n")
        } else {
            start = r.first
            val end = r.second
            length = end - start + 1
            sb.append("HTTP/1.1 206 Partial Content\r\n").append("Content-Type: $mime\r\n")
            sb.append("Content-Range: bytes $start-$end/$total\r\n")
            sb.append("Content-Length: $length\r\n")
        }
        sb.append("Accept-Ranges: bytes\r\n").append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())

        if (method == "HEAD") {
            pfd.close()
            out.flush()
            return
        }
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
            if (start > 0) fis.channel.position(start)
            if (r == null) fis.copyTo(out, 64 * 1024) else copyExactly(fis, out, length)
        }
        out.flush()
    }

    /** Rewrite every URL in an m3u8 to a proxy URL so headers reach all sub-requests. */
    private fun rewritePlaylist(body: String, baseUrl: String, headers: Map<String, String>): String {
        // Only a media playlist (#EXTINF) carries liveness/duration; the master (variants) has
        // neither, so leave the publish()-reset values in place for it. VOD => #EXT-X-ENDLIST.
        if (body.contains("#EXTINF")) {
            val live = !body.contains("#EXT-X-ENDLIST")
            isLiveStream = live
            vodDurationMs = if (live) 0L else sumExtInf(body)
        }
        val uriAttr = Regex("URI=\"([^\"]*)\"")
        return body.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") ->
                    uriAttr.replace(line) { m -> "URI=\"${proxify(m.groupValues[1], baseUrl, headers)}\"" }
                else -> proxify(line, baseUrl, headers)
            }
        }
    }

    private fun proxify(ref: String, baseUrl: String, headers: Map<String, String>): String {
        val abs = resolve(baseUrl, ref)
        val pathOnly = abs.substringBefore('?').lowercase()
        // Label common HLS segment types so clients/Shaka don't treat TS as fMP4.
        val mime = when {
            pathOnly.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            pathOnly.endsWith(".ts") || pathOnly.endsWith(".mts") || pathOnly.endsWith(".m2ts") ->
                "video/mp2t"
            pathOnly.endsWith(".m4s") || pathOnly.endsWith(".cmfv") || pathOnly.endsWith(".cmfa") ||
                pathOnly.endsWith(".mp4") -> "video/mp4"
            pathOnly.endsWith(".aac") -> "audio/aac"
            pathOnly.endsWith(".vtt") || pathOnly.endsWith(".webvtt") -> "text/vtt"
            pathOnly.endsWith(".key") -> "application/octet-stream"
            else -> null
        }
        // headers already filtered (inherited from the parent playlist registration)
        return register(Entry.Remote(abs, headers, mime), guessExt(abs, mime))
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) {
            ref
        } else {
            runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
        }

    private fun parseRange(range: String?, total: Long): Pair<Long, Long>? {
        if (range == null || total <= 0) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").find(range) ?: return null
        val s = m.groupValues[1]
        val e = m.groupValues[2]
        if (s.isEmpty()) return null
        val start = s.toLong()
        val end = if (e.isNotEmpty()) e.toLong().coerceAtMost(total - 1) else total - 1
        if (start > end) return null
        return start to end
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun guessExt(url: String, mime: String?): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "")
        // Only treat a short last path segment as a real extension (avoid "segment-12345" → ".segment-12345").
        if (fromUrl.length in 2..5 && fromUrl.all { it.isLetterOrDigit() }) {
            return ".$fromUrl"
        }
        return extForMime(mime)
    }

    private fun extForMime(mime: String?): String = when {
        mime?.contains("mpegurl", true) == true -> ".m3u8"
        mime?.contains("mp2t", true) == true -> ".ts"
        mime?.contains("webm", true) == true -> ".webm"
        mime?.contains("matroska", true) == true -> ".mkv"
        mime?.contains("quicktime", true) == true -> ".mov"
        // Prefer no fake extension over ".mp4" for unknown live segments (often MPEG-TS).
        mime?.contains("mp4", true) == true -> ".mp4"
        else -> ""
    }

    private fun lanIp(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                // Never advertise a VPN tunnel address: WireGuard/tun interfaces carry
                // 10.x addresses that pass isSiteLocalAddress, but the TV can't route
                // to them — the stream would stall or never start. Interface enumeration
                // order is arbitrary, so without this filter a running VPN app can win
                // even when this app is split-tunnel excluded (exclusion changes routing,
                // not interface visibility).
                .filterNot { nif ->
                    val n = nif.name.orEmpty()
                    n.startsWith("tun") || n.startsWith("wg") ||
                        n.startsWith("ppp") || n.startsWith("ipsec")
                }
                // Prefer Wi-Fi over cellular/other interfaces.
                .sortedByDescending { it.name.orEmpty().startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"

    companion object {
        private const val TAG = "LocalProxyServer"
        /** Live HLS registers one entry per segment URL; keep a hard cap. */
        private const val MAX_ENTRIES = 2_000
        /**
         * Max bytes to load into heap for playlist rewrite. Live segments are multi‑MB
         * and must never go through body.bytes() — that OOM-killed the app at 256MB.
         */
        private const val MAX_PLAYLIST_BUFFER_BYTES = 512 * 1024
        /** How long a previously good live media playlist may be re-served after a blip. */
        private const val STALE_PLAYLIST_MAX_AGE_MS = 20_000L
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Sum a VOD media playlist's segment durations (#EXTINF:<seconds>[,title]) → ms. */
        fun sumExtInf(body: String): Long {
            var totalSec = 0.0
            body.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("#EXTINF:")) {
                    line.substring(8).substringBefore(',').trim().toDoubleOrNull()?.let { totalSec += it }
                }
            }
            return (totalSec * 1000).toLong()
        }
    }
}
