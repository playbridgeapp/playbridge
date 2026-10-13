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

/** Receiver ids establish credential ownership. MAC only relocates an already-associated TV. */
internal object AtvDeviceMatcher {
    fun match(receiver: AtvReceiverTarget, devices: List<AtvDiscoveredDevice>, records: List<AtvTvRecord>): AtvMatch {
        val receiverAddresses = receiver.addresses.map(::normalizeIp).filter { it.isNotEmpty() }.toSet()
        val byIp = devices.firstOrNull { device ->
            (device.addresses + device.host).map(::normalizeIp).any { it.isNotEmpty() && it in receiverAddresses }
        }
        val associated = records.filter { receiver.endpoint.toString() in it.receiverIds }
        // An ambiguous legacy association must be resolved by verified pairing, not discovery.
        if (associated.size > 1) return AtvMatch(null, null, true, null)
        val record = associated.singleOrNull()
        val mac = normalizeMac(record?.mac)
        val relocated = if (mac == null) null else devices.firstOrNull { normalizeMac(it.bluetoothMac) == mac }
        val ipConflicts = record != null && mac != null && byIp?.bluetoothMac != null &&
            normalizeMac(byIp.bluetoothMac) != mac
        val device = if (record == null) byIp else relocated ?: byIp.takeUnless { ipConflicts }
        if (ipConflicts && relocated == null) return AtvMatch(record, null, true, null)
        val available = record != null || receiver.androidTv || byIp != null
        val host = device?.host?.takeIf { it.isNotBlank() }
            ?: receiver.addresses.firstOrNull { it.isNotBlank() }
            ?: record?.lastIp?.takeIf { it.isNotBlank() }
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
