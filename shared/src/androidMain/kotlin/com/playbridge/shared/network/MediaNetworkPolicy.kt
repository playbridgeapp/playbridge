package com.playbridge.shared.network

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Network and origin checks for untrusted media requests supplied by webpages. */
object MediaNetworkPolicy {
    fun isHttpUrl(value: String): Boolean = parseHttpUri(value) != null

    /**
     * Performs the DNS-free part of destination validation.
     *
     * Callers that open a connection must also validate the DNS answers (and,
     * where available, the connected peer) with [areAllowedAddresses]. Keeping
     * DNS out of this check avoids doing network I/O on Android's main thread.
     */
    fun isAllowedUrlSyntax(value: String, allowPrivateNetwork: Boolean): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host)) return false
        return allowPrivateNetwork || !isLanHostname(host)
    }

    fun sameOrigin(first: String, second: String): Boolean {
        val a = parseHttpUri(first) ?: return false
        val b = parseHttpUri(second) ?: return false
        return normalizedOrigin(a) == normalizedOrigin(b)
    }

    /** Resolves immediately before use so DNS changes cannot bypass the page-cast grant. */
    fun isAllowedDestination(value: String, allowPrivateNetwork: Boolean): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host) || (!allowPrivateNetwork && isLanHostname(host))) return false
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return false
        return areAllowedAddresses(host, addresses, allowPrivateNetwork)
    }

    /** Validates the exact DNS answer an HTTP client will use, closing DNS-rebinding gaps. */
    fun areAllowedAddresses(
        host: String,
        addresses: List<InetAddress>,
        allowPrivateNetwork: Boolean,
    ): Boolean {
        if (isLoopbackHostname(host) || (!allowPrivateNetwork && isLanHostname(host))) return false
        return addresses.isNotEmpty() && addresses.all { isAllowedAddress(it, allowPrivateNetwork) }
    }

    fun targetsPrivateNetwork(value: String): Boolean {
        val uri = parseHttpUri(value) ?: return false
        val host = uri.host ?: return false
        if (isLoopbackHostname(host) || isLanHostname(host)) return true
        return runCatching {
            InetAddress.getAllByName(host).any { classifyAddress(it) != AddressClass.PUBLIC }
        }.getOrDefault(false)
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

    private fun isAllowedAddress(address: InetAddress, allowPrivateNetwork: Boolean): Boolean =
        when (classifyAddress(address)) {
            AddressClass.PUBLIC -> true
            AddressClass.PRIVATE_LAN -> allowPrivateNetwork
            AddressClass.FORBIDDEN -> false
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
