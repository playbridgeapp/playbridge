package com.playbridge.sender.history

import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads the expiry instant a signed media link carries about itself. Pure: no network, no
 * Android types, nothing logged. Returns epoch milliseconds, or null when the link carries no
 * recognisable (and plausible) expiry. When several forms are present the earliest wins.
 *
 * Recognised forms (placeholders only):
 *  - Plain epoch parameter: `?expires=1700000000`, `?expire=`, `?expiry=`, `?expiration=`,
 *    `?exp=`, `?e=`, `?Expires=` (CloudFront signed URL), `?validto=`, `?valid_until=`,
 *    `?expires_at=`. Seconds or milliseconds. Names are case-insensitive.
 *  - AWS SigV4 presigned: `?X-Amz-Date=20231115T120000Z&X-Amz-Expires=3600` (date + seconds).
 *  - Google Cloud Storage V4: `?X-Goog-Date=20231115T120000Z&X-Goog-Expires=3600`.
 *  - Azure SAS: `?sv=..&se=2023-11-15T12:00:00Z&sig=..` (`se` ISO-8601, only with `sig`).
 *  - Akamai token authentication: `?hdnts=st=1700000000~exp=1700003600~acl=/<path>~hmac=..`, also
 *    `hdnea`, `hdntl`, `token`, `__token__`, and the documented path form
 *    `/hdnts=exp=1700003600~acl=/<path>~hmac=../master.m3u8` (HLS/DASH). Inside a token `exp=` is
 *    the epoch-seconds expiry.
 *  - JWT in a query value or path segment (`eyJ...<payload>.<sig>`): payload claim `exp`
 *    (seconds). Signature is not verified; the token is never stored or logged.
 *  - `name=value` path segments (e.g. `/expires=1700000000/`) use the plain-epoch names.
 *
 * Not recognised: relative `ttl`/`age` parameters (no issue time), CloudFront custom-policy
 * (base64 `Policy=`), signed cookies, opaque tokens, `Expires` HTTP headers.
 *
 * Plausibility: an epoch is accepted only between 2015-01-01 and `savedAtMs` + 10 years, as
 * seconds or milliseconds. Anything else (ports, ids, short counters named `e`) yields null.
 */
fun linkExpiresAtMs(url: String, savedAtMs: Long): Long? {
    val candidates = ArrayList<Long>(2)
    val noFragment = url.substringBefore('#')
    val query = noFragment.substringAfter('?', "")
    val beforeQuery = noFragment.substringBefore('?')
    val afterScheme = beforeQuery.substringAfter("://", beforeQuery)
    val path = afterScheme.substringAfter('/', "")

    val params = LinkedHashMap<String, MutableList<String>>()
    fun add(name: String, value: String) {
        params.getOrPut(name.lowercase()) { ArrayList(1) }.add(value)
    }
    for (pair in query.split('&', ';')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        add(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)))
    }
    val segments = path.split('/').map(::decode).filter { it.isNotEmpty() }
    for (segment in segments) {
        val eq = segment.indexOf('=')
        if (eq in 1..32) add(segment.substring(0, eq), segment.substring(eq + 1))
    }

    fun take(ms: Long?) { if (ms != null) candidates.add(ms) }

    for (name in EPOCH_NAMES) params[name]?.forEach { take(plausibleEpochMs(it, savedAtMs)) }

    take(signedDuration(params, "x-amz-date", "x-amz-expires", savedAtMs))
    take(signedDuration(params, "x-goog-date", "x-goog-expires", savedAtMs))

    if (params.containsKey("sig")) {
        params["se"]?.forEach { take(parseIsoMs(it)?.takeIf { ms -> plausibleMs(ms, savedAtMs) }) }
    }

    for (name in TOKEN_NAMES) params[name]?.forEach { take(akamaiExp(it, savedAtMs)) }

    val jwtSources = params.values.flatten() + segments
    for (value in jwtSources) take(jwtExpMs(value, savedAtMs))

    return candidates.minOrNull()
}

private val EPOCH_NAMES = listOf(
    "expires", "expire", "expiry", "expiration", "expires_at", "exp", "e", "validto", "valid_until",
)
private val TOKEN_NAMES = listOf("hdnts", "hdnea", "hdntl", "token", "__token__")

private const val MIN_EPOCH_MS = 1_420_070_400_000L // 2015-01-01T00:00:00Z
private const val TEN_YEARS_MS = 10L * 366 * 24 * 3600 * 1000
private const val MAX_SIGNED_SECONDS = 7L * 24 * 3600 // SigV4 / GCS V4 ceiling
private const val MAX_JWT_CHARS = 8192

private fun decode(s: String): String =
    runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

private fun plausibleMs(ms: Long, savedAtMs: Long): Boolean =
    ms >= MIN_EPOCH_MS && ms <= savedAtMs + TEN_YEARS_MS

/** Accepts epoch seconds or milliseconds; the two ranges cannot overlap inside the window. */
internal fun plausibleEpochMs(raw: String, savedAtMs: Long): Long? {
    val text = raw.trim()
    if (text.isEmpty() || text.length > 16 || !text.all { it in '0'..'9' || it == '.' }) return null
    val number = text.substringBefore('.').toLongOrNull() ?: return null
    if (text.count { it == '.' } > 1) return null
    val asMs = if (number in 1_000_000_000L..99_999_999_999L) number * 1000 else number
    return asMs.takeIf { plausibleMs(it, savedAtMs) }
}

private fun signedDuration(
    params: Map<String, List<String>>, dateName: String, lengthName: String, savedAtMs: Long,
): Long? {
    val date = params[dateName]?.firstOrNull() ?: return null
    val seconds = params[lengthName]?.firstOrNull()?.trim()?.toLongOrNull() ?: return null
    if (seconds !in 1..MAX_SIGNED_SECONDS) return null
    val start = runCatching {
        LocalDateTime.parse(date.trim(), BASIC_ISO).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull() ?: return null
    return (start + seconds * 1000).takeIf { plausibleMs(it, savedAtMs) }
}

private val BASIC_ISO = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

private fun parseIsoMs(raw: String): Long? {
    val text = raw.trim()
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/** `st=..~exp=1700003600~acl=..~hmac=..`; `exp` is epoch seconds. Delimiters: `~` or `&`. */
private fun akamaiExp(token: String, savedAtMs: Long): Long? {
    for (part in token.split('~', '&')) {
        if (part.startsWith("exp=", ignoreCase = true)) {
            return plausibleEpochMs(part.substring(4), savedAtMs)
        }
    }
    return null
}

private fun jwtExpMs(value: String, savedAtMs: Long): Long? {
    if (value.length > MAX_JWT_CHARS || !value.startsWith("eyJ")) return null
    val parts = value.split('.')
    if (parts.size != 3 || parts[1].isEmpty()) return null
    return runCatching {
        val json = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
        val exp = (Json.parseToJsonElement(json) as? JsonObject)?.get("exp") as? JsonPrimitive
        val seconds = exp?.doubleOrNull ?: return@runCatching null
        if (seconds.isNaN() || seconds < 0 || seconds > 1e12) return@runCatching null
        plausibleEpochMs(seconds.toLong().toString(), savedAtMs)
    }.getOrNull()
}
