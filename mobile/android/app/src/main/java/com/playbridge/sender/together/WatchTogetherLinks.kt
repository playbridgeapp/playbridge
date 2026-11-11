package com.playbridge.sender.together

/** Recognises Watch Together invite links: `https://<relay>/j/<room>#k=<key>&h=<host key>`. */
object WatchTogetherLinks {
    private val invite = Regex("""^https?://[^/?#]+(/[^?#]*)?/j/[0-9a-f]{32}#(?=.*\bk=)(?=.*\bh=).+$""")
    private val handedOver = Regex("""^(https?://[^/?#]+(?:/[^?#]*)?/j/[0-9a-f]{32})\?([^#]*\bk=[^#]*)$""")

    /**
     * The invite in [link], or null for other links. The relay's invite page hands the
     * link to the app with the key in the query (Android intents drop fragments); it
     * moves back to the fragment here.
     */
    fun invite(link: String): String? {
        val trimmed = link.trim()
        if (invite.matches(trimmed)) return trimmed
        val match = handedOver.matchEntire(trimmed) ?: return null
        return "${match.groupValues[1]}#${match.groupValues[2]}".takeIf(invite::matches)
    }

    fun isInvite(link: String): Boolean = invite(link) != null
}
