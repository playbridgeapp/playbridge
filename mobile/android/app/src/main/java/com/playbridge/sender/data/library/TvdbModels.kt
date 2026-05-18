package com.playbridge.sender.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Auth
@Serializable data class TvdbLoginRequest(val apikey: String)
@Serializable data class TvdbAuthResponse(val status: String = "", val data: TvdbAuthData? = null)
@Serializable data class TvdbAuthData(val token: String = "")

// Search
@Serializable data class TvdbSearchResponse(val data: List<TvdbSearchResult> = emptyList())
@Serializable
data class TvdbSearchResult(
    @SerialName("tvdb_id")  val tvdbId: String  = "",
    @SerialName("imdb_id")  val imdbId: String? = null,
    val name:     String  = "",
    val type:     String  = "",   // "series", "movie"
    val image:    String? = null,
    val overview: String? = null,
    val year:     String? = null
)

// Series
@Serializable data class TvdbSeriesResponse(val data: TvdbSeries? = null)
@Serializable
data class TvdbSeries(
    val id:         Int     = 0,
    val name:       String  = "",
    val overview:   String? = null,
    val image:      String? = null,   // full poster URL
    @SerialName("firstAired") val firstAired: String? = null,
    val status:     TvdbStatus?        = null,
    val artworks:   List<TvdbArtwork>  = emptyList()
)
@Serializable data class TvdbStatus(val name: String? = null)
@Serializable
data class TvdbArtwork(
    val type:  Int    = 0,    // 1=poster, 3=background/fanart
    val image: String = "",
    val score: Double = 0.0
)

// Episodes (official ordering, paginated — 500 per page)
@Serializable
data class TvdbEpisodesResponse(
    val data:  TvdbEpisodesData? = null,
    val links: TvdbLinks?        = null
)
@Serializable
data class TvdbEpisodesData(
    val series:   TvdbSeries?       = null,
    val episodes: List<TvdbEpisode> = emptyList()
)
@Serializable
data class TvdbLinks(
    val next:  String? = null,
    @SerialName("total_items") val totalItems: Int = 0
)
@Serializable
data class TvdbEpisode(
    val id:             Int     = 0,
    @SerialName("seasonNumber")   val seasonNumber:   Int     = 0,
    @SerialName("number")         val number:         Int     = 0,
    @SerialName("absoluteNumber") val absoluteNumber: Int?    = null,
    val name:      String? = null,
    val overview:  String? = null,
    val image:     String? = null,   // full thumbnail URL
    val aired:     String? = null,
    val runtime:   Int?    = null
) {
    /** Convert to a StremioVideo for use as addonMeta.videos. */
    fun toStremioVideo(): StremioVideo = StremioVideo(
        id        = "$seasonNumber:$number",
        title     = name ?: "Episode $number",
        season    = seasonNumber,
        episode   = number,
        released  = aired,
        thumbnail = image,
        overview  = overview
    )
}
