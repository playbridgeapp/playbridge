package com.playbridge.sender.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== TMDB API Responses ====================

@Serializable
data class TmdbPagedResponse<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0
)

// ==================== Movie Models ====================

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String = "",
    val popularity: Double = 0.0,
    val adult: Boolean = false,
    @SerialName("media_type") val mediaType: String? = null
) {
    val year: String get() = releaseDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    val rating: String get() = String.format("%.1f", voteAverage)

    /** IMDB-style ID for Stremio addon lookups (fetched separately from movie details) */
    @kotlinx.serialization.Transient
    var imdbId: String? = null
}

// ==================== TV Show Models ====================

@Serializable
data class TmdbTvShow(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String = "",
    val popularity: Double = 0.0,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("media_type") val mediaType: String? = null
) {
    val year: String get() = firstAirDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    val rating: String get() = String.format("%.1f", voteAverage)

    @kotlinx.serialization.Transient
    var imdbId: String? = null
}

// ==================== Movie Details (includes IMDB ID) ====================

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("imdb_id") val imdbId: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val tagline: String = ""
) {
    val year: String get() = releaseDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    val rating: String get() = String.format("%.1f", voteAverage)
    val runtimeFormatted: String get() {
        val r = runtime ?: return ""
        return "${r / 60}h ${r % 60}m"
    }
}

// ==================== TV Show Details ====================

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeason> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val tagline: String = ""
) {
    val year: String get() = firstAirDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    val rating: String get() = String.format("%.1f", voteAverage)
    val imdbId: String? get() = externalIds?.imdbId
}

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null
)

// ==================== Season & Episode ====================

@Serializable
data class TmdbSeason(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode>? = null
) {
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

@Serializable
data class TmdbEpisode(
    val id: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null
) {
    val stillUrl: String? get() = stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
}

// ==================== Common ====================

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String = ""
)

// ==================== Multi-Search Result ====================

/**
 * Multi-search can return movies or TV shows — differentiated by media_type field.
 * We use a unified wrapper and parse accordingly.
 */
@Serializable
data class TmdbMultiSearchResult(
    val id: Int,
    @SerialName("media_type") val mediaType: String = "",
    // Movie fields
    val title: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    // TV fields
    val name: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    // Shared fields
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String = "",
    val popularity: Double = 0.0
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val isMovie: Boolean get() = mediaType == "movie"
    val isTvShow: Boolean get() = mediaType == "tv"
}
