package com.playbridge.sender.data.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Images & Logos ====================

@Serializable
data class TmdbImages(
    val logos: List<TmdbLogo> = emptyList()
)

@Serializable
data class TmdbLogo(
    @SerialName("file_path") val filePath: String
) {
    val logoUrl: String get() = "https://image.tmdb.org/t/p/w500$filePath"
}

// ==================== Credits (Cast/Crew) ====================

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Serializable
data class TmdbCast(
    val name: String,
    @SerialName("known_for_department") val knownForDepartment: String = ""
)

@Serializable
data class TmdbCrew(
    val name: String,
    val job: String
)

// ==================== Content Ratings ====================

@Serializable
data class TmdbMovieReleaseDates(
    val results: List<TmdbMovieReleaseDateResult> = emptyList()
)

@Serializable
data class TmdbMovieReleaseDateResult(
    @SerialName("iso_3166_1") val iso31661: String,
    @SerialName("release_dates") val releaseDates: List<TmdbMovieReleaseDateItem> = emptyList()
)

@Serializable
data class TmdbMovieReleaseDateItem(
    val certification: String = ""
)

@Serializable
data class TmdbTvContentRatings(
    val results: List<TmdbTvContentRatingResult> = emptyList()
)

@Serializable
data class TmdbTvContentRatingResult(
    @SerialName("iso_3166_1") val iso31661: String,
    val rating: String = ""
)

// ==================== Videos / Trailers ====================

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val official: Boolean = false
) {
    val youtubeUrl: String? get() = if (site == "YouTube") "https://www.youtube.com/watch?v=$key" else null
}

@Serializable
data class TmdbVideoResult(
    val results: List<TmdbVideo> = emptyList()
) {
    /** Best YouTube trailer URL: official trailers first, then any YouTube clip. */
    val bestTrailerUrl: String? get() = results
        .filter { it.site == "YouTube" && it.type == "Trailer" }
        .maxByOrNull { if (it.official) 1 else 0 }
        ?.youtubeUrl
        ?: results.firstOrNull { it.site == "YouTube" }?.youtubeUrl
}

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
    val tagline: String = "",
    val credits: TmdbCredits? = null,
    val images: TmdbImages? = null,
    val release_dates: TmdbMovieReleaseDates? = null
) {
    val year: String get() = releaseDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
    val rating: String get() = String.format("%.1f", voteAverage)
    val runtimeFormatted: String get() {
        val r = runtime ?: return ""
        return "${r / 60}h ${r % 60}m"
    }
    
    val logoUrl: String? get() = images?.logos?.firstOrNull()?.logoUrl
    val certification: String get() {
        val usDates = release_dates?.results?.firstOrNull { it.iso31661 == "US" }
        return usDates?.releaseDates?.firstOrNull { it.certification.isNotBlank() }?.certification ?: ""
    }
    val director: String get() = credits?.crew?.firstOrNull { it.job == "Director" }?.name ?: ""
    val cast: List<String> get() = credits?.cast?.take(6)?.map { it.name } ?: emptyList()
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
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbNextEpisode? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeason> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val tagline: String = "",
    val credits: TmdbCredits? = null,
    val images: TmdbImages? = null,
    val content_ratings: TmdbTvContentRatings? = null
) {
    val year: String get() = firstAirDate.take(4)
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
    val rating: String get() = String.format("%.1f", voteAverage)
    val imdbId: String? get() = externalIds?.imdbId
    
    val logoUrl: String? get() = images?.logos?.firstOrNull()?.logoUrl
    val certification: String get() {
        return content_ratings?.results?.firstOrNull { it.iso31661 == "US" }?.rating ?: ""
    }
    val cast: List<String> get() = credits?.cast?.take(6)?.map { it.name } ?: emptyList()
}

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int?    = null
)

@Serializable
data class TmdbNextEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
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
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    val isMovie: Boolean get() = mediaType == "movie"
    val isTvShow: Boolean get() = mediaType == "tv"
}


// ==================== Watch Providers ====================

@Serializable
data class TmdbWatchProvider(
    @SerialName("provider_id") val providerId: Int,
    @SerialName("provider_name") val providerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val displayPriority: Int = 0
) {
    val logoUrl: String? get() = logoPath?.let { "https://image.tmdb.org/t/p/w45$it" }
}

@Serializable
data class TmdbWatchProvidersRegion(
    val link: String = "",
    val flatrate: List<TmdbWatchProvider> = emptyList(),
    val rent: List<TmdbWatchProvider> = emptyList(),
    val buy: List<TmdbWatchProvider> = emptyList()
)

@Serializable
data class TmdbWatchProvidersResponse(
    val results: Map<String, TmdbWatchProvidersRegion> = emptyMap()
)

// ==================== Common Genres ====================

/**
 * Predefined list of common TMDB genres for the Discovery UI.
 * Note: TV and Movie sometimes use different IDs for certain genres (e.g. Sci-Fi & Fantasy),
 * but these are generally safe for both or at least movies.
 */
object TmdbCommonGenres {
    val list = listOf(
        TmdbGenre(28, "Action"),
        TmdbGenre(12, "Adventure"),
        TmdbGenre(16, "Animation"),
        TmdbGenre(35, "Comedy"),
        TmdbGenre(80, "Crime"),
        TmdbGenre(99, "Documentary"),
        TmdbGenre(18, "Drama"),
        TmdbGenre(10751, "Family"),
        TmdbGenre(14, "Fantasy"),
        TmdbGenre(36, "History"),
        TmdbGenre(27, "Horror"),
        TmdbGenre(10402, "Music"),
        TmdbGenre(9648, "Mystery"),
        TmdbGenre(10749, "Romance"),
        TmdbGenre(878, "Science Fiction"),
        TmdbGenre(10770, "TV Movie"),
        TmdbGenre(53, "Thriller"),
        TmdbGenre(10752, "War"),
        TmdbGenre(37, "Western")
    )
}

// ==================== Find by external ID (IMDb) ====================

@Serializable
data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbMovie> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbTvShow> = emptyList()
)
