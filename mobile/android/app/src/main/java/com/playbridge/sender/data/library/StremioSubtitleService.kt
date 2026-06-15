package com.playbridge.sender.data.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service to interact with Stremio subtitle addons.
 *
 * Always queries the default OpenSubtitles v3 addon as a baseline. If an
 * [AddonRepository] is provided, any installed addons that declare subtitle
 * support are queried in parallel and their results are merged in.
 * Duplicates are eliminated by (url, lang) pair.
 */
class StremioSubtitleService(
    private val addonRepository: AddonRepository? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "StremioSubtitleService"
        private const val DEFAULT_ADDON_URL = "https://opensubtitles-v3.strem.io/subtitles"

        private val LANG_ISO3 = mapOf(
            "en" to "eng", "es" to "spa", "fr" to "fre", "de" to "ger", "it" to "ita",
            "ja" to "jpn", "ko" to "kor", "zh" to "chi", "ru" to "rus", "pt" to "por",
            "ar" to "ara", "hi" to "hin", "nl" to "dut", "sv" to "swe", "tr" to "tur", "pl" to "pol"
        )
        private val LANG_NAMES = mapOf(
            "en" to "english", "es" to "spanish", "fr" to "french", "de" to "german", "it" to "italian",
            "ja" to "japanese", "ko" to "korean", "zh" to "chinese", "ru" to "russian", "pt" to "portuguese",
            "ar" to "arabic", "hi" to "hindi", "nl" to "dutch", "sv" to "swedish", "tr" to "turkish", "pl" to "polish"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Fetch subtitles for a movie.
     * @param imdbId The IMDB ID of the movie (e.g., tt1234567).
     * @return Merged, deduplicated list of subtitle streams from all sources.
     */
    suspend fun getSubtitlesForMovie(imdbId: String): List<StremioStream> {
        return coroutineScope {
            val defaultDeferred = async(Dispatchers.IO) {
                fetchSubtitles("$DEFAULT_ADDON_URL/movie/$imdbId.json")
            }
            val addonDeferred = async(Dispatchers.IO) {
                addonRepository?.resolveSubtitles("movie", imdbId) ?: emptyList()
            }
            mergeSubtitles(defaultDeferred.await(), addonDeferred.await())
        }
    }

    /**
     * Fetch subtitles for a TV show episode.
     * @param imdbId The IMDB ID of the TV show (e.g., tt1234567).
     * @param season The season number.
     * @param episode The episode number.
     * @return Merged, deduplicated list of subtitle streams from all sources.
     */
    suspend fun getSubtitlesForEpisode(imdbId: String, season: Int, episode: Int): List<StremioStream> {
        return coroutineScope {
            val defaultDeferred = async(Dispatchers.IO) {
                fetchSubtitles("$DEFAULT_ADDON_URL/series/$imdbId:$season:$episode.json")
            }
            val addonDeferred = async(Dispatchers.IO) {
                addonRepository?.resolveSubtitles("series", "$imdbId:$season:$episode") ?: emptyList()
            }
            mergeSubtitles(defaultDeferred.await(), addonDeferred.await())
        }
    }

    /**
     * Fetch ALL subtitles for a movie (season/episode null) or episode and return their URLs so
     * the user can choose on the TV. Each URL is suffixed with a `#<label>` fragment carrying a
     * human-readable language name — the fragment is never sent over HTTP (OkHttp strips it), so it
     * doesn't affect the download, but the TV uses it to label the track. Subtitles in
     * [preferredLang] are listed first. Deduplicated by URL, capped at [limit].
     */
    suspend fun getAllSubtitleUrls(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        preferredLang: String = "",
        videoRelease: String? = null,
        perLanguageLimit: Int = 5,
        limit: Int = 30
    ): List<String> {
        if (imdbId.isNullOrBlank()) return emptyList()
        return runCatching {
            val streams = if (season != null && episode != null)
                getSubtitlesForEpisode(imdbId, season, episode)
            else getSubtitlesForMovie(imdbId)
            val pref = preferredLang.lowercase()
            // Release-match profile of the actual video file (from the selected stream's
            // filename). Metadata-rich addons (e.g. AIOStreams) put the release name in the
            // subtitle `title`, so we can pick the sub whose release matches the video —
            // killing the BluRay-vs-WEB ~90s recap mismatch. Empty when no release/title.
            val videoTokens = videoRelease?.let { normReleaseTokens(it) }.orEmpty()

            data class Cand(
                val url: String, val label: String, val prefLang: Boolean,
                val score: Int, val dedupeKey: String, val lang: String
            )
            val ranked = streams
                .mapNotNull { s ->
                    val url = s.url ?: return@mapNotNull null
                    val subRelease = s.title ?: s.name
                    val score = if (videoTokens.isEmpty() || subRelease.isNullOrBlank()) 0
                                else releaseMatchScore(videoRelease!!, videoTokens, subRelease)
                    // Collapse the many re-uploads of the same release (per language) into one.
                    val releaseKey = subRelease?.let { normReleaseTokens(it).sorted().joinToString(".") }
                        ?.ifBlank { null } ?: url
                    // Enrich the label with a short source/quality tag so same-language options
                    // are distinguishable in the TV picker (e.g. "English · BLURAY 1080p").
                    val tag = subReleaseTag(subRelease)
                    val label = subtitleLabel(s) + (tag?.let { " · $it" } ?: "")
                    Cand(url, label, pref.isNotBlank() && matchesLang(s, pref), score, "${s.lang}|$releaseKey", s.lang ?: "")
                }
                // Preferred language first, then best release match — so distinctBy keeps the
                // best-scored copy of each release. sortedWith is stable.
                .sortedWith(compareByDescending<Cand> { it.prefLang }.thenByDescending { it.score })
                .distinctBy { it.dedupeKey }

            // Keep ALL languages so the TV can offer a language picker (NuvioTV-style), but cap
            // per language and overall so we don't flood it. Preferred language stays first.
            val perLang = HashMap<String, Int>()
            ranked
                .filter { c ->
                    val n = perLang.getOrDefault(c.lang, 0)
                    if (n >= perLanguageLimit) false else { perLang[c.lang] = n + 1; true }
                }
                .take(limit)
                .map { "${it.url}#${java.net.URLEncoder.encode(it.label, "UTF-8")}" }
        }.getOrDefault(emptyList())
    }

    /** A readable language label for a subtitle stream (English name when derivable). */
    private fun subtitleLabel(s: StremioStream): String {
        val code = s.lang?.lowercase()?.trim()
        val display = code?.let { c ->
            LANG_NAMES[c] ?: LANG_NAMES.entries.firstOrNull { LANG_ISO3[it.key] == c }?.value
        }?.replaceFirstChar { it.titlecase() }
        return display
            ?: s.name?.takeIf { it.isNotBlank() }
            ?: s.title?.takeIf { it.isNotBlank() }
            ?: code?.uppercase()
            ?: "Subtitle"
    }

    /** Match a subtitle's language against a preferred ISO code, tolerating 2-letter / 3-letter
     *  codes and the English language name appearing in lang/name/title. */
    private fun matchesLang(s: StremioStream, prefCode: String): Boolean {
        val p = prefCode.lowercase()
        if (p.isBlank()) return false
        val name = LANG_NAMES[p]
        val iso3 = LANG_ISO3[p]
        val hay = "${s.lang ?: ""} ${s.name ?: ""} ${s.title ?: ""}".lowercase()
        val tokens = hay.split(' ', '.', '-', '_', ',', '/', '(', ')', '[', ']').filter { it.isNotBlank() }
        val codeMatch = tokens.any { it == p || it == iso3 }
        val nameMatch = name != null && hay.contains(name)
        return codeMatch || nameMatch
    }

    /**
     * Merge two subtitle lists, preferring addon-sourced results on duplicate (url, lang) pairs.
     * Addon results are prepended so they appear first in the UI.
     */
    private fun mergeSubtitles(
        default: List<StremioStream>,
        addon: List<StremioStream>
    ): List<StremioStream> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<StremioStream>()

        // Addon results first — they take precedence on duplicates
        for (stream in addon) {
            val key = "${stream.url.orEmpty()}|${stream.name.orEmpty()}"
            if (seen.add(key)) merged.add(stream)
        }
        for (stream in default) {
            val key = "${stream.url.orEmpty()}|${stream.name.orEmpty()}"
            if (seen.add(key)) merged.add(stream)
        }

        Log.d(TAG, "Merged subtitles: ${addon.size} from addons + ${default.size} from default = ${merged.size} total")
        return merged
    }

    private suspend fun fetchSubtitles(url: String): List<StremioStream> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val subtitleResponse = json.decodeFromString<StremioStreamResponse>(body)
                    subtitleResponse.subtitles ?: subtitleResponse.streams ?: emptyList()
                } else {
                    Log.e(TAG, "Subtitle request failed: ${response.code} for $url")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtitle request error", e)
                emptyList()
            }
        }
    }

    // ==================== Release matching ====================
    // Score a subtitle's release name (`title`) against the video file's release name so the
    // best-matched subtitle sorts first. The release group is decisive (same group = same
    // timing); a BluRay-vs-WEB/HDTV source conflict is penalised because that's the usual
    // cause of a constant ~90s "Previously on" recap offset.

    private object Release {
        val BLURAY = setOf("bluray", "blu", "bdrip", "brrip", "bdremux", "bd", "brip", "remux")
        val WEB = setOf("web", "webdl", "webrip", "amzn", "nf", "dsnp", "hmax", "atvp", "hulu")
        val HDTV = setOf("hdtv", "pdtv", "hdtvrip")
        val DVD = setOf("dvdrip", "dvd", "dvdr")
        val GROUP_STOP = setOf(
            "dl", "web", "webdl", "webrip", "hdtv", "bluray", "bdrip", "brrip", "remux",
            "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "dd", "ddp", "aac", "dts",
            "ma", "hd", "atmos", "truehd", "264", "265", "720p", "1080p", "2160p", "480p",
            "mkv", "mp4", "uhd", "4k", "dv", "hdr", "hdr10", "10bit", "multi", "dual", "esub",
            "en", "eng", "nordic"
        )
        val EXT = Regex("\\.(mkv|mp4|avi|m2ts|ts|srt|vtt|ssa|ass)$", RegexOption.IGNORE_CASE)
        val SEP = Regex("[^a-z0-9]+")
    }

    private fun normReleaseTokens(s: String): Set<String> =
        s.lowercase().split(Release.SEP).filterTo(HashSet()) { it.isNotBlank() }

    private fun sourceClass(t: Set<String>): String? = when {
        t.any { it in Release.BLURAY } -> "bluray"
        t.any { it in Release.WEB } -> "web"
        t.any { it in Release.HDTV } -> "hdtv"
        t.any { it in Release.DVD } -> "dvd"
        else -> null
    }

    private fun resolutionClass(t: Set<String>): String? = when {
        "2160p" in t -> "2160p"
        "1080p" in t || "1080i" in t -> "1080p"
        "720p" in t -> "720p"
        "480p" in t -> "480p"
        "4k" in t || "uhd" in t -> "2160p"
        else -> null
    }

    private fun codecClass(t: Set<String>): String? = when {
        t.any { it in setOf("x265", "h265", "hevc", "265") } -> "hevc"
        t.any { it in setOf("x264", "h264", "avc", "264") } -> "avc"
        "xvid" in t -> "xvid"
        else -> null
    }

    /** The trailing release-group token (after the final `-`), or null if absent/ambiguous. */
    private fun releaseGroup(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val base = name.replace(Release.EXT, "")
        if ('-' !in base) return null
        val after = base.substringAfterLast('-').trim()
        val tok = after.split(Release.SEP).firstOrNull { it.isNotBlank() }?.lowercase() ?: return null
        return if (tok.length < 3 || tok in Release.GROUP_STOP) null else tok
    }

    private fun releaseMatchScore(videoRelease: String, videoTokens: Set<String>, subRelease: String): Int {
        val st = normReleaseTokens(subRelease)
        var score = 0
        val vSrc = sourceClass(videoTokens); val sSrc = sourceClass(st)
        if (vSrc != null && sSrc != null) score += if (vSrc == sSrc) 50 else -40
        val vRes = resolutionClass(videoTokens); val sRes = resolutionClass(st)
        if (vRes != null && sRes != null && vRes == sRes) score += 15
        val vCodec = codecClass(videoTokens); val sCodec = codecClass(st)
        if (vCodec != null && sCodec != null && vCodec == sCodec) score += 10
        val vGroup = releaseGroup(videoRelease); val sGroup = releaseGroup(subRelease)
        if (!vGroup.isNullOrBlank() && vGroup == sGroup) score += 60
        return score
    }

    /** A short, human source/quality tag for a subtitle's release (e.g. "BLURAY 1080p"), or null. */
    private fun subReleaseTag(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val t = normReleaseTokens(title)
        val parts = listOfNotNull(sourceClass(t)?.uppercase(), resolutionClass(t))
        return parts.joinToString(" ").ifBlank { null }
    }

    /** Last path segment of a URL, decoded, as a release-name hint for [getAllSubtitleUrls]. */
    fun filenameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val seg = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        if (seg.isBlank()) return null
        return runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
    }
}
