package com.playbridge.sender.cast

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist as Media3HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist as Media3HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser as Media3HlsPlaylistParser
import com.playbridge.sender.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Data class representing a detected video URL
 */
@Serializable
enum class MediaValidationState {
    PENDING,
    VERIFIED_PLAYABLE,
    FAILED,
}

@Serializable
enum class ThumbnailPreviewState {
    NOT_REQUESTED,
    READY,
    UNAVAILABLE,
}

@Serializable
data class DetectedVideo(
    val url: String,
    val tabId: Int = -1,
    val contentType: String? = null,
    val detectedBy: String = "unknown",
    val originUrl: String? = null,
    val headers: Map<String, String>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var fileSize: Long? = null,  // Will be fetched asynchronously
    var fileSizeChecked: Boolean = false,
    val originalMessage: String? = null,
    var qualities: List<VideoQuality> = emptyList(),
    var qualitiesChecked: Boolean = false,
    var hlsPlaylist: HlsPlaylist? = null,
    var subtitlePreview: String? = null,
    var subtitlePreviewChecked: Boolean = false,
    var isPlayable: Boolean? = null,
    @kotlinx.serialization.Transient
    val playlistPayload: List<playbridge.PlayPayload>? = null,
    val title: String? = null,
    /** Synthetic multivariant playlist text from the shared detection core. */
    val playlistBody: String? = null,
    /** Companion demuxed audio media playlist (same live session). */
    val audioUrl: String? = null,
    val hlsRole: String? = null,
    val isSyntheticMaster: Boolean = false,
    /** Detector-provided broad category; older messages are classified locally. */
    val mediaKind: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val lastSeen: Long = timestamp,
    /**
     * Media lifecycle this detection belongs to within its tab. Bumped when the page
     * performs a same-document (SPA) navigation, so cast-sheet ranking can prefer streams
     * from the view the user is currently on without discarding older detections.
     */
    val lifecycleIndex: Int = 0,
    var validationState: MediaValidationState = MediaValidationState.PENDING,
    var thumbnailState: ThumbnailPreviewState = ThumbnailPreviewState.NOT_REQUESTED,
    /**
     * Explicit stream route chosen by the cast sheet policy after packaging.
     * Prefs value of [com.playbridge.sender.cast.proxy.StreamRouteMode] or null.
     */
    val effectiveStreamRoute: String? = null,
    /** Policy reason code for logs/UI (never a raw authenticated URL). */
    val streamRouteReason: String? = null,
    /**
     * Optional library identity carried when packaging contentPayload through DetectedVideo
     * so external casts retain watch-progress metadata without a global arm slot.
     */
    @kotlinx.serialization.Transient
    val visualMetadata: playbridge.VisualMetadata? = null,
    /** Resume point for library contentPayload casts packaged through DetectedVideo. */
    val startPositionMs: Long = 0L,
) {
    val isSubtitle: Boolean
        get() = mediaKind.equals("subtitle", ignoreCase = true) ||
                contentType?.contains("vtt", ignoreCase = true) == true ||
                contentType?.contains("subrip", ignoreCase = true) == true ||
                url.endsWith(".vtt", ignoreCase = true) ||
                url.endsWith(".srt", ignoreCase = true)

    /**
     * True when detection captured a synthetic/exclusive handoff that should be cast
     * via the phone proxy (playlist body and/or demuxed audio), not as a bare Direct URL.
     */
    val hasSyntheticHandoff: Boolean
        get() = isSyntheticMaster ||
            !playlistBody.isNullOrBlank() ||
            !audioUrl.isNullOrBlank()

    val kind: DetectedMediaKind
        get() = classifyDetectedMediaKind(
            explicitKind = mediaKind,
            url = url,
            contentType = contentType,
            hlsRole = hlsRole,
        )

    val isAudio: Boolean get() = kind == DetectedMediaKind.AUDIO
    val isImage: Boolean get() = kind == DetectedMediaKind.IMAGE
    val isVideo: Boolean get() = kind == DetectedMediaKind.VIDEO

    val effectiveValidationState: MediaValidationState
        get() = when (isPlayable) {
            true -> MediaValidationState.VERIFIED_PLAYABLE
            false -> MediaValidationState.FAILED
            null -> validationState
        }
}

enum class DetectedMediaKind { VIDEO, AUDIO, IMAGE, SUBTITLE }

internal fun detectionEvidenceScore(detectedBy: String?): Int = when (detectedBy?.lowercase()) {
    "body_content_m3u8", "body_content_mpd" -> 80
    "synthetic_hls_master" -> 75
    "player_config" -> 70
    "content_type" -> 50
    "dom_source" -> 30
    "url_extension" -> 20
    "url_pattern_m3u8", "url_pattern_mpd" -> 10
    "response_body_url" -> 5
    else -> 15
}

internal fun validationStateForDetection(
    detectedBy: String?,
    isSyntheticMaster: Boolean,
): MediaValidationState = when {
    isSyntheticMaster -> MediaValidationState.VERIFIED_PLAYABLE
    detectedBy == "body_content_m3u8" || detectedBy == "body_content_mpd" ->
        MediaValidationState.VERIFIED_PLAYABLE
    else -> MediaValidationState.PENDING
}

data class DetectedMediaBadge(
    val kind: DetectedMediaKind,
    val count: Int,
)

/**
 * Chooses one concise toolbar badge instead of summing unrelated media types.
 * Playable video is most actionable, followed by audio and then images.
 */
fun buildDetectedMediaBadge(media: List<DetectedVideo>): DetectedMediaBadge? {
    val preferredKind = when {
        media.any { it.isVideo } -> DetectedMediaKind.VIDEO
        media.any { it.isAudio } -> DetectedMediaKind.AUDIO
        media.any { it.isImage } -> DetectedMediaKind.IMAGE
        else -> return null
    }
    return DetectedMediaBadge(
        kind = preferredKind,
        count = media.count { it.kind == preferredKind },
    )
}

private val AUDIO_FILE_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")
private val IMAGE_FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "heic", "heif")

internal fun classifyDetectedMediaKind(
    explicitKind: String?,
    url: String,
    contentType: String?,
    hlsRole: String?,
): DetectedMediaKind {
    when (explicitKind?.lowercase()) {
        "audio" -> return DetectedMediaKind.AUDIO
        "image" -> return DetectedMediaKind.IMAGE
        "subtitle" -> return DetectedMediaKind.SUBTITLE
        "video" -> return DetectedMediaKind.VIDEO
    }
    val mime = contentType.orEmpty().lowercase()
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val extension = path.substringAfterLast('.', "")
    return when {
        mime.contains("vtt") || mime.contains("subrip") || extension == "vtt" || extension == "srt" ->
            DetectedMediaKind.SUBTITLE
        hlsRole.equals("audio_media", ignoreCase = true) ->
            DetectedMediaKind.AUDIO
        mime.contains("mpegurl") || mime.contains("application/dash") ||
            path.contains("m3u8") || extension == "mpd" -> DetectedMediaKind.VIDEO
        mime.startsWith("audio/") || extension in AUDIO_FILE_EXTENSIONS -> DetectedMediaKind.AUDIO
        mime.startsWith("video/") -> DetectedMediaKind.VIDEO
        mime.startsWith("image/") || extension in IMAGE_FILE_EXTENSIONS -> DetectedMediaKind.IMAGE
        else -> DetectedMediaKind.VIDEO
    }
}

/** Title used for the dedicated cast-sheet row for synthetic demuxed/exclusive masters. */
const val SYNTHETIC_CAST_ITEM_TITLE = "Synthetic playlist (Via phone)"

/**
 * Ranking score for ordering and auto-picking detected videos (higher = better).
 * Verification is the strongest signal, followed by how the detector observed the
 * candidate, then adaptive-stream metadata, replay headers, and thumbnail success.
 * This keeps a URL that merely resembles an M3U8 below a manifest observed in a
 * response body, while still ranking unchecked candidates sensibly during validation.
 * A verified manifest with multiple selectable qualities is more useful than one of
 * its individual media playlists and must not lose that position merely because the
 * child happened to finish thumbnail generation first.
 *
 * Synthetic handoff masters outrank everything else so the cast sheet prefers them.
 *
 * Kept in one place so the cast sheet and the quick-cast / DLNA auto-pick paths can
 * never diverge.
 */
fun DetectedVideo.castScore(): Int {
    if (!isVideo) return 0
    if (hasSyntheticHandoff) return 1_000
    val isDash = url.contains(".mpd", ignoreCase = true) ||
                 contentType?.contains("dash", ignoreCase = true) == true
    val isHlsUrl = url.contains(".m3u8", ignoreCase = true) ||
                   contentType?.contains("mpegurl", ignoreCase = true) == true
    val looksAdaptive = isDash || isHlsUrl
    val validationScore = when (effectiveValidationState) {
        MediaValidationState.VERIFIED_PLAYABLE -> 600
        MediaValidationState.PENDING -> 300
        MediaValidationState.FAILED -> 0
    }
    val evidenceScore = detectionEvidenceScore(detectedBy)
    val adaptiveScore = when {
        hlsPlaylist?.validation == HlsPlaylistValidation.VALID_MASTER -> 35
        hlsPlaylist?.validation == HlsPlaylistValidation.VALID_MEDIA -> 30
        isDash && qualities.isNotEmpty() -> 35
        looksAdaptive -> 20
        else -> 10
    }
    val qualityLadderScore = if (
        qualities.size > 1 &&
        (hlsPlaylist?.validation == HlsPlaylistValidation.VALID_MASTER || isDash)
    ) {
        125
    } else {
        0
    }
    val replayScore = if (!headers.isNullOrEmpty()) 15 else 0
    val previewScore = if (thumbnailState == ThumbnailPreviewState.READY) 25 else 0
    return validationScore + evidenceScore + adaptiveScore + qualityLadderScore +
        replayScore + previewScore
}

/**
 * Builds the Videos-tab list for [CastSheet]: when any detection carries synthetic
 * handoff data, prepends a single dedicated row ("Synthetic playlist (Via phone)")
 * and keeps the remaining non-synthetic streams ranked below it.
 */
fun buildCastSheetVideos(videos: List<DetectedVideo>): List<DetectedVideo> {
    val playable = videos.filter { it.isVideo }
    val handoffSources = playable.filter { it.hasSyntheticHandoff }
    if (handoffSources.isEmpty()) {
        return playable.sortedWith(castSheetComparator(playable))
    }

    // Prefer a detector-emitted synthetic master with a body; else any body; else newest.
    // Within each tier, prefer the newest media lifecycle / activity so an SPA's latest
    // view wins over synthetic rows left over from previously opened streams.
    val byLifecycleThenActivity = compareBy<DetectedVideo> { it.lifecycleIndex }
        .thenBy { maxOf(it.timestamp, it.lastSeen) }
    val source = handoffSources
        .filter { it.isSyntheticMaster && !it.playlistBody.isNullOrBlank() }
        .maxWithOrNull(byLifecycleThenActivity)
        ?: handoffSources
            .filter { !it.playlistBody.isNullOrBlank() }
            .maxWithOrNull(byLifecycleThenActivity)
        ?: handoffSources.maxByOrNull { it.timestamp }
        ?: return playable.sortedWith(castSheetComparator(playable))

    val syntheticRow = source.copy(
        title = SYNTHETIC_CAST_ITEM_TITLE,
        detectedBy = "synthetic",
        isSyntheticMaster = true,
        contentType = source.contentType
            ?: "application/vnd.apple.mpegurl",
    )

    // Drop other synthetic masters and the handoff source URL (replaced by syntheticRow).
    val restCandidates = playable.filter { video ->
        !video.isSyntheticMaster && video.url != source.url
    }
    val rest = restCandidates.sortedWith(castSheetComparator(restCandidates))

    return listOf(syntheticRow) + rest
}

fun buildCastSheetAudio(videos: List<DetectedVideo>): List<DetectedVideo> =
    videos.filter { it.isAudio }.sortedByDescending { it.timestamp }

fun buildCastSheetImages(videos: List<DetectedVideo>): List<DetectedVideo> =
    videos.filter { it.isImage }.sortedByDescending { it.timestamp }

internal fun thumbnailPrefetchCandidates(
    media: List<DetectedVideo>,
    limit: Int = 2,
): List<DetectedVideo> = if (limit <= 0) {
    emptyList()
} else {
    val candidates = buildCastSheetVideos(media)
        .filter { it.effectiveValidationState != MediaValidationState.FAILED }
    val bestVerified = candidates.firstOrNull {
        it.effectiveValidationState == MediaValidationState.VERIFIED_PLAYABLE
    }
    val newestPending = candidates
        .filter { it.effectiveValidationState == MediaValidationState.PENDING }
        .maxByOrNull { maxOf(it.timestamp, it.lastSeen) }
    buildList {
        bestVerified?.let(::add)
        newestPending?.takeIf { pending -> none { it.url == pending.url } }?.let(::add)
        for (candidate in candidates) {
            if (size >= limit) break
            if (none { it.url == candidate.url }) add(candidate)
        }
    }.take(limit)
}

/**
 * Freshness influence on [castSheetComparator]. The newest candidate in the ranked list
 * receives the full bonus; it decays by [RECENCY_BONUS_PER_MINUTE] per whole minute of age
 * (relative to that newest candidate) down to zero. Verification, evidence, and quality
 * ladders still dominate a same-minute arrival, but a fully loaded stream that is minutes
 * fresher than a stale ladder master wins — the latest detected video should sit at the top
 * once earlier detections have had their moment. Timestamps are detector epoch millis, so
 * sub-minute differences (and the toy timestamps used in tests) produce no bonus difference.
 */
private const val RECENCY_BONUS_CAP = 150
private const val RECENCY_BONUS_PER_MINUTE = 50
private const val RECENCY_MINUTE_MS = 60_000L

/**
 * Bonus for detections born in the tab's newest media lifecycle (see
 * [DetectedVideo.lifecycleIndex]). On single-page apps each view change (clicking a
 * video on a listing page) starts a new lifecycle without clearing older rows. The
 * bonus is large enough that the stream for the view the user just opened rises to the
 * top immediately — above stale verified/laddered rows from previous views — while a
 * previous view's stream that is *still actively observed* (fresh lastSeen, e.g. live
 * playlist polls) holds the top until the new candidate finishes verification. Rows
 * from older lifecycles are demoted, never removed, so they remain castable.
 * Uniform when every candidate shares one lifecycle (e.g. no SPA navigations).
 */
private const val LIFECYCLE_BONUS = 400

private fun recencyBonus(video: DetectedVideo, newestMs: Long): Int {
    val seenMs = maxOf(video.timestamp, video.lastSeen)
    val ageMinutes = (newestMs - seenMs).coerceAtLeast(0L) / RECENCY_MINUTE_MS
    val decay = (ageMinutes * RECENCY_BONUS_PER_MINUTE).coerceAtMost(RECENCY_BONUS_CAP.toLong())
    return (RECENCY_BONUS_CAP - decay).toInt()
}

private fun lifecycleBonus(video: DetectedVideo, newestLifecycle: Int): Int =
    if (video.lifecycleIndex >= newestLifecycle) LIFECYCLE_BONUS else 0

private fun castSheetComparator(videos: List<DetectedVideo>): Comparator<DetectedVideo> {
    val newestMs = videos.maxOfOrNull { maxOf(it.timestamp, it.lastSeen) } ?: 0L
    val newestLifecycle = videos.maxOfOrNull { it.lifecycleIndex } ?: 0
    return compareByDescending<DetectedVideo> {
        it.castScore() + recencyBonus(it, newestMs) + lifecycleBonus(it, newestLifecycle)
    }.thenByDescending { maxOf(it.timestamp, it.lastSeen) }
}

/**
 * Data class representing an active subtitle period
 */
data class Cue(val startTime: Long, val endTime: Long, val text: String) : Comparable<Cue> {
    override fun compareTo(other: Cue): Int {
        return this.startTime.compareTo(other.startTime)
    }
}

/**
 * Singleton that manages video detection from the WebExtension.
 * Stores detected videos **per Kotlin tab ID** so that each browser tab
 * has its own isolated list of detected videos.
 */
object VideoDetector {

    private const val TAG = "VideoDetector"
    private const val MAX_HLS_THUMBNAIL_BYTES = 12 * 1024 * 1024
    private const val HLS_THUMBNAIL_SEGMENT_COUNT = 3

    /** Streams first seen this close before an SPA navigation adopt the new lifecycle. */
    private const val SAME_DOCUMENT_ADOPT_GRACE_MS = 2_000L

    private var appContext: Context? = null

    /**
     * Detection diagnostics may include media URLs, origins, and header *names*.
     * Debug builds only — never emit on release (see AGENTS.md logging rules).
     */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private val detectorDebugJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Pretty-print a detector payload one logcat line at a time.
     * Android truncates single log lines; multi-line JSON must be split.
     */
    private fun debugLogJson(label: String, message: JsonObject) {
        if (!BuildConfig.DEBUG) return
        val pretty = try {
            detectorDebugJson.encodeToString(message)
        } catch (_: Exception) {
            try {
                org.json.JSONObject(message.toString()).toString(2)
            } catch (_: Exception) {
                message.toString()
            }
        }
        Log.d(TAG, "$label — payload:")
        pretty.lineSequence().forEach { line ->
            if (line.isNotEmpty()) Log.d(TAG, line)
        }
    }

    /** Call once from Application or Activity.onCreate so HLS thumbnail extraction has a cache dir. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Headers that are browser-context-specific and must not be forwarded to media players.
     * Sending `sec-fetch-site: same-origin` to a CDN (different domain) causes the CDN to
     * reject segment requests. `sec-ch-ua-*` are client-hint fingerprinting headers that
     * media players should never send.
     */
    val PLAYER_SKIP_HEADERS: Set<String> = setOf(
        "Range", "Accept-Encoding", "Host", "Connection", "Content-Length",
        "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site", "Sec-Fetch-Storage-Access",
        "Sec-GPC", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform",
        "Priority", "Upgrade-Insecure-Requests", "TE", "Pragma"
    )

    /** Returns the cached thumbnail for [url], or null if not yet fetched. */
    fun getCachedThumbnail(url: String): Bitmap? = synchronized(thumbnailCache) { thumbnailCache[url] }

    /** Returns true if a thumbnail has already been fetched and cached for this URL. */
    fun hasThumbnail(url: String): Boolean = synchronized(thumbnailCache) { thumbnailCache.containsKey(url) }

    /** Returns a headers map safe to pass to ExoPlayer or MediaMetadataRetriever. */
    fun mediaHeaders(video: DetectedVideo): HashMap<String, String> {
        val result = HashMap<String, String>()
        video.headers?.forEach { (k, v) ->
            if (PLAYER_SKIP_HEADERS.none { it.equals(k, ignoreCase = true) }) result[k] = v
        }
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        return result
    }

    // Per-tab storage: Kotlin tab ID -> list of detected videos
    private val tabVideos = mutableStateMapOf<String, SnapshotStateList<DetectedVideo>>()

    // Thumbnail cache: URL -> Bitmap (max 20 entries, LRU eviction)
    private val thumbnailCache: LinkedHashMap<String, Bitmap> =
        object : LinkedHashMap<String, Bitmap>(20, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > 20
        }

    // Per-tab seen URLs to avoid duplicates
    private val tabSeenUrls = mutableMapOf<String, MutableSet<String>>()

    // Per-tab media lifecycle counter: bumped on same-document (SPA) navigations.
    // Detections stamp the current value into DetectedVideo.lifecycleIndex.
    private val tabLifecycleIndex = mutableMapOf<String, Int>()

    // Last document generation accepted from the GeckoView detector per Kotlin tab.
    private val detectorPageTracker = DetectorPageTracker()

    // Track ignored URLs (e.g., HLS variants) — global since variants can appear across tabs
    private val ignoredUrls = mutableSetOf<String>()

    /**
     * Incremented on the main thread whenever a video's playability or quality status changes.
     * Observed as an explicit Compose input in BrowserActivity so the sheet's sort order updates
     * automatically without the user closing and reopening it.
     */
    var processingVersion by mutableIntStateOf(0)
        private set

    /** Must be called from the main thread after updating any video's sort-relevant fields. */
    private fun notifyVideoUpdated() { processingVersion++ }

    /**
     * Duplicate detector messages replace a SnapshotStateList element with an enriched copy.
     * A probe that started on the previous instance must publish its result to that current copy,
     * otherwise the cache can contain a thumbnail while the sheet still ranks the row as pending.
     * Called only from the main thread.
     */
    private fun syncProbeStateToTrackedCopies(
        source: DetectedVideo,
        fileSize: Boolean = false,
        manifest: Boolean = false,
        thumbnail: Boolean = false,
    ) {
        tabVideos.values.forEach { videos ->
            videos.forEach trackedLoop@ { tracked ->
                if (tracked === source || tracked.url != source.url) return@trackedLoop
                if (source.tabId != -1 && tracked.tabId != source.tabId) return@trackedLoop
                if (fileSize) {
                    tracked.fileSize = source.fileSize
                    tracked.fileSizeChecked = source.fileSizeChecked
                }
                if (manifest) {
                    tracked.qualities = source.qualities
                    tracked.qualitiesChecked = source.qualitiesChecked
                    tracked.hlsPlaylist = source.hlsPlaylist
                }
                when (source.effectiveValidationState) {
                    MediaValidationState.VERIFIED_PLAYABLE -> {
                        tracked.isPlayable = true
                        tracked.validationState = MediaValidationState.VERIFIED_PLAYABLE
                    }
                    MediaValidationState.FAILED -> {
                        // Do not let an older probe overwrite stronger body evidence that arrived
                        // on an enriched replacement while that probe was in flight.
                        if (tracked.effectiveValidationState !=
                            MediaValidationState.VERIFIED_PLAYABLE
                        ) {
                            tracked.isPlayable = false
                            tracked.validationState = MediaValidationState.FAILED
                        }
                    }
                    MediaValidationState.PENDING -> Unit
                }
                if (thumbnail) tracked.thumbnailState = source.thumbnailState
            }
        }
    }

    private val thumbnailWorkMutex = Mutex()
    private val thumbnailRequests = ThumbnailRequestCoordinator<String, Bitmap>()

    /**
     * Get the observable video list for a specific tab.
     * Returns an empty list if no videos have been detected for the tab.
     */
    fun getVideosForTab(tabId: String): List<DetectedVideo> {
        return tabVideos[tabId] ?: emptyList()
    }

    /**
     * Get the count of detected videos for a specific tab.
     */
    fun getVideoCountForTab(tabId: String): Int {
        return tabVideos[tabId]?.size ?: 0
    }

    /**
     * Apply a committed navigation without allowing a delayed message for the
     * same generation to erase media that already arrived.
     */
    internal fun onDetectorNavigation(
        tabId: String,
        incoming: DetectorPageVersion,
    ): DetectorMessageOrder {
        val order = detectorPageTracker.observe(tabId, incoming)
        if (order == DetectorMessageOrder.ADVANCE) {
            clearTabMedia(tabId)
        }
        return order
    }

    /**
     * Prepare for a detection. A newer detection may arrive before its navigation
     * message, so it advances and clears atomically; stale detections are rejected.
     */
    internal fun acceptDetectorVideo(tabId: String, incoming: DetectorPageVersion): Boolean {
        return when (detectorPageTracker.observe(tabId, incoming)) {
            DetectorMessageOrder.ADVANCE -> {
                clearTabMedia(tabId)
                true
            }
            DetectorMessageOrder.CURRENT -> true
            DetectorMessageOrder.STALE -> false
        }
    }

    /** Current media lifecycle for [tabId]; detections stamp this into their row. */
    internal fun lifecycleIndexForTab(tabId: String): Int = tabLifecycleIndex[tabId] ?: 0

    /**
     * A same-document (SPA) navigation starts a new media lifecycle WITHOUT clearing
     * detections: streams from earlier views may still be playing and stay castable,
     * just ranked below what the user navigated to. Rows first seen within the grace
     * window adopt the new lifecycle because the stream that triggered the route change
     * can race ahead of this message. Returns false for stale detector messages.
     */
    internal fun onSameDocumentNavigation(
        tabId: String,
        incoming: DetectorPageVersion,
        atMs: Long,
    ): Boolean {
        return when (detectorPageTracker.observe(tabId, incoming)) {
            DetectorMessageOrder.ADVANCE -> {
                // Generation moved underneath us (missed commit); fall back to clearing.
                clearTabMedia(tabId)
                true
            }
            DetectorMessageOrder.CURRENT -> {
                val next = (tabLifecycleIndex[tabId] ?: 0) + 1
                tabLifecycleIndex[tabId] = next
                tabVideos[tabId]?.let { videos ->
                    for (i in videos.indices) {
                        val video = videos[i]
                        if (video.lifecycleIndex < next &&
                            maxOf(video.timestamp, video.lastSeen) >=
                                atMs - SAME_DOCUMENT_ADOPT_GRACE_MS
                        ) {
                            videos[i] = video.copy(lifecycleIndex = next)
                        }
                    }
                }
                notifyVideoUpdated()
                true
            }
            DetectorMessageOrder.STALE -> false
        }
    }

    /**
     * Process a message received from the video detector extension,
     * associating it with the given Kotlin tab ID.
     */
    fun onMessageReceived(message: JsonObject, kotlinTabId: String) {
        debugLogJson("Received message for tab $kotlinTabId", message)

        val type = message["type"]?.jsonPrimitive?.content

        when (type) {
            "video_detected" -> {
                val url = message["url"]?.jsonPrimitive?.content ?: return

                // Check if URL is in exact ignore list or starts with an ignored segment prefix
                if (ignoredUrls.contains(url) || ignoredUrls.any { url.startsWith(it) }) {
                    debugLog("Ignoring media URL (matched blocklist or segment prefix): $url")
                    return
                }

                val headersJson = try { message["headers"]?.jsonObject } catch(e: Exception) { null }
                val headers = headersJson?.mapValues { it.value.jsonPrimitive.content }
                val incomingDetectedBy =
                    message["detectedBy"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val incomingTimestamp =
                    message["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                val incomingLastSeen =
                    message["lastSeen"]?.jsonPrimitive?.longOrNull ?: incomingTimestamp

                // Get or create per-tab structures
                val videos = tabVideos.getOrPut(kotlinTabId) { mutableStateListOf() }
                val seenUrls = tabSeenUrls.getOrPut(kotlinTabId) { mutableSetOf() }

                // Check if already exists to update
                val existingIndex = videos.indexOfFirst { it.url == url }

                if (existingIndex != -1) {
                    val existing = videos[existingIndex]
                    val playlistBody = message["playlistBody"]?.jsonPrimitive?.contentOrNull
                    val audioUrl = message["audioUrl"]?.jsonPrimitive?.contentOrNull
                    val incomingMediaKind = message["mediaKind"]?.jsonPrimitive?.contentOrNull
                    val incomingWidth = message["width"]?.jsonPrimitive?.intOrNull
                    val incomingHeight = message["height"]?.jsonPrimitive?.intOrNull
                    val isSynthetic =
                        message["isSyntheticMaster"]?.jsonPrimitive?.booleanOrNull ?: false
                    val evidenceUpgraded =
                        detectionEvidenceScore(incomingDetectedBy) >
                            detectionEvidenceScore(existing.detectedBy)
                    val incomingValidation = validationStateForDetection(
                        incomingDetectedBy,
                        isSynthetic,
                    )
                    val shouldUpdate =
                        (headers != null && headers.isNotEmpty()) ||
                            !playlistBody.isNullOrBlank() ||
                            !audioUrl.isNullOrBlank() ||
                            incomingMediaKind != null ||
                            incomingWidth != null ||
                            incomingHeight != null ||
                            isSynthetic ||
                            evidenceUpgraded ||
                            incomingLastSeen > existing.lastSeen
                    if (shouldUpdate) {
                        debugLog(
                            "Updating detection for tab $kotlinTabId " +
                                "kind=${incomingMediaKind ?: existing.mediaKind ?: "?"} " +
                                "by=$incomingDetectedBy evidenceUpgraded=$evidenceUpgraded",
                        )
                        videos[existingIndex] = existing.copy(
                            headers = headers ?: existing.headers,
                            originUrl = message["originUrl"]?.jsonPrimitive?.content
                                ?: existing.originUrl,
                            contentType = message["contentType"]?.jsonPrimitive?.content
                                ?: existing.contentType,
                            originalMessage = message.toString(),
                            playlistBody = playlistBody ?: existing.playlistBody,
                            audioUrl = audioUrl ?: existing.audioUrl,
                            hlsRole = message["hlsRole"]?.jsonPrimitive?.contentOrNull
                                ?: existing.hlsRole,
                            isSyntheticMaster = isSynthetic || existing.isSyntheticMaster,
                            mediaKind = incomingMediaKind ?: existing.mediaKind,
                            width = incomingWidth ?: existing.width,
                            height = incomingHeight ?: existing.height,
                            detectedBy = if (evidenceUpgraded) {
                                incomingDetectedBy
                            } else {
                                existing.detectedBy
                            },
                            lastSeen = maxOf(existing.lastSeen, incomingLastSeen),
                            // Fresh detector activity in a newer SPA view re-homes the row.
                            lifecycleIndex = maxOf(
                                existing.lifecycleIndex,
                                lifecycleIndexForTab(kotlinTabId),
                            ),
                            validationState = if (
                                incomingValidation == MediaValidationState.VERIFIED_PLAYABLE
                            ) {
                                MediaValidationState.VERIFIED_PLAYABLE
                            } else {
                                existing.validationState
                            },
                            isPlayable = if (
                                incomingValidation == MediaValidationState.VERIFIED_PLAYABLE
                            ) {
                                true
                            } else {
                                existing.isPlayable
                            },
                        )
                        notifyVideoUpdated()
                    }
                    return
                }

                val isSynthetic =
                    message["isSyntheticMaster"]?.jsonPrimitive?.booleanOrNull ?: false
                val video = DetectedVideo(
                    url = url,
                    tabId = message["tabId"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                    contentType = message["contentType"]?.jsonPrimitive?.content,
                    detectedBy = incomingDetectedBy,
                    originUrl = message["originUrl"]?.jsonPrimitive?.content,
                    headers = headers,
                    timestamp = incomingTimestamp,
                    originalMessage = message.toString(),
                    playlistBody = message["playlistBody"]?.jsonPrimitive?.contentOrNull,
                    audioUrl = message["audioUrl"]?.jsonPrimitive?.contentOrNull,
                    hlsRole = message["hlsRole"]?.jsonPrimitive?.contentOrNull,
                    isSyntheticMaster = isSynthetic,
                    mediaKind = message["mediaKind"]?.jsonPrimitive?.contentOrNull,
                    width = message["width"]?.jsonPrimitive?.intOrNull,
                    height = message["height"]?.jsonPrimitive?.intOrNull,
                    lastSeen = incomingLastSeen,
                    lifecycleIndex = lifecycleIndexForTab(kotlinTabId),
                    validationState = validationStateForDetection(
                        incomingDetectedBy,
                        isSynthetic,
                    ),
                )

                debugLog(
                    "MEDIA DETECTED tab=$kotlinTabId kind=${video.mediaKind ?: video.kind} " +
                        "by=${video.detectedBy} type=${video.contentType ?: "N/A"} " +
                        "headers=${video.headers?.size ?: 0} lifecycle=${video.lifecycleIndex} " +
                        "url=$url",
                )

                seenUrls.add(url)
                videos.add(video)
                notifyVideoUpdated()
            }
            else -> {
                debugLog("Unknown detector message type: $type")
            }
        }
    }

    /**
     * Legacy overload — routes to "unknown" tab. Prefer the tab-aware overload.
     */
    fun onMessageReceived(message: JsonObject) {
        onMessageReceived(message, "_unknown")
    }

    /**
     * Fetch file size for a video URL using HEAD request
     */
    suspend fun fetchFileSize(video: DetectedVideo): Long? {
        if (video.fileSizeChecked) {
            return video.fileSize
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(video.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                video.headers?.forEach { (key, value) ->
                    if (!key.equals("Range", ignoreCase = true)) {
                        connection.setRequestProperty(key, value)
                    }
                }

                connection.connect()

                val contentLength = connection.contentLengthLong
                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..299) {
                    video.fileSize = if (contentLength > 0) contentLength else null
                    val isAdaptiveManifest =
                        video.url.contains(".m3u8", ignoreCase = true) ||
                            video.url.contains(".mpd", ignoreCase = true) ||
                            video.contentType?.contains("mpegurl", ignoreCase = true) == true ||
                            video.contentType?.contains("dash", ignoreCase = true) == true
                    if (!isAdaptiveManifest) {
                        // A successful HEAD is useful evidence for a progressive file, but it says
                        // nothing about whether an adaptive URL actually contains a valid manifest.
                        video.isPlayable = true
                        video.validationState = MediaValidationState.VERIFIED_PLAYABLE
                    }
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    responseCode == HttpURLConnection.HTTP_GONE
                ) {
                    video.isPlayable = false
                    video.validationState = MediaValidationState.FAILED
                    debugLog("Media probe confirmed unavailable: HTTP $responseCode")
                } else {
                    // HEAD is commonly rejected even when GET playback works. Only a definitive
                    // missing response should invalidate a candidate; manifest/preview work will
                    // establish playability for other statuses.
                    debugLog("Media HEAD probe inconclusive: HTTP $responseCode")
                }

                video.fileSizeChecked = true
                debugLog("File size for ${video.url.take(50)}: ${video.fileSize ?: "unknown"}")

                withContext(Dispatchers.Main) {
                    syncProbeStateToTrackedCopies(video, fileSize = true)
                    notifyVideoUpdated()
                }
                video.fileSize
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching file size: ${e.message}")
                video.fileSizeChecked = true
                withContext(Dispatchers.Main) {
                    syncProbeStateToTrackedCopies(video, fileSize = true)
                    notifyVideoUpdated()
                }
                null
            }
        }
    }

    /**
     * Fetch a small preview of a subtitle file to help identify language
     */
    suspend fun fetchSubtitlePreview(video: DetectedVideo): String? {
        if (video.subtitlePreviewChecked) {
            return video.subtitlePreview
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(video.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true

                // Set Range header to fetch only first 4KB to ensure we get a few cues
                connection.setRequestProperty("Range", "bytes=0-4096")

                // Forward the page's request headers (Referer / Cookie / User-Agent) — many
                // subtitle hosts 403 a bare request, which is why previews sometimes don't load.
                val hdrs = video.headers ?: emptyMap()
                hdrs.forEach { (k, v) -> runCatching { connection.setRequestProperty(k, v) } }
                if (!video.originUrl.isNullOrEmpty() && hdrs.keys.none { it.equals("Referer", ignoreCase = true) }) {
                    connection.setRequestProperty("Referer", video.originUrl)
                }
                if (hdrs.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                }

                connection.connect()

                // Check if response is partial content (206) or OK (200)
                if (connection.responseCode in 200..299) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }

                    if (content.isNotEmpty()) {
                        val cues = if (video.url.endsWith(".vtt", ignoreCase = true) ||
                                     video.contentType?.contains("vtt", ignoreCase = true) == true) {
                            parseVtt(content)
                        } else {
                            parseSrt(content)
                        }

                        // Extract first 3 cues
                        val previewText = cues.take(3).joinToString(" • ") {
                            it.text.replace("\n", " ")
                        }

                        if (previewText.isNotEmpty()) {
                            video.subtitlePreview = previewText
                        } else {
                            // Fallback if parsing failed but we got text
                            val fallbackLines = content.lineSequence().map { it.trim() }.filter { trimmed ->
                                trimmed.isNotEmpty() &&
                                !trimmed.contains("WEBVTT", ignoreCase = true) &&
                                !trimmed.contains("-->") &&
                                trimmed.toIntOrNull() == null
                            }.take(2).toList()
                            video.subtitlePreview = fallbackLines.joinToString(" • ")
                        }

                        video.subtitlePreviewChecked = true
                        debugLog(
                            "Subtitle preview for ${video.url.take(30)}: ${video.subtitlePreview}",
                        )
                        video.subtitlePreview
                    } else {
                        video.subtitlePreviewChecked = true
                        null
                    }
                } else {
                    connection.disconnect()
                    video.subtitlePreviewChecked = true
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching subtitle preview: ${e.message}")
                video.subtitlePreviewChecked = true
                null
            }
        }
    }

    // Subtitle parsing helpers copied from TV's SubtitleManager

    private fun parseSrt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        var currentStart = -1L
        var currentEnd = -1L
        val currentText = StringBuilder()

        // Use lineSequence() for O(1) memory parsing
        val iterator = content.lineSequence().iterator()
        while (iterator.hasNext()) {
            val rawLine = iterator.next()
            val trimmedLine = rawLine.trim()

            if (trimmedLine.isEmpty()) {
                if (currentStart != -1L && currentEnd != -1L && currentText.isNotEmpty()) {
                    parsedCues.add(Cue(currentStart, currentEnd, currentText.toString().trimEnd()))
                }
                currentStart = -1L
                currentEnd = -1L
                currentText.clear()
            } else if (trimmedLine.contains("-->")) {
                val times = trimmedLine.split("-->")
                if (times.size == 2) {
                    currentStart = parseTimestamp(times[0].trim().replace(',', '.'))
                    currentEnd = parseTimestamp(times[1].trim().replace(',', '.'))
                }
            } else if (currentStart != -1L) {
                // If we have a start time, any subsequent non-empty line is part of the text
                currentText.append(rawLine).append("\n")
            }
        }
        // Add final cue if file doesn't end with blank line
        if (currentStart != -1L && currentEnd != -1L && currentText.isNotEmpty()) {
            parsedCues.add(Cue(currentStart, currentEnd, currentText.toString().trimEnd()))
        }
        return parsedCues
    }

    private fun parseVtt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()

        // Use lineSequence() for O(1) memory parsing
        val iterator = content.lineSequence().iterator()
        while (iterator.hasNext()) {
            val line = iterator.next().trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = parseTimestamp(times[0].trim())
                    val end = parseTimestamp(times[1].trim())

                    val textBuilder = StringBuilder()
                    while (iterator.hasNext()) {
                        val textLine = iterator.next()
                        if (textLine.trim().isEmpty()) break
                        textBuilder.append(textLine).append("\n")
                    }
                    val text = textBuilder.toString().trim()

                    if (start != -1L && end != -1L && text.isNotEmpty()) {
                        parsedCues.add(Cue(start, end, text))
                    }
                }
            }
        }
        return parsedCues
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val parts = timestamp.split(':')
            var hours = 0L
            var minutes = 0L
            var seconds = 0.0

            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                seconds = parts[2].replace(',', '.').toDouble()
            } else if (parts.size == 2) {
                minutes = parts[0].toLong()
                seconds = parts[1].replace(',', '.').toDouble()
            } else {
                return -1
            }

            (hours * 3600000 + minutes * 60000 + (seconds * 1000)).toLong()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Fetch HLS variant qualities if the video is an m3u8 playlist.
     * Operates on a specific tab's video list for variant cleanup.
     */
    suspend fun fetchHlsQualities(video: DetectedVideo, kotlinTabId: String? = null): List<VideoQuality> {
        if (video.qualitiesChecked) {
            return video.qualities
        }

        return withContext(Dispatchers.IO) {
            try {
                // DASH/MPD
                if (video.url.contains(".mpd", ignoreCase = true) ||
                    video.contentType?.contains("dash", ignoreCase = true) == true) {

                    val qualities = DashParser.parseManifest(video.url, video.headers)
                    video.qualities = qualities
                    video.qualitiesChecked = true
                    if (qualities.isNotEmpty()) {
                        video.isPlayable = true
                        video.validationState = MediaValidationState.VERIFIED_PLAYABLE
                    } else {
                        video.isPlayable = false
                        video.validationState = MediaValidationState.FAILED
                    }
                    debugLog("Fetched ${qualities.size} DASH qualities for ${video.url}")
                    // Bump the version so the cast sheet re-derives and re-ranks live — the
                    // HLS path below does this, but DASH was returning early without it, so a
                    // parsed DASH stream only moved up after the sheet was reopened.
                    withContext(Dispatchers.Main) {
                        syncProbeStateToTrackedCopies(video, manifest = true)
                        notifyVideoUpdated()
                    }
                    return@withContext qualities
                }

                // simple check if it looks like an m3u8 url
                if (video.url.contains(".m3u8", ignoreCase = true) ||
                    video.contentType?.contains("mpegurl", ignoreCase = true) == true) {

                    val playlist = HlsParser.parsePlaylist(video.url, video.headers)
                    video.hlsPlaylist = playlist
                    video.qualities = playlist.videoQualities
                    video.qualitiesChecked = true
                    when (playlist.validation) {
                        HlsPlaylistValidation.VALID_MASTER,
                        HlsPlaylistValidation.VALID_MEDIA -> {
                            video.validationState = MediaValidationState.VERIFIED_PLAYABLE
                            video.isPlayable = true
                        }
                        HlsPlaylistValidation.INVALID -> {
                            video.validationState = MediaValidationState.FAILED
                            video.isPlayable = false
                        }
                        HlsPlaylistValidation.FETCH_FAILED -> {
                            // A replay request can fail after the extension has already observed
                            // and parsed the real response. Preserve that stronger evidence and
                            // leave unchecked URLs retryable from a visible row.
                            video.qualitiesChecked = false
                        }
                    }

                    if (playlist.segmentPrefixes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Only add prefixes that don't accidentally match the master playlist
                            // URL itself (e.g. stream.m3u8 is in the same directory as segments).
                            val safeSegmentPrefixes = playlist.segmentPrefixes
                                .filter { !video.url.startsWith(it) }
                            ignoredUrls.addAll(safeSegmentPrefixes)

                            if (kotlinTabId != null) {
                                val prefixes = playlist.segmentPrefixes
                                tabVideos[kotlinTabId]?.removeAll { detected ->
                                    detected.url != video.url &&
                                    prefixes.any { detected.url.startsWith(it) }
                                }
                            }
                        }
                    }

                    if (playlist.videoQualities.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Add variants to ignore list so future detections are filtered
                            playlist.videoQualities.forEach { quality ->
                                ignoredUrls.add(quality.url)
                            }

                            // Only remove existing items when we have a confirmed tab ID.
                            if (kotlinTabId != null) {
                                tabVideos[kotlinTabId]?.removeAll { detected ->
                                    ignoredUrls.contains(detected.url)
                                }
                            }
                        }
                    }

                    debugLog("Fetched ${playlist.videoQualities.size} HLS qualities for ${video.url}")
                    withContext(Dispatchers.Main) {
                        syncProbeStateToTrackedCopies(video, manifest = true)
                        notifyVideoUpdated()
                    }
                    playlist.videoQualities

                } else {
                    video.qualitiesChecked = true
                    emptyList()
                }
            } catch (e: Exception) {
                // Avoid logging the stream URL in release; message alone is enough for failures.
                Log.e(TAG, "Error fetching HLS qualities: ${e.message}")
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "HLS qualities URL was: ${video.url}")
                }
                video.isPlayable = false
                video.validationState = MediaValidationState.FAILED
                video.qualitiesChecked = true
                withContext(Dispatchers.Main) {
                    syncProbeStateToTrackedCopies(video, manifest = true)
                    notifyVideoUpdated()
                }
                emptyList()
            }
        }
    }

    /** Fetches a thumbnail through HLS sample reconstruction or a bounded progressive download. */
    internal suspend fun fetchThumbnail(
        video: DetectedVideo,
        priority: ThumbnailRequestPriority = ThumbnailRequestPriority.VISIBLE,
    ): Bitmap? {
        if (!video.isVideo) return null
        if (video.url.startsWith("data:", ignoreCase = true)) return null
        val cached = synchronized(thumbnailCache) { thumbnailCache[video.url] }
        if (cached != null) {
            withContext(Dispatchers.Main) {
                val changed = video.thumbnailState != ThumbnailPreviewState.READY ||
                    video.effectiveValidationState != MediaValidationState.VERIFIED_PLAYABLE
                video.thumbnailState = ThumbnailPreviewState.READY
                video.isPlayable = true
                video.validationState = MediaValidationState.VERIFIED_PLAYABLE
                if (changed) {
                    syncProbeStateToTrackedCopies(video, thumbnail = true)
                    notifyVideoUpdated()
                }
            }
            return cached
        }

        val bitmap = thumbnailRequests.run(video.url, priority) {
            thumbnailWorkMutex.withLock {
                // Another URL can finish while this request waits for the decoder slot.
                synchronized(thumbnailCache) {
                    thumbnailCache[video.url]
                } ?: withContext(Dispatchers.IO) {
                    val isHls = video.url.contains(".m3u8", ignoreCase = true) ||
                        video.contentType?.contains("mpegurl", ignoreCase = true) == true
                    val bmp: Bitmap? = if (isHls && appContext != null) {
                        fetchHlsThumbnail(video)
                    } else if (appContext != null) {
                        fetchProgressiveThumbnail(video)
                    } else {
                        null
                    }
                    if (bmp != null) {
                        synchronized(thumbnailCache) { thumbnailCache[video.url] = bmp }
                    }
                    bmp
                }
            }
        }
        withContext(Dispatchers.Main) {
            val nextPreviewState = if (bitmap != null) {
                ThumbnailPreviewState.READY
            } else {
                ThumbnailPreviewState.UNAVAILABLE
            }
            val changed = video.thumbnailState != nextPreviewState ||
                (bitmap != null &&
                    video.effectiveValidationState != MediaValidationState.VERIFIED_PLAYABLE)
            video.thumbnailState = nextPreviewState
            if (bitmap != null) {
                video.isPlayable = true
                video.validationState = MediaValidationState.VERIFIED_PLAYABLE
            }
            if (changed) {
                syncProbeStateToTrackedCopies(video, thumbnail = true)
                notifyVideoUpdated()
            }
        }
        return bitmap
    }

    /**
     * Progressive thumbnail extraction:
     * 1. Download the first 2 MB of the file to a temp file.
     * 2. Run MMR on the local file.
     */
    private fun fetchProgressiveThumbnail(video: DetectedVideo): Bitmap? {
        val ctx = appContext ?: return null
        val tempFile = File.createTempFile("playbridge_prog_thumb_", ".tmp", ctx.cacheDir)
        return try {
            // Download first 2MB
            if (!downloadSegmentToFile(video.url, video.headers, tempFile, maxBytes = 2 * 1024 * 1024)) {
                Log.w(TAG, "Progressive thumbnail download failed")
                return null
            }
            extractThumbnailFromFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Runs MediaMetadataRetriever on a local file.
     */
    private fun extractThumbnailFromFile(file: File): Bitmap? {
        var result: Bitmap? = null
        var exception: Exception? = null
        val latch = CountDownLatch(1)
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    
                    // For short clips, seek to 0.5s. For others, seek to 1s (safe within 2MB chunk).
                    val seekUs = if (durationMs > 2_000L) 1_000_000L else 500_000L
                    result = retriever.getFrameAtTime(
                        seekUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: retriever.getFrameAtTime()
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                exception = e
            }
            latch.countDown()
        }.start()

        val completed = latch.await(10L, TimeUnit.SECONDS)
        val failure = exception
        return when {
            !completed -> {
                Log.w(TAG, "MMR thumbnail extraction timed out")
                null
            }
            failure != null -> {
                Log.w(TAG, "MMR extraction failed: ${failure.message}")
                null
            }
            else -> result
        }
    }

    private data class ParsedThumbnailPlaylist(
        val url: String,
        val playlist: Media3HlsMediaPlaylist,
    )

    /**
     * HLS thumbnail extraction reconstructs a small, locally decodable sample. Media playlists can
     * use MPEG-TS, fragmented MP4 initialization sections, byte ranges, or AES-128 encryption, so
     * treating a URI line as a standalone `.ts` file is not sufficient.
     */
    private fun fetchHlsThumbnail(video: DetectedVideo): Bitmap? {
        val ctx = appContext ?: return null

        // If fetchHlsQualities already ran and found variant playlists, use the lowest-bandwidth
        // variant's media playlist URL to save bandwidth. Otherwise video.url is the playlist itself.
        val mediaPlaylistUrl: String = run {
            val playlist = video.hlsPlaylist
            if (playlist != null && playlist.videoQualities.isNotEmpty()) {
                playlist.videoQualities.minByOrNull { it.bandwidth }?.url ?: video.url
            } else {
                video.url
            }
        }

        val parsed = fetchThumbnailMediaPlaylist(mediaPlaylistUrl, video.headers)
        val segments = parsed?.playlist?.segments?.filterNot { it.hasGapTag }.orEmpty()
        if (parsed == null || segments.isEmpty()) {
            Log.w(TAG, "HLS thumbnail: no usable media segments")
            return null
        }

        // ~25% into the segment list for a mid-stream frame (avoids intros)
        val targetIndex = ((segments.size - 1) * 0.25).toInt()
        val target = segments[targetIndex]
        val isFragmentedMp4 = target.initializationSegment != null
        debugLog(
            "HLS thumbnail: segment [${targetIndex + 1}/${segments.size}] " +
                "init=$isFragmentedMp4 encrypted=${target.fullSegmentEncryptionKeyUri != null} " +
                "range=${target.byteRangeLength != C.LENGTH_UNSET.toLong()}",
        )

        if (target.drmInitData != null && target.fullSegmentEncryptionKeyUri == null) {
            Log.w(TAG, "HLS thumbnail: sample-encrypted/DRM stream cannot be decoded for preview")
            return null
        }

        val suffix = if (isFragmentedMp4) ".mp4" else ".ts"
        val tempFile = File.createTempFile("playbridge_thumb_", suffix, ctx.cacheDir)
        return try {
            if (!writeHlsThumbnailSample(parsed, segments, targetIndex, video.headers, tempFile)) {
                Log.w(TAG, "HLS thumbnail: could not reconstruct a decodable sample")
                return null
            }
            extractThumbnailFromFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun fetchThumbnailMediaPlaylist(
        playlistUrl: String,
        headers: Map<String, String>?,
        depth: Int = 0,
        multivariant: Media3HlsMultivariantPlaylist? = null,
    ): ParsedThumbnailPlaylist? {
        if (depth > 3) return null
        val connection = try {
            openThumbnailConnection(playlistUrl, headers)
        } catch (e: Exception) {
            Log.w(TAG, "HLS thumbnail playlist connection failed: ${e.message}")
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val resolvedPlaylistUrl = connection.url.toString()
            val parsed = connection.inputStream.use { input ->
                val parser = if (multivariant != null) {
                    Media3HlsPlaylistParser(multivariant, null)
                } else {
                    Media3HlsPlaylistParser()
                }
                parser.parse(Uri.parse(resolvedPlaylistUrl), input)
            }
            when (parsed) {
                is Media3HlsMediaPlaylist -> ParsedThumbnailPlaylist(resolvedPlaylistUrl, parsed)
                is Media3HlsMultivariantPlaylist -> {
                    val videoVariants = parsed.variants.filter { variant ->
                        variant.format.width > 0 ||
                            variant.format.height > 0 ||
                            variant.format.codecs.orEmpty().contains(
                                Regex("avc|hvc|hev|vp9|vp0?9|av01", RegexOption.IGNORE_CASE),
                            )
                    }.ifEmpty { parsed.variants }
                    val variant = videoVariants.minByOrNull { variant ->
                        variant.format.averageBitrate.takeIf { it > 0 }
                            ?: variant.format.peakBitrate.takeIf { it > 0 }
                            ?: Int.MAX_VALUE
                    } ?: return null
                    fetchThumbnailMediaPlaylist(
                        playlistUrl = variant.url.toString(),
                        headers = headers,
                        depth = depth + 1,
                        multivariant = parsed,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS thumbnail playlist parse failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun writeHlsThumbnailSample(
        parsed: ParsedThumbnailPlaylist,
        segments: List<Media3HlsMediaPlaylist.Segment>,
        targetIndex: Int,
        headers: Map<String, String>?,
        outFile: File,
    ): Boolean {
        val target = segments[targetIndex]
        val keyCache = mutableMapOf<String, ByteArray>()
        var totalBytes = 0
        var writtenSegments = 0

        return try {
            outFile.outputStream().use { output ->
                target.initializationSegment?.let { init ->
                    val bytes = fetchHlsResource(
                        playlistUrl = parsed.url,
                        resource = init,
                        headers = headers,
                        keyCache = keyCache,
                        maxBytes = MAX_HLS_THUMBNAIL_BYTES,
                    ) ?: return false
                    output.write(bytes)
                    totalBytes += bytes.size
                }

                for (segment in segments.drop(targetIndex).take(HLS_THUMBNAIL_SEGMENT_COUNT)) {
                    if (segment.relativeDiscontinuitySequence != target.relativeDiscontinuitySequence) break
                    if (segment.drmInitData != null && segment.fullSegmentEncryptionKeyUri == null) break
                    if (!sameHlsInitializationSection(segment, target)) break
                    val remaining = MAX_HLS_THUMBNAIL_BYTES - totalBytes
                    if (remaining <= 0) break
                    val bytes = fetchHlsResource(
                        playlistUrl = parsed.url,
                        resource = segment,
                        headers = headers,
                        keyCache = keyCache,
                        maxBytes = remaining,
                    ) ?: break
                    output.write(bytes)
                    totalBytes += bytes.size
                    writtenSegments++
                }
            }
            debugLog(
                "HLS thumbnail: reconstructed $writtenSegments segment(s), $totalBytes bytes",
            )
            writtenSegments > 0 && outFile.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "HLS thumbnail sample reconstruction failed: ${e.message}")
            false
        }
    }

    private fun sameHlsInitializationSection(
        left: Media3HlsMediaPlaylist.Segment,
        right: Media3HlsMediaPlaylist.Segment,
    ): Boolean {
        val leftInit = left.initializationSegment
        val rightInit = right.initializationSegment
        if (leftInit == null || rightInit == null) return leftInit == rightInit
        return leftInit.url == rightInit.url &&
            leftInit.byteRangeOffset == rightInit.byteRangeOffset &&
            leftInit.byteRangeLength == rightInit.byteRangeLength &&
            leftInit.fullSegmentEncryptionKeyUri == rightInit.fullSegmentEncryptionKeyUri &&
            leftInit.encryptionIV == rightInit.encryptionIV
    }

    private fun fetchHlsResource(
        playlistUrl: String,
        resource: Media3HlsMediaPlaylist.SegmentBase,
        headers: Map<String, String>?,
        keyCache: MutableMap<String, ByteArray>,
        maxBytes: Int,
    ): ByteArray? {
        val resourceUrl = URI(playlistUrl).resolve(resource.url).toString()
        val payload = fetchThumbnailBytes(
            url = resourceUrl,
            headers = headers,
            offset = resource.byteRangeOffset,
            length = resource.byteRangeLength,
            maxBytes = maxBytes,
        ) ?: return null

        val keyReference = resource.fullSegmentEncryptionKeyUri ?: return payload
        val iv = resource.encryptionIV ?: return null
        val keyUrl = URI(playlistUrl).resolve(keyReference).toString()
        val key = keyCache[keyUrl] ?: fetchThumbnailBytes(
            url = keyUrl,
            headers = headers,
            offset = 0L,
            length = C.LENGTH_UNSET.toLong(),
            maxBytes = 32,
        )?.also { keyCache[keyUrl] = it } ?: return null
        return decryptHlsAes128(payload, key, iv)
    }

    private fun fetchThumbnailBytes(
        url: String,
        headers: Map<String, String>?,
        offset: Long,
        length: Long,
        maxBytes: Int,
    ): ByteArray? {
        val connection = try {
            openThumbnailConnection(url, headers)
        } catch (e: Exception) {
            Log.w(TAG, "HLS thumbnail resource connection failed: ${e.message}")
            return null
        }
        val range = hlsRangeHeader(offset, length)
        if (range != null) connection.setRequestProperty("Range", range)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            connection.inputStream.use { input ->
                if (range != null && status != HttpURLConnection.HTTP_PARTIAL && offset > 0L) {
                    var remainingSkip = offset
                    while (remainingSkip > 0L) {
                        val skipped = input.skip(remainingSkip)
                        if (skipped <= 0L) {
                            if (input.read() < 0) return null
                            remainingSkip--
                        } else {
                            remainingSkip -= skipped
                        }
                    }
                }

                val expected = length.takeIf { it > 0L } ?: Long.MAX_VALUE
                val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (total < expected) {
                    val allowed = minOf(buffer.size.toLong(), expected - total).toInt()
                    val read = input.read(buffer, 0, allowed)
                    if (read < 0) break
                    if (total + read > maxBytes) return null
                    output.write(buffer, 0, read)
                    total += read
                }
                if (length > 0L && total < length) return null
                output.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS thumbnail resource fetch failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openThumbnailConnection(
        url: String,
        headers: Map<String, String>?,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        headers?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) &&
                PLAYER_SKIP_HEADERS.none { it.equals(key, ignoreCase = true) }) {
                setRequestProperty(key, value)
            }
        }
        if (headers?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }
    }

    /**
     * Downloads up to [maxBytes] of [segmentUrl] into [outFile].
     * Returns true if the file is non-empty after the download.
     */
    private fun downloadSegmentToFile(
        segmentUrl: String,
        headers: Map<String, String>?,
        outFile: File,
        maxBytes: Int = 3 * 1024 * 1024
    ): Boolean {
        return try {
            val conn = URL(segmentUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            headers?.forEach { (k, v) ->
                if (PLAYER_SKIP_HEADERS.none { it.equals(k, ignoreCase = true) }) {
                    conn.setRequestProperty(k, v)
                }
            }
            if (headers?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var totalRead = 0
                    while (totalRead < maxBytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - totalRead))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        totalRead += read
                    }
                }
            }
            outFile.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail media download failed: ${e.message}")
            false
        }
    }

    /**
     * Clear detected videos for a specific tab.
     */
    fun clearTab(tabId: String) {
        clearTabMedia(tabId)
        detectorPageTracker.forget(tabId)
    }

    private fun clearTabMedia(tabId: String) {
        if (tabVideos.containsKey(tabId) || tabSeenUrls.containsKey(tabId)) {
            debugLog("Clearing videos for tab $tabId (had ${tabVideos[tabId]?.size ?: 0})")
        }
        tabVideos.remove(tabId)
        tabSeenUrls.remove(tabId)
        tabLifecycleIndex.remove(tabId)
    }

    /**
     * Clear all detected videos across all tabs.
     */
    fun clear() {
        debugLog("Clearing all detected videos across ${tabVideos.size} tabs")
        tabVideos.clear()
        tabSeenUrls.clear()
        tabLifecycleIndex.clear()
        detectorPageTracker.clear()
        ignoredUrls.clear()
    }

    /**
     * Get count of detected videos across all tabs (for debugging).
     */
    fun getVideoCount(): Int = tabVideos.values.sumOf { it.size }
}
