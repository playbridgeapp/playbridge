package com.playbridge.sender.player

import android.content.Context
import android.content.Intent

/**
 * Launches [PlayerActivity] for the given stream. Safe to call from any [Context];
 * [headers] are only attached when non-empty (resolved addon URLs usually carry none).
 */
object PlayerLauncher {
    fun start(
        context: Context,
        url: String,
        title: String? = null,
        contentType: String? = null,
        headers: Map<String, String>? = null,
        subtitles: List<SubtitleTrack> = emptyList(),
        // Watch-progress identity (movies / single episodes). Omit for untracked playback.
        startPositionMs: Long = 0L,
        /** The tapped thumbnail, to grow the player out of it. */
        origin: PlayerLaunchOrigin? = null,
        /** A cached detector thumbnail (by media URL) to show until the first frame. */
        thumbnailKey: String? = null,
        /** The website's icon, pulsing in place of the spinner while the video loads. */
        siteIconUrl: String? = null,
        /** A Watch Together video: the party controls playback (see [PlayerActivity]). */
        watchTogetherItem: String? = null,
    ) {
        val intent = Intent(context, PlayerActivity.activityClass()).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, contentType)
            if (!headers.isNullOrEmpty()) putExtra(PlayerActivity.EXTRA_HEADERS, HashMap(headers))
            if (subtitles.isNotEmpty()) {
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_URLS, ArrayList(subtitles.map { it.url }))
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_LABELS, ArrayList(subtitles.map { it.label ?: "" }))
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_LANGS, ArrayList(subtitles.map { it.language ?: "" }))
                putStringArrayListExtra(PlayerActivity.EXTRA_SUB_FORMATS, ArrayList(subtitles.map { it.format ?: "" }))
                putExtra(PlayerActivity.EXTRA_SUB_HEADERS, ArrayList(subtitles.map { HashMap(it.headers) }))
            }
            if (startPositionMs > 0) putExtra(PlayerActivity.EXTRA_START_POSITION_MS, startPositionMs)
            thumbnailKey?.let { putExtra(PlayerActivity.EXTRA_THUMBNAIL_KEY, it) }
            siteIconUrl?.let { putExtra(PlayerActivity.EXTRA_SITE_ICON_URL, it) }
            watchTogetherItem?.let { putExtra(PlayerActivity.EXTRA_WATCH_TOGETHER_ITEM, it) }
            origin?.putInto(this)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent, PlayerLaunchOrigin.openOptions(context, origin))
    }

    /**
     * Launches [PlayerActivity] with a playlist of episodes; ExoPlayer auto-advances
     * through them and the player exits when the last one ends. Used for series.
     */
    fun startPlaylist(
        context: Context,
        urls: List<String>,
        titles: List<String>,
        startIndex: Int,
        // Watch-progress identity (series). seasons/episodes are parallel to urls.
        startPositionMs: Long = 0L,
    ) {
        if (urls.isEmpty()) return
        val intent = Intent(context, PlayerActivity.activityClass()).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URLS, ArrayList(urls))
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(titles))
            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
        }
        context.startActivity(intent, PlayerLaunchOrigin.openOptions(context, null))
    }


}
