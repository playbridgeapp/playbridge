package com.playbridge.sender.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Filter options + request model for the TMDB Discover endpoints.
 * Mirrors https://developer.themoviedb.org/reference/discover-movie and /discover-tv.
 */

// ==================== Genres (media-aware) ====================

/**
 * A genre as shown in the Discover filter. TMDB uses different genre IDs for movies
 * and TV (e.g. movie "Action"=28 vs TV "Action & Adventure"=10759), so each option
 * carries the correct id per media type. A null id means the genre does not exist for
 * that media type and is simply omitted from that query.
 */
data class DiscoverGenre(
    val name: String,
    val movieId: Int?,
    val tvId: Int?
)

object TmdbDiscoverGenres {
    val list = listOf(
        DiscoverGenre("Action", 28, 10759),          // TV: Action & Adventure
        DiscoverGenre("Adventure", 12, 10759),       // TV: Action & Adventure
        DiscoverGenre("Animation", 16, 16),
        DiscoverGenre("Comedy", 35, 35),
        DiscoverGenre("Crime", 80, 80),
        DiscoverGenre("Documentary", 99, 99),
        DiscoverGenre("Drama", 18, 18),
        DiscoverGenre("Family", 10751, 10751),
        DiscoverGenre("Fantasy", 14, 10765),         // TV: Sci-Fi & Fantasy
        DiscoverGenre("History", 36, null),
        DiscoverGenre("Horror", 27, null),
        DiscoverGenre("Kids", null, 10762),
        DiscoverGenre("Music", 10402, null),
        DiscoverGenre("Mystery", 9648, 9648),
        DiscoverGenre("News", null, 10763),
        DiscoverGenre("Reality", null, 10764),
        DiscoverGenre("Romance", 10749, null),
        DiscoverGenre("Sci-Fi", 878, 10765),         // TV: Sci-Fi & Fantasy
        DiscoverGenre("Soap", null, 10766),
        DiscoverGenre("Talk", null, 10767),
        DiscoverGenre("Thriller", 53, null),
        DiscoverGenre("War", 10752, 10768),          // TV: War & Politics
        DiscoverGenre("Western", 37, 37),
        DiscoverGenre("TV Movie", 10770, null)
    )

    /** Joins the movie genre ids for [names] using AND (",") or OR ("|"). */
    fun movieGenreParam(names: Set<String>, matchAll: Boolean): String? =
        list.filter { it.name in names }.mapNotNull { it.movieId }
            .distinct()
            .joinToString(if (matchAll) "," else "|")
            .ifBlank { null }

    /** Joins the TV genre ids for [names] using AND (",") or OR ("|"). */
    fun tvGenreParam(names: Set<String>, matchAll: Boolean): String? =
        list.filter { it.name in names }.mapNotNull { it.tvId }
            .distinct()
            .joinToString(if (matchAll) "," else "|")
            .ifBlank { null }
}

// ==================== Sort ====================

/** A single Discover sort option, with the api value mapped per media type. */
data class DiscoverSort(
    val label: String,
    val movieValue: String,
    val tvValue: String
)

object TmdbDiscoverSorts {
    val list = listOf(
        DiscoverSort("Popular", "popularity.desc", "popularity.desc"),
        DiscoverSort("Top Rated", "vote_average.desc", "vote_average.desc"),
        DiscoverSort("Most Voted", "vote_count.desc", "vote_count.desc"),
        DiscoverSort("Newest", "primary_release_date.desc", "first_air_date.desc"),
        DiscoverSort("Oldest", "primary_release_date.asc", "first_air_date.asc"),
        DiscoverSort("Revenue", "revenue.desc", "popularity.desc"), // revenue is movie-only
        DiscoverSort("A–Z", "title.asc", "name.asc"),
        DiscoverSort("Z–A", "title.desc", "name.desc")
    )

    /** Sort options applicable to the given media type (Revenue is movie-only). */
    fun forMovieOnly(label: String) = label == "Revenue"
}

// ==================== Origin country ====================

data class DiscoverCountry(val code: String, val name: String)

object TmdbDiscoverCountries {
    val list = listOf(
        DiscoverCountry("US", "United States"),
        DiscoverCountry("GB", "United Kingdom"),
        DiscoverCountry("IN", "India"),
        DiscoverCountry("CA", "Canada"),
        DiscoverCountry("AU", "Australia"),
        DiscoverCountry("FR", "France"),
        DiscoverCountry("DE", "Germany"),
        DiscoverCountry("ES", "Spain"),
        DiscoverCountry("IT", "Italy"),
        DiscoverCountry("JP", "Japan"),
        DiscoverCountry("KR", "South Korea"),
        DiscoverCountry("CN", "China"),
        DiscoverCountry("BR", "Brazil"),
        DiscoverCountry("MX", "Mexico"),
        DiscoverCountry("RU", "Russia")
    )
}

/** Regions used for watch-provider filtering (watch_region). */
object TmdbWatchRegions {
    val list = listOf(
        DiscoverCountry("US", "United States"),
        DiscoverCountry("GB", "United Kingdom"),
        DiscoverCountry("IN", "India"),
        DiscoverCountry("CA", "Canada"),
        DiscoverCountry("AU", "Australia"),
        DiscoverCountry("FR", "France"),
        DiscoverCountry("DE", "Germany"),
        DiscoverCountry("ES", "Spain"),
        DiscoverCountry("IT", "Italy"),
        DiscoverCountry("JP", "Japan"),
        DiscoverCountry("KR", "South Korea"),
        DiscoverCountry("BR", "Brazil")
    )
}

// ==================== Movie certification (US ratings) ====================

object TmdbMovieCertifications {
    const val COUNTRY = "US"
    val list = listOf("G", "PG", "PG-13", "R", "NC-17")
}

/** Movie release types (with_release_type), TMDB values 1–6. */
data class DiscoverReleaseType(val value: Int, val label: String)

object TmdbReleaseTypes {
    val list = listOf(
        DiscoverReleaseType(1, "Premiere"),
        DiscoverReleaseType(2, "Theatrical (limited)"),
        DiscoverReleaseType(3, "Theatrical"),
        DiscoverReleaseType(4, "Digital"),
        DiscoverReleaseType(5, "Physical"),
        DiscoverReleaseType(6, "TV")
    )
}

// ==================== TV status / type ====================

data class DiscoverTvStatus(val value: Int, val label: String)

object TmdbTvStatuses {
    val list = listOf(
        DiscoverTvStatus(0, "Returning"),
        DiscoverTvStatus(1, "Planned"),
        DiscoverTvStatus(2, "In Production"),
        DiscoverTvStatus(3, "Ended"),
        DiscoverTvStatus(4, "Cancelled"),
        DiscoverTvStatus(5, "Pilot")
    )
}

data class DiscoverTvType(val value: Int, val label: String)

object TmdbTvTypes {
    val list = listOf(
        DiscoverTvType(0, "Documentary"),
        DiscoverTvType(1, "News"),
        DiscoverTvType(2, "Miniseries"),
        DiscoverTvType(3, "Reality"),
        DiscoverTvType(4, "Scripted"),
        DiscoverTvType(5, "Talk Show"),
        DiscoverTvType(6, "Video")
    )
}

/** Watch-provider monetization types (with_watch_monetization_types). */
object TmdbMonetizationTypes {
    val list = listOf("flatrate", "free", "ads", "rent", "buy")
}

// ==================== Request model ====================

/**
 * All Discover query parameters. The ViewModel builds one of these per media type
 * (movie / TV) because genre ids and a few params differ between them.
 */
data class DiscoverFilters(
    val sortBy: String = "popularity.desc",
    val includeAdult: Boolean = false,
    val withGenres: String? = null,
    val withoutGenres: String? = null,
    val withOriginalLanguage: String? = null,
    val withOriginCountry: String? = null,
    val voteAverageGte: Double? = null,
    val voteAverageLte: Double? = null,
    val voteCountGte: Int? = null,
    val runtimeGte: Int? = null,
    val runtimeLte: Int? = null,
    val withKeywords: String? = null,
    val withoutKeywords: String? = null,
    val dateGte: String? = null,            // yyyy-MM-dd
    val dateLte: String? = null,            // yyyy-MM-dd
    val watchRegion: String? = null,
    val withWatchProviders: String? = null,
    val withWatchMonetizationTypes: String? = null,
    // Movie-only
    val certificationCountry: String? = null,
    val certification: String? = null,
    val withReleaseType: String? = null,
    // TV-only
    val withStatus: String? = null,
    val withType: String? = null
)

// ==================== Watch-provider list response ====================

@Serializable
data class TmdbWatchProviderListResponse(
    val results: List<TmdbWatchProvider> = emptyList()
)
