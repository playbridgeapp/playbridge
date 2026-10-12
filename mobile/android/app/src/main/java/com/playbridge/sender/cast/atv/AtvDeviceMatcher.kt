package com.playbridge.sender.cast.atv

import com.playbridge.sender.model.EndpointKey
import com.playbridge.sender.model.TvDevice

internal data class AtvReceiverTarget(
    val endpoint: EndpointKey,
    val name: String,
    val addresses: List<String>,
    val androidTv: Boolean,
    val googleCast: Boolean,
)

internal data class AtvMatch(
    val record: AtvTvRecord?,
    val device: AtvDiscoveredDevice?,
    val available: Boolean,
    val host: String?,
)

internal fun TvDevice.toAtvTarget(): AtvReceiverTarget = AtvReceiverTarget(
    endpoint = endpointKey,
    name = name,
    addresses = (addresses.ifEmpty { listOf(ip) }).filter { it.isNotBlank() },
    androidTv = players.any { it.equals("exo", ignoreCase = true) },
    googleCast = resolvedProtocol == com.playbridge.sender.model.CastProtocol.GOOGLE_CAST,
)

/** Matches `_androidtvremote2._tcp` to the current receiver by IP, then by stored id or `bt` MAC. */
internal object AtvDeviceMatcher {
    fun match(receiver: AtvReceiverTarget, devices: List<AtvDiscoveredDevice>, records: List<AtvTvRecord>): AtvMatch {
        val receiverAddresses = receiver.addresses.map(::normalizeIp).filter { it.isNotEmpty() }.toSet()
        val byIp = devices.firstOrNull { device ->
            (device.addresses + device.host).map(::normalizeIp).any { it.isNotEmpty() && it in receiverAddresses }
        }
        val byId = records.firstOrNull { receiver.endpoint.toString() in it.receiverIds }
        val byMac = records.firstOrNull { record ->
            val mac = normalizeMac(record.mac) ?: return@firstOrNull false
            devices.any { normalizeMac(it.bluetoothMac) == mac }
        }
        val byLastIp = if (devices.isEmpty()) {
            records.firstOrNull { record ->
                val ip = normalizeIp(record.lastIp)
                ip.isNotEmpty() && ip in receiverAddresses
            }
        } else {
            null
        }
        val record = byId ?: byMac ?: byLastIp
        val device = byIp ?: devices.firstOrNull { candidate ->
            val mac = normalizeMac(candidate.bluetoothMac)
            mac != null && mac == normalizeMac(record?.mac)
        }
        val available = record != null || receiver.androidTv || byIp != null
        val host = device?.host?.takeIf { it.isNotBlank() }
            ?: record?.lastIp?.takeIf { it.isNotBlank() }
            ?: receiver.addresses.firstOrNull { it.isNotBlank() }
        return AtvMatch(record = record, device = device, available = available, host = host)
    }

    fun normalizeIp(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().removePrefix("[").removeSuffix("]").lowercase()
    }

    fun normalizeMac(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.uppercase()
    }
}
