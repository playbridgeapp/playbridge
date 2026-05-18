package com.playbridge.sender.browser

enum class LibraryMediaType {
    ALL,
    MOVIE,
    TV_SHOW
}

enum class LibrarySortBy(val apiValue: String) {
    POPULARITY_DESC("popularity.desc"),
    PRIMARY_RELEASE_DATE_DESC("primary_release_date.desc")
}
