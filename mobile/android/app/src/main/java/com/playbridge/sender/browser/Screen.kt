package com.playbridge.sender.browser

/**
 * Navigation destinations for the single-Activity app shell ([BrowserActivity]).
 *
 * Lives in the `browser` package because the shell host does; screens in other
 * feature packages import it via `com.playbridge.sender.browser.Screen`.
 */
sealed class Screen {
    object Browser : Screen()
    object Tabs : Screen()
    object Extensions : Screen()
    object Connection : Screen()
    object Downloads : Screen()
    object Settings : Screen()
    object History : Screen()
    object CastHistory : Screen()
    object Bookmarks : Screen()
    object Home : Screen()
    object Remote : Screen()
    object Library : Screen()
    object DebridLibrary : Screen()
    object AddonSettings : Screen()
    data class LibraryDetail(val id: String, val type: String, val source: String? = null) : Screen()
    object Dashboard : Screen()
    object PhoneFiles : Screen()
    object Iptv : Screen()
    data class IptvDetail(val playlistId: Long) : Screen()
    object Collections : Screen()
    data class CollectionDetail(val collectionId: Long) : Screen()

    companion object {
        /**
         * Saver so the current screen survives Activity recreation (e.g. the shell being
         * destroyed for memory while the in-app player sits in front of it — without this,
         * Back from the player landed on the default Browser screen instead of where the
         * user actually was, like a library detail page).
         */
        val Saver: androidx.compose.runtime.saveable.Saver<Screen, String> =
            androidx.compose.runtime.saveable.Saver(
                save = { screen ->
                    when (screen) {
                        Browser -> "browser"
                        Tabs -> "tabs"
                        Extensions -> "extensions"
                        Connection -> "connection"
                        Downloads -> "downloads"
                        Settings -> "settings"
                        History -> "history"
                        CastHistory -> "casthistory"
                        Bookmarks -> "bookmarks"
                        Home -> "home"
                        Remote -> "remote"
                        Library -> "library"
                        DebridLibrary -> "debridlibrary"
                        AddonSettings -> "addonsettings"
                        Dashboard -> "dashboard"
                        PhoneFiles -> "phonefiles"
                        Iptv -> "iptv"
                        Collections -> "collections"
                        is LibraryDetail -> "librarydetail|${screen.id}|${screen.type}|${screen.source ?: ""}"
                        is IptvDetail -> "iptvdetail|${screen.playlistId}"
                        is CollectionDetail -> "collectiondetail|${screen.collectionId}"
                    }
                },
                restore = { value ->
                    val parts = value.split("|")
                    when (parts[0]) {
                        "browser" -> Browser
                        "tabs" -> Tabs
                        "extensions" -> Extensions
                        "connection" -> Connection
                        "downloads" -> Downloads
                        "settings" -> Settings
                        "history" -> History
                        "casthistory" -> CastHistory
                        "bookmarks" -> Bookmarks
                        "home" -> Home
                        "remote" -> Remote
                        "library" -> Library
                        "debridlibrary" -> DebridLibrary
                        "addonsettings" -> AddonSettings
                        "dashboard" -> Dashboard
                        "phonefiles" -> PhoneFiles
                        "iptv" -> Iptv
                        "collections" -> Collections
                        "librarydetail" -> parts.getOrNull(1)?.let { id ->
                            LibraryDetail(
                                id = id,
                                type = parts.getOrNull(2) ?: "movie",
                                source = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
                            )
                        } ?: Browser
                        "iptvdetail" -> parts.getOrNull(1)?.toLongOrNull()
                            ?.let { IptvDetail(it) } ?: Browser
                        "collectiondetail" -> parts.getOrNull(1)?.toLongOrNull()
                            ?.let { CollectionDetail(it) } ?: Browser
                        else -> Browser
                    }
                },
            )
    }
}
