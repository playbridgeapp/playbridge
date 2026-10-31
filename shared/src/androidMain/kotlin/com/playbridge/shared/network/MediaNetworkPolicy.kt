package com.playbridge.shared.network

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Network and origin checks for untrusted media requests supplied by webpages. */
object MediaNetworkPolicy {
    const val MAX_PRIVATE_ORIGINS = 16

    fun isHttpUrl(value: String): Boolean = parseHttpUri(value) != null

    /** Returns an exact, normalized HTTP origin (scheme, host and effective port). */
    fun normalizeOrigin(value: String): String? = parseHttpUri(value)?.let(::normalizedOrigin)

    /** Validates and normalizes a bounded sender-approved private-origin grant. */
    fun normalizePrivateOrigins(values: Collection<String>): Set<String>? {
        if (values.size > MAX_PRIVATE_ORIGINS) return null
        val normalized = values.mapTo(linkedSetOf()) { value ->
            val uri = parseHttpUri(value) ?: return null
            if (uri.path !in listOf("", "/") || uri.query != null || uri.fragment != null) return null
            val host = uri.host ?: return null
            if (isLoopbackHostname(host)) return null
            normalizedOrigin(uri)
        }
        return normalized.takeIf { it.size <= MAX_PRIVATE_ORIGINS }
    }

    /**
     * Performs the DNS-free part of destination validation.
     *
     * Callers that open a connection must also validate the DNS answers (and,
     * where available, the connected peer) with [areAllowedAddresses].
     */
    fun isAllowedUrlSyntax(value: String, allowedPrivateOrigins: Collection<String>): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host)) return false
        val grants = normalizePrivateOrigins(allowedPrivateOrigins) ?: return false
        return !isLanHostname(host) || normalizedOrigin(uri) in grants
    }

    fun sameOrigin(first: String, second: String): Boolean {
        val a = parseHttpUri(first) ?: return false
        val b = parseHttpUri(second) ?: return false
        return normalizedOrigin(a) == normalizedOrigin(b)
    }

    /**
     * Normalizes an embedded local stream-proxy origin (`http://127.0.0.1:<port>`).
     * Rejects non-HTTP schemes, non-`127.0.0.1` hosts, implicit/invalid ports, paths, and queries.
     */
    fun normalizeTrustedLocalProxyOrigin(origin: String?): String? {
        val raw = origin?.takeIf(String::isNotBlank) ?: return null
        val uri = parseHttpUri(raw) ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) ||
            uri.host != "127.0.0.1" ||
            uri.port !in 1..65535 ||
            uri.path !in listOf("", "/") ||
            uri.query != null ||
            uri.fragment != null
        ) {
            return null
        }
        return "http://127.0.0.1:${uri.port}"
    }

    /**
     * Returns true only for stream/segment URLs served by the trusted local proxy instance
     * (`http://127.0.0.1:<port>/s/...` or `/proxy/...`), never management routes or other ports.
     */
    fun isTrustedLocalProxyUrl(value: String, trustedLocalProxyOrigin: String?): Boolean {
        val trusted = normalizeTrustedLocalProxyOrigin(trustedLocalProxyOrigin) ?: return false
        val uri = parseHttpUri(value) ?: return false
        if (normalizedOrigin(uri) != trusted) return false
        val path = uri.path ?: return false
        return path.startsWith("/s/") || path.startsWith("/proxy/")
    }

    /**
     * DNS-free validation for registering a page-supplied URL with the local proxy.
     *
     * Rejects loopback/link-local/forbidden IP literals, non-canonical numeric IPv4 forms,
     * ungranted RFC1918/CGNAT/ULA IP literals, and forbidden IP literals inside [allowedPrivateOrigins].
     */
    fun isAllowedRegistrationUrlSyntax(
        value: String,
        allowedPrivateOrigins: Collection<String>,
    ): Boolean {
        if (!isAllowedUrlSyntax(value, allowedPrivateOrigins)) return false
        for (origin in allowedPrivateOrigins) {
            val originHost = parseHttpUri(origin)?.host ?: return false
            if (isNonCanonicalNumericHost(originHost)) return false
            val literal = parseIpLiteralWithoutDns(originHost)
            if (literal != null && classifyAddress(literal) == AddressClass.FORBIDDEN) {
                return false
            }
        }
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isNonCanonicalNumericHost(host)) return false
        val literal = parseIpLiteralWithoutDns(host) ?: return true
        return areAllowedAddresses(value, listOf(literal), allowedPrivateOrigins)
    }

    /**
     * Syntax check for second-layer player/subtitle loaders that permits either a URL owned by
     * [trustedLocalProxyOrigin] or a valid page-cast destination under [allowedPrivateOrigins].
     */
    fun isAllowedUrlSyntaxOrTrustedProxy(
        value: String,
        allowedPrivateOrigins: Collection<String>,
        trustedLocalProxyOrigin: String?,
    ): Boolean = isTrustedLocalProxyUrl(value, trustedLocalProxyOrigin) ||
        isAllowedRegistrationUrlSyntax(value, allowedPrivateOrigins)

    /**
     * DNS check for second-layer player/subtitle loaders. Permits `127.0.0.1` resolution only when
     * a valid [trustedLocalProxyOrigin] is configured; the connection interceptor still enforces
     * exact port and `/s/` or `/proxy/` path before any request bytes are sent.
     */
    fun areAllowedAddressesForHostOrTrustedProxy(
        host: String,
        addresses: List<InetAddress>,
        allowedPrivateOrigins: Collection<String>,
        trustedLocalProxyOrigin: String?,
    ): Boolean {
        if (normalizeTrustedLocalProxyOrigin(trustedLocalProxyOrigin) != null && host == "127.0.0.1") {
            return addresses.isNotEmpty() && addresses.all {
                it.isLoopbackAddress && it.hostAddress == "127.0.0.1"
            }
        }
        return areAllowedAddressesForHost(host, addresses, allowedPrivateOrigins)
    }

    /**
     * Connected-socket check for second-layer player/subtitle loaders.
     */
    fun areAllowedConnectionOrTrustedProxy(
        targetUrl: String,
        peerAddress: InetAddress?,
        allowedPrivateOrigins: Collection<String>,
        trustedLocalProxyOrigin: String?,
    ): Boolean {
        val peer = peerAddress ?: return false
        if (isTrustedLocalProxyUrl(targetUrl, trustedLocalProxyOrigin)) {
            return peer.isLoopbackAddress && peer.hostAddress == "127.0.0.1"
        }
        return isAllowedRegistrationUrlSyntax(targetUrl, allowedPrivateOrigins) &&
            areAllowedAddresses(targetUrl, listOf(peer), allowedPrivateOrigins)
    }

    /** Resolves immediately before use so DNS changes cannot bypass the page-cast grant. */
    fun isAllowedDestination(value: String, allowedPrivateOrigins: Collection<String>): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return false
        return areAllowedAddresses(value, addresses, allowedPrivateOrigins)
    }

    /**
     * DNS hooks receive only a hostname. This permits private answers when at least one approved
     * origin names that host; the connected-request check below still enforces scheme and port.
     */
    fun areAllowedAddressesForHost(
        host: String,
        addresses: List<InetAddress>,
        allowedPrivateOrigins: Collection<String>,
    ): Boolean {
        if (isLoopbackHostname(host)) return false
        val grants = normalizePrivateOrigins(allowedPrivateOrigins) ?: return false
        val privateHostApproved = grants.any { origin ->
            parseHttpUri(origin)?.host?.equals(host, ignoreCase = true) == true
        }
        if (isLanHostname(host) && !privateHostApproved) return false
        return addresses.isNotEmpty() && addresses.all {
            when (classifyAddress(it)) {
                AddressClass.PUBLIC -> true
                AddressClass.PRIVATE_LAN -> privateHostApproved
                AddressClass.FORBIDDEN -> false
            }
        }
    }

    /** Validates the exact DNS answer an HTTP client will use, closing DNS-rebinding gaps. */
    fun areAllowedAddresses(
        value: String,
        addresses: List<InetAddress>,
        allowedPrivateOrigins: Collection<String>,
    ): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host)) return false
        val grants = normalizePrivateOrigins(allowedPrivateOrigins) ?: return false
        val privateOriginApproved = normalizedOrigin(uri) in grants
        if (isLanHostname(host) && !privateOriginApproved) return false
        return addresses.isNotEmpty() && addresses.all {
            when (classifyAddress(it)) {
                AddressClass.PUBLIC -> true
                AddressClass.PRIVATE_LAN -> privateOriginApproved
                AddressClass.FORBIDDEN -> false
            }
        }
    }

    fun targetsPrivateNetwork(value: String): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host) || isLanHostname(host)) return true
        return runCatching {
            InetAddress.getAllByName(host).any { classifyAddress(it) != AddressClass.PUBLIC }
        }.getOrDefault(false)
    }

    /** Returns an exact origin only for grantable private-LAN destinations. */
    fun privateOrigin(value: String): String? {
        val uri = parseHttpUri(value) ?: return null
        val host = uri.host ?: return null
        if (isLoopbackHostname(host)) return null
        if (isLanHostname(host)) return normalizedOrigin(uri)
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return null
        if (addresses.isEmpty() || addresses.any { classifyAddress(it) == AddressClass.FORBIDDEN }) return null
        return normalizedOrigin(uri).takeIf {
            addresses.any { address -> classifyAddress(address) == AddressClass.PRIVATE_LAN }
        }
    }

    private fun parseHttpUri(value: String): URI? = runCatching {
        if (value.isBlank() || value.length > 8_192) return null
        val uri = URI(value)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) return null
        uri
    }.getOrNull()

    private fun normalizedOrigin(uri: URI): String {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = IDN.toASCII(uri.host).lowercase(Locale.ROOT)
        val effectivePort = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return "$scheme://$host:$effectivePort"
    }

    private fun isLoopbackHostname(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".localhost", ignoreCase = true)

    private fun isLanHostname(host: String): Boolean =
        isLoopbackHostname(host) || host.endsWith(".local", ignoreCase = true)

    private fun isNonCanonicalNumericHost(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) return false
        val looksNumericOrHex = host.all { it.isDigit() || it == '.' } ||
            host.startsWith("0x", ignoreCase = true) ||
            host.split('.').any { part -> part.startsWith("0x", ignoreCase = true) }
        if (!looksNumericOrHex) return false
        return parseCanonicalIpv4Literal(host) == null
    }

    private fun parseCanonicalIpv4Literal(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part.startsWith('0')) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[i] = octet.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpLiteralWithoutDns(host: String): InetAddress? {
        if (host.startsWith("[") && host.endsWith("]")) {
            // Bracketed IPv6 literals are parsed by InetAddress without invoking DNS.
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        return parseCanonicalIpv4Literal(host)
    }

    private fun classifyAddress(address: InetAddress): AddressClass {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isMulticastAddress
        ) return AddressClass.FORBIDDEN
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                val third = bytes[2].toInt() and 0xff
                when {
                    first == 10 ||
                        (first == 172 && second in 16..31) ||
                        (first == 192 && second == 168) ||
                        // CGNAT / Tailscale 100.64.0.0/10: same as Rust classify_address.
                        (first == 100 && second in 64..127) -> AddressClass.PRIVATE_LAN
                    first == 0 || first >= 224 ||
                        (first == 192 && second == 0) ||
                        (first == 198 && second in 18..19) ||
                        (first == 198 && second == 51 && third == 100) ||
                        (first == 203 && second == 0 && third == 113) -> AddressClass.FORBIDDEN
                    else -> AddressClass.PUBLIC
                }
            }
            is Inet6Address -> when {
                (bytes[0].toInt() and 0xfe) == 0xfc -> AddressClass.PRIVATE_LAN
                address.isSiteLocalAddress || isDocumentationIpv6(bytes) -> AddressClass.FORBIDDEN
                else -> AddressClass.PUBLIC
            }
            else -> AddressClass.FORBIDDEN
        }
    }

    private fun isDocumentationIpv6(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()

    private enum class AddressClass { PUBLIC, PRIVATE_LAN, FORBIDDEN }
}
