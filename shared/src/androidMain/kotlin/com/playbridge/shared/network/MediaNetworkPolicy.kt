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
                        (first == 192 && second == 168) -> AddressClass.PRIVATE_LAN
                    first == 0 || first >= 224 ||
                        (first == 100 && second in 64..127) ||
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
