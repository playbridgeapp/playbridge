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
}
