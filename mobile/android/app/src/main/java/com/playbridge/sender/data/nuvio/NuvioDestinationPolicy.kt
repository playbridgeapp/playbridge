package com.playbridge.sender.data.nuvio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Fail-closed destination and path checks for native plugin install and runtime
 * fetches. Legitimate LAN casting uses other clients and is not affected.
 */
internal object NuvioDestinationPolicy {
    private val SCRAPER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val SCRIPT_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private const val MAX_SCRIPT_SEGMENTS = 4
    private val HOSTNAME = Regex("^[a-z0-9.-]+$")
    private val BLOCKED_SUFFIXES = listOf(
        ".local",
        ".localhost",
        ".localdomain",
        ".internal",
        ".home.arpa",
        ".lan",
        ".intranet",
        ".corp",
        ".home",
        ".localdomain",
    )
    private val BLOCKED_NAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "metadata.google.internal",
        "metadata.google.com",
        "instance-data",
        "metadata",
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun safeScraperId(id: String): String? {
        if (!SCRAPER_ID.matches(id)) return null
        if (id == "." || id == ".." || id.contains("..")) return null
        return id
    }

    /**
     * Relative install path only. Nested names such as `providers/castle.js` are
     * allowed. On-disk cache names stay `scraperId.js`; this does not accept them
     * as storage paths.
     */
    fun safeScriptFilename(name: String): String? {
        if (name.length !in 1..256) return null
        if (name.any { it == '\\' || it == '%' || it == '?' || it == '#' || it == '\u0000' || it.isISOControl() }) return null
        if (name.contains("://") || name.startsWith("/") || name.contains("//")) return null
        val segments = name.split('/')
        if (segments.size !in 1..MAX_SCRIPT_SEGMENTS) return null
        if (segments.any { segment ->
            segment.isEmpty() || segment == "." || segment == ".." || segment.contains("..") ||
                !SCRIPT_SEGMENT.matches(segment)
        }) {
            return null
        }
        if (!segments.last().endsWith(".js")) return null
        return name
    }

    fun repoDirectoryName(repoUrl: String): String = sha256Hex(repoUrl)

    fun legacyDirectoryName(repoUrl: String): String = repoUrl.hashCode().toUInt().toString(16)

    fun normalizeHost(host: String): String {
        val trimmed = host.trim().trimEnd('.').lowercase()
        if (trimmed.isEmpty()) return ""
        return try {
            IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: Exception) {
            trimmed
        }
    }

    fun isRecordableHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.length !in 1..253) return false
        if (normalized.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' || it.isWhitespace() }) {
            return false
        }
        return !isBlockedHostname(normalized)
    }

    fun isBlockedHostname(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty() || normalized.length > 253) return true
        if (normalized in BLOCKED_NAMES) return true
        if (BLOCKED_SUFFIXES.any { normalized.endsWith(it) }) return true
        if (normalized.contains('%') || normalized.contains("..") || normalized.startsWith(".") || normalized.startsWith("-")) {
            return true
        }
        if (normalized.matches(Regex("\\d{1,10}"))) return true
        if (isIpv4Shape(normalized)) {
            val address = parseStrictIpv4(normalized) ?: return true
            return isBlockedAddress(address)
        }
        if (isIpv6Literal(normalized)) {
            val address = literalAddress(normalized) ?: return true
            return isBlockedAddress(address)
        }
        if (!HOSTNAME.matches(normalized)) return true
        if (normalized.endsWith(".") || normalized.endsWith("-")) return true
        return false
    }

    fun isBlockedAddress(address: InetAddress): Boolean {
        val bytes = address.address ?: return true
        return when (bytes.size) {
            4 -> isBlockedIpv4(bytes)
            16 -> isBlockedIpv6(bytes)
            else -> true
        }
    }

    fun literalAddress(host: String): InetAddress? {
        val normalized = normalizeHost(host)
        if (isIpv4Shape(normalized)) return parseStrictIpv4(normalized)
        if (isIpv6Literal(normalized)) return parseForcedIpv6(normalized)
        return null
    }

    /**
     * Keep mapped and compatible literals as 16-byte Inet6Address. JDK
     * getByName/getByAddress otherwise collapse ::ffff:8.8.8.8 into public IPv4.
     */
    private fun parseForcedIpv6(host: String): InetAddress? {
        return try {
            val parsed = InetAddress.getByName(host)
            val bytes = when (val raw = parsed.address) {
                null -> return null
                else -> when (raw.size) {
                    16 -> raw
                    4 -> ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte()) + raw
                    else -> return null
                }
            }
            Inet6Address.getByAddress(null, bytes, -1)
        } catch (_: Exception) {
            null
        }
    }

    fun parseInstallUrl(raw: String, allowCleartext: Boolean): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.length !in 1..2048) return null
        if (trimmed.any { it.isWhitespace() || it == '\\' }) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val https = url.scheme == "https"
        val cleartext = allowCleartext && url.scheme == "http"
        if (!https && !cleartext) return null
        if (url.host.contains("..") || isBlockedHostname(url.host)) return null
        if (url.encodedPath.contains("..")) return null
        return url
    }

    fun isBlockedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val a = bytes.map { it.toInt() and 0xff }
        if (a[0] == 0 || a[0] == 10 || a[0] == 127) return true
        if (a[0] == 100 && a[1] in 64..127) return true
        if (a[0] == 169 && a[1] == 254) return true
        if (a[0] == 172 && a[1] in 16..31) return true
        if (a[0] == 192 && a[1] == 168) return true
        if (a[0] == 192 && a[1] == 0 && a[2] == 0) return true
        if (a[0] == 192 && a[1] == 0 && a[2] == 2) return true
        if (a[0] == 192 && a[1] == 88 && a[2] == 99) return true
        if (a[0] == 198 && (a[1] == 18 || a[1] == 19)) return true
        if (a[0] == 198 && a[1] == 51 && a[2] == 100) return true
        if (a[0] == 203 && a[1] == 0 && a[2] == 113) return true
        if (a[0] >= 224) return true
        return false
    }

    /**
     * Allow only globally routable 2000::/3, excluding documentation, Teredo,
     * benchmarking, ORCHID, 6to4, and 6bone. Everything else (mapped IPv4,
     * NAT64, unique-local, link-local, ::/96) fails closed.
     */
    private fun isBlockedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return true
        val first = bytes[0].toInt() and 0xff
        if (first !in 0x20..0x3f) return true
        if (first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()) return true
        if (first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) return true
        if (first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0x02.toByte() &&
            bytes[4] == 0.toByte() && bytes[5] == 0.toByte()
        ) {
            return true
        }
        if (first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0.toByte() &&
            ((bytes[3].toInt() and 0xf0) == 0x10 || (bytes[3].toInt() and 0xf0) == 0x20)
        ) {
            return true
        }
        if (first == 0x20 && bytes[1] == 0x02.toByte()) return true
        if (first == 0x3f && bytes[1] == 0xfe.toByte()) return true
        return false
    }

    private fun isIpv4Shape(host: String): Boolean = host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))

    private fun isIpv6Literal(host: String): Boolean {
        if (host.count { it == ':' } < 2) return false
        return host.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
    }

    private fun parseStrictIpv4(host: String): Inet4Address? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith('0')) }) return null
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(nums.map { it.toByte() }.toByteArray()) as? Inet4Address
    }
}
