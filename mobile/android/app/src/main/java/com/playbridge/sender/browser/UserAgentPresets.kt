package com.playbridge.sender.browser

/**
 * A selectable User-Agent preset for the in-app browser.
 *
 * [value] is the literal UA string sent on every request; `null` means "no
 * override" — i.e. defer to GeckoView's own default (mobile) identity, or to
 * the separate "Desktop Site" quick-toggle in the menu (which uses Android
 * Components' `toggleDesktopMode()` machinery rather than a literal string
 * set here). Only the [UserAgentPresets.DEFAULT_ID] preset uses `null`; every
 * other built-in preset carries a real string once applied.
 */
data class UserAgentPreset(
    val id: String,
    val label: String,
    val value: String?,
)

/** A user-saved, named User-Agent string. Persisted via SettingsRepository. */
data class CustomUserAgent(
    val id: String,
    val name: String,
    val value: String,
)

object UserAgentPresets {
    const val DEFAULT_ID = "default"

    /** Selection ids for saved custom entries are prefixed with this so they can never collide with a built-in preset id. */
    private const val CUSTOM_PREFIX = "custom:"

    fun customSelectionId(customAgentId: String): String = "$CUSTOM_PREFIX$customAgentId"

    fun isCustomSelectionId(selectionId: String): Boolean = selectionId.startsWith(CUSTOM_PREFIX)

    fun customAgentIdFrom(selectionId: String): String = selectionId.removePrefix(CUSTOM_PREFIX)

    /** Selectable built-in presets, in display order. */
    val presets: List<UserAgentPreset> = listOf(
        UserAgentPreset(DEFAULT_ID, "Default (Mobile)", null),
        UserAgentPreset(
            "chrome_android",
            "Chrome — Android",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        ),
        UserAgentPreset(
            "chrome_windows",
            "Chrome — Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        ),
        UserAgentPreset(
            "chrome_macos",
            "Chrome — macOS",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        ),
        UserAgentPreset(
            "firefox_android",
            "Firefox — Android",
            "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0",
        ),
        UserAgentPreset(
            "firefox_windows",
            "Firefox — Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        ),
        UserAgentPreset(
            "safari_ios",
            "Safari — iPhone",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        ),
        UserAgentPreset(
            "safari_macos",
            "Safari — macOS",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        ),
        UserAgentPreset(
            "samsung_internet",
            "Samsung Internet — Android",
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36",
        ),
        UserAgentPreset(
            "googlebot",
            "Googlebot",
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        ),
    )

    /**
     * Resolve the saved selection (a built-in preset id, or a `custom:<id>`
     * selection id) into the literal UA string to apply, or null for "no
     * override" (default mobile / desktop-toggle UA).
     */
    fun resolve(selectionId: String, customAgents: List<CustomUserAgent>): String? {
        if (isCustomSelectionId(selectionId)) {
            val agentId = customAgentIdFrom(selectionId)
            return customAgents.find { it.id == agentId }?.value
        }
        return presets.find { it.id == selectionId }?.value
    }
}
