package com.playbridge.sender.cast.remote

/** Presentation only. Never use this output for identity, queue matching or subtitle discovery. */
object RemoteTitlePolicy {
    private val extension = Regex("\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mp3|flac|ogg|wav|jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE)
    private val release = Regex("(?i)(?:^|[ ._\\-\\[\\]()])(?:480p|576p|720p|1080[pi]|1440p|2160p|4320p|[xh][ ._-]?26[45]|hevc|avc|web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|remux|aac[ ._-]?[257](?:[ ._]1)?|dts[ ._-]?hd)(?=$|[ ._\\-\\[\\]()])")
    private val filenameSeparators = Regex("[._]+")
    private val whitespace = Regex("\\s+")
    private val episodeCode = Regex("(?i)(?<![A-Za-z0-9])S(\\d+)[ ._·-]*E(\\d+)(?![A-Za-z0-9])")
    private val episodeOnly = Regex("(?i)\\b(?:E|Episode\\s*)(\\d+)(?![A-Za-z0-9])")

    private fun matchingEpisode(title: String, season: Int?, episode: Int): MatchResult? =
        episodeCode.findAll(title).firstOrNull {
            (season == null || it.groupValues[1].toIntOrNull() == season) && it.groupValues[2].toIntOrNull() == episode
        }

    /** The badge owns the episode code; the part after it is the episode title. */
    fun episodeTitle(item: RemoteQueueItem): String {
        val title = clean(item.title).orEmpty().ifBlank { "Episode ${item.index + 1}" }
        val season = item.season ?: return title
        val episode = item.episode ?: return title
        val code = matchingEpisode(title, season, episode) ?: return title
        val suffix = title.substring(code.range.last + 1).trimStart(' ', '.', '_', '·', '-', '–', '—', ':', '|', ')', ']')
        if (suffix.isNotBlank()) return suffix
        // A trailing code has no show/episode delimiter; keep the meaningful prefix.
        return title.substring(0, code.range.first).trimEnd(' ', '.', '_', '·', '-', '–', '—', ':', '|', '(', '[')
            .ifBlank { "Episode" }
    }

    private val pageUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /**
     * Browser info line. A reported page title is shown as itself. A title that is only
     * an http(s) URL is shown as its host. Blank means the info card has no title line.
     */
    fun browserHeading(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (pageUrl.matches(trimmed)) {
            val host = runCatching { java.net.URI(trimmed).host }.getOrNull()?.takeIf { it.isNotBlank() }
            if (host != null) return host
        }
        return clean(trimmed)
    }

    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val filename = extension.containsMatchIn(raw) || raw.contains('_') ||
            (raw.count { it == '.' } >= 2 && release.containsMatchIn(raw))
        if (!filename) return raw
        var display = raw.replace(extension, "")
        // Repeated passes retain boundary separators shared by adjacent release tokens.
        repeat(3) { display = display.replace(release, " ") }
        display = display.replace(filenameSeparators, " ").replace(Regex("\\[\\s*]|\\(\\s*\\)"), " ")
            .replace(whitespace, " ").trim(' ', '-', '[', ']', '(', ')')
        return display.takeIf { it.isNotBlank() } ?: raw
    }

    fun subtitle(state: RemoteUiState): String? {
        if (state.context == RemoteContext.Browser) return null
        val playback = state.playback ?: return null
        if (playback.mediaKind == RemoteMediaKind.Image) return "Image"
        val current = state.queue?.let { queue -> queue.items.firstOrNull {
            if (queue.currentItemId != null) it.itemId == queue.currentItemId else it.index == queue.currentIndex
        } }
        val episode = current?.episode
        if (episode != null) {
            val title = clean(playback.title).orEmpty()
            val alreadyShown = matchingEpisode(title, current.season, episode) != null ||
                current.season == null && episodeOnly.findAll(title).any { it.groupValues[1].toIntOrNull() == episode }
            if (!alreadyShown) return current.season?.let { "S${it}E$episode" } ?: "Episode $episode"
        }
        return when {
            playback.isLive -> "Live"
            playback.displayedState == RemotePlaybackState.Paused -> "Paused"
            playback.displayedState == RemotePlaybackState.Buffering -> "Buffering"
            else -> null
        }
    }
}
