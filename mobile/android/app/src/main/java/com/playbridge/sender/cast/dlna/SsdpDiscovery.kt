package com.playbridge.sender.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Minimal SSDP (UPnP discovery) client.
 *
 * Sends multicast M-SEARCH requests and collects the unicast replies, returning
 * the distinct device-description LOCATION URLs. Runs alongside the native mDNS
 * discovery (`connection/NsdHelper`).
 */
class SsdpDiscovery(private val context: Context) {

    data class Hit(
        val location: String,
        val st: String?,
        val usn: String?,
        val server: String?,
    )

    /**
     * Broadcast M-SEARCH for each of [searchTargets] and listen [timeoutMs] for
     * replies. Blocking socket I/O — runs on [Dispatchers.IO].
     */
    suspend fun search(
        searchTargets: List<String> = DEFAULT_TARGETS,
        timeoutMs: Long = 3000L,
    ): List<Hit> = withContext(Dispatchers.IO) {
        // Android silently drops inbound multicast/UDP unless we hold this lock.
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("playbridge-ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }
        val socket = DatagramSocket().apply { soTimeout = 800 }
        val hits = LinkedHashMap<String, Hit>() // dedup by LOCATION
        try {
            val group = InetAddress.getByName(MCAST_ADDR)
            // UDP is lossy and some devices ignore the first probe; send each twice.
            repeat(2) {
                for (st in searchTargets) {
                    val bytes = buildMSearch(st).toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(group, MCAST_PORT)))
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length)
                val location = header(text, "LOCATION") ?: continue
                if (!hits.containsKey(location)) {
                    val hit = Hit(
                        location = location,
                        st = header(text, "ST"),
                        usn = header(text, "USN"),
                        server = header(text, "SERVER"),
                    )
                    hits[location] = hit
                    Log.d(TAG, "Hit from ${packet.address.hostAddress}: $hit")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP search failed", e)
        } finally {
            socket.close()
            if (lock.isHeld) lock.release()
        }
        hits.values.toList()
    }

    private fun buildMSearch(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MCAST_ADDR:$MCAST_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            "\r\n"

    /** Case-insensitive lookup of an HTTP-style header line in an SSDP reply. */
    private fun header(response: String, name: String): String? {
        val prefix = "$name:"
        return response.lineSequence()
            .firstOrNull { it.length >= prefix.length && it.take(prefix.length).equals(prefix, ignoreCase = true) }
            ?.substring(prefix.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val MCAST_ADDR = "239.255.255.250"
        private const val MCAST_PORT = 1900

        /** MediaRenderer device + AVTransport service — covers most renderers. */
        val DEFAULT_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
        )
    }
}
