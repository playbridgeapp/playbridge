package com.playbridge.sender.cast.dlna

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Local HTTP proxy that lets a DLNA renderer fetch media the phone has the rights
 * or headers for. Two source kinds:
 *  - [Entry.Remote]: a web stream — re-requested upstream with the captured headers
 *    (Referer/Cookie/UA), redirects followed, `Range` forwarded; `.m3u8` playlists
 *    are rewritten so every sub-request also carries headers ([rewritePlaylist]).
 *  - [Entry.Local]: a local file (`content://`/SAF) — served from ContentResolver
 *    with HTTP range support so the renderer can seek.
 *
 * Uses short opaque tokens (renderer-friendly URLs) backed by an **LRU-bounded
 * map**, so a live HLS stream that mints thousands of segment URLs can't grow
 * memory without limit — old, no-longer-requested segments are evicted.
 *
 * Raw ServerSocket (no NanoHTTPD/Ktor) — matches the hand-rolled SSDP/AVTransport
 * layer and adds no dependency.
 */
class LocalProxyServer(
    private val http: OkHttpClient,
    private val resolver: ContentResolver,
) {
    sealed interface Entry {
        data class Remote(val url: String, val headers: Map<String, String>, val mime: String?) : Entry
        data class Local(val uri: Uri, val mime: String?) : Entry
    }

    // accessOrder=true + removeEldestEntry => LRU eviction; synchronized for the accept threads.
    private val entries: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > MAX_ENTRIES
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
    }

    /** Register a remote web stream; returns the proxy URL to hand the renderer. */
    fun publish(url: String, headers: Map<String, String>, mime: String?): String {
        isLiveStream = false // re-learned when an HLS media playlist is served
        return register(Entry.Remote(url, filterHeaders(headers), mime), guessExt(url, mime))
    }

    /** Register a local file (content:// / file Uri); returns the proxy URL. */
    fun publishLocal(uri: Uri, mime: String?): String {
        isLiveStream = false
        return register(Entry.Local(uri, mime), extForMime(mime))
    }

    /** Drop browser-context headers CDNs reject from a different origin (matches mediaHeaders). */
    private fun filterHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { k ->
            val lk = k.lowercase()
            !lk.startsWith("sec-fetch") && !lk.startsWith("sec-ch") &&
                lk != "host" && lk != "accept-encoding" && lk != "connection" && lk != "range"
        }

    private fun register(entry: Entry, ext: String): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        entries[token] = entry
        return "http://${lanIp()}:$port/$token$ext"
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
        val token = path.trimStart('/').substringBefore('.').substringBefore('?')
        when (val entry = entries[token]) {
            null -> writeStatus(out, 404, "Not Found")
            is Entry.Remote -> serveRemote(entry, method, range, out)
            is Entry.Local -> serveLocal(entry, method, range, out)
        }
    }

    private fun serveRemote(entry: Entry.Remote, method: String, range: String?, out: OutputStream) {
        val req = Request.Builder().url(entry.url).apply {
            entry.headers.forEach { (k, v) -> header(k, v) }
            range?.let { header("Range", it) }
        }.build()

        http.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val ctype = resp.header("Content-Type")
            val isPlaylist = entry.mime?.contains("mpegurl", true) == true ||
                ctype?.contains("mpegurl", true) == true ||
                finalUrl.substringBefore('?').endsWith(".m3u8", true)

            if (method != "HEAD" && isPlaylist) {
                Log.d(TAG, "playlist ${resp.code} ($ctype) <- $finalUrl")
                val rewritten = rewritePlaylist(resp.body?.string().orEmpty(), finalUrl, entry.headers)
                    .toByteArray()
                val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                sb.append("Content-Length: ${rewritten.size}\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                out.write(rewritten)
                out.flush()
            } else {
                val reason = resp.message.ifEmpty { if (resp.code == 206) "Partial Content" else "OK" }
                val mime = entry.mime ?: ctype ?: "video/mp4"
                val sb = StringBuilder("HTTP/1.1 ${resp.code} $reason\r\n")
                sb.append("Content-Type: $mime\r\n")
                resp.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                resp.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                if (method != "HEAD") {
                    resp.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
                }
                out.flush()
            }
        }
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
        // A media playlist (#EXTINF) without #EXT-X-ENDLIST is a live stream.
        if (body.contains("#EXTINF")) isLiveStream = !body.contains("#EXT-X-ENDLIST")
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
        val mime = if (abs.substringBefore('?').endsWith(".m3u8", true)) {
            "application/vnd.apple.mpegurl"
        } else {
            null
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
        if (fromUrl.length in 2..4) return ".$fromUrl"
        return extForMime(mime)
    }

    private fun extForMime(mime: String?): String = when {
        mime?.contains("mpegurl", true) == true -> ".m3u8"
        mime?.contains("webm", true) == true -> ".webm"
        mime?.contains("matroska", true) == true -> ".mkv"
        mime?.contains("quicktime", true) == true -> ".mov"
        else -> ".mp4"
    }

    private fun lanIp(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"

    companion object {
        private const val TAG = "LocalProxyServer"
        private const val MAX_ENTRIES = 10_000
    }
}
