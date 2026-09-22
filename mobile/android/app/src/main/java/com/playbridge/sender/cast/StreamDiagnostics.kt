package com.playbridge.sender.cast

import android.util.Log
import com.playbridge.sender.BuildConfig

/** Debug logcat only. No URL paths, query strings, cookies, headers, or persisted output. */
internal object StreamDiagnostics {
    private const val TAG = "PBStream"
    private val rankings = LinkedHashMap<String, String>()

    fun id(url: String): String = Integer.toUnsignedString(url.hashCode(), 16)

    inline fun event(name: String, detail: () -> String) {
        if (BuildConfig.DEBUG) write(name, detail())
    }

    fun write(name: String, detail: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$name $detail")
    }

    fun ranking(context: String, videos: List<DetectedVideo>) {
        if (!BuildConfig.DEBUG) return
        val ranked = buildCastSheetVideos(videos)
        val newestMs = ranked.maxOfOrNull { maxOf(it.timestamp, it.lastSeen) } ?: 0L
        val signature = "count=${ranked.size} failed=${ranked.count { it.effectiveValidationState == MediaValidationState.FAILED }} " +
            ranked.take(8).mapIndexed { index, video ->
                "${index + 1}:${id(video.url)}(score=${video.castScore()}," +
                    "state=${video.effectiveValidationState},by=${video.detectedBy}," +
                    "life=${video.lifecycleIndex},ageSec=${(newestMs - maxOf(video.timestamp, video.lastSeen)) / 1000}," +
                    "qualities=${video.qualities.size},preview=${video.thumbnailState})"
            }.joinToString(" ")
        synchronized(rankings) {
            if (rankings[context] == signature) return
            if (rankings.size >= 32 && context !in rankings) rankings.remove(rankings.keys.first())
            rankings[context] = signature
        }
        write("rank", "context=$context $signature")
    }
}
