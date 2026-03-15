package com.playbridge.sender.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OmdbResponse(
    @SerialName("Title") val title: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("Ratings") val ratings: List<OmdbRating> = emptyList(),
    @SerialName("Response") val response: String = "False"
) {
    val rottenTomatoesRating: String?
        get() = ratings.find { it.source == "Rotten Tomatoes" }?.value
}

@Serializable
data class OmdbRating(
    @SerialName("Source") val source: String,
    @SerialName("Value") val value: String
)
