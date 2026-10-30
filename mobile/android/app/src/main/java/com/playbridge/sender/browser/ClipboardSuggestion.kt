package com.playbridge.sender.browser

sealed interface ClipboardSuggestion {
    data class Link(val url: String) : ClipboardSuggestion
    data class Text(val query: String) : ClipboardSuggestion
}

object ClipboardClassifier {
    const val MAX_INPUT_LENGTH = 2000
    const val MAX_TEXT_QUERY_LENGTH = 200

    private val WHITESPACE_REGEX = Regex("\\s+")

    // Common file extensions that look like multi-part extensions or file names, not web domains
    private val NON_TLD_EXTENSIONS = setOf(
        "gz", "tar", "tgz", "bz2", "xz", "zst", "7z", "zip", "rar",
        "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
        "mp3", "mp4", "mkv", "avi", "mov", "wav", "flac", "ogg", "m4a",
        "apk", "aab", "exe", "dmg", "pkg", "deb", "rpm", "msi", "iso",
        "bin", "cue", "rom", "jar", "aar",
        "js", "ts", "json", "xml", "html", "htm", "css", "scss",
        "kt", "java", "py", "c", "cpp", "h", "rs", "go", "rb", "php",
        "sh", "yaml", "yml", "toml", "sql", "db", "log", "md"
    )

    // localhost or localhost:port with optional path/query/fragment
    private val LOCALHOST_REGEX = Regex(
        "^localhost(:[0-9]{1,5})?([/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    // IPv4: 4 octets (0-255) + optional :port + optional path/query/fragment
    private val IPV4_REGEX = Regex(
        "^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(:[0-9]{1,5})?([/?#].*)?$"
    )

    // IPv6: [::1] etc. + optional :port + optional path/query/fragment
    private val IPV6_REGEX = Regex(
        "^\\[[0-9a-fA-F:]+\\](:[0-9]{1,5})?([/?#].*)?$"
    )

    // Domain name: labels separated by dots, TLD is 2-63 alphabetic chars, optional :port, optional path/query/fragment
    private val DOMAIN_REGEX = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+([a-zA-Z]{2,63})(:[0-9]{1,5})?([/?#].*)?$"
    )

    fun classify(raw: CharSequence?): ClipboardSuggestion? {
        if (raw == null || raw.length > MAX_INPUT_LENGTH) {
            return null
        }

        val collapsed = WHITESPACE_REGEX.replace(raw, " ").trim()
        if (collapsed.isEmpty() || collapsed.length > MAX_INPUT_LENGTH) {
            return null
        }

        // Single-token link candidate (no spaces allowed)
        if (!collapsed.contains(' ')) {
            val candidate = stripTrailingPunctuation(collapsed)
            if (candidate.isNotEmpty()) {
                val linkUrl = checkLink(candidate)
                if (linkUrl != null) {
                    return ClipboardSuggestion.Link(linkUrl)
                }
                if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(candidate)) return null
            }
        }

        // Text query fallback (multi-word, sentences, or non-link single tokens) capped to 200 chars
        val query = collapsed.take(MAX_TEXT_QUERY_LENGTH)
        return if (query.isNotEmpty()) ClipboardSuggestion.Text(query) else null
    }

    private fun checkLink(candidate: String): String? {
        // Scheme present: http://, https://, about:
        if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            val schemeEnd = candidate.indexOf("://") + 3
            val rest = candidate.substring(schemeEnd)
            return if (rest.isNotEmpty() && !rest.startsWith("/") && !rest.startsWith("?") && !rest.startsWith("#")) {
                candidate
            } else {
                null
            }
        }

        if (candidate.startsWith("about:", ignoreCase = true)) {
            val rest = candidate.substring("about:".length)
            return if (rest.isNotEmpty()) candidate else null
        }

        // Schemeless: check localhost, IPv4, IPv6, domain name
        if (LOCALHOST_REGEX.matches(candidate)) {
            return if (isValidPort(candidate)) "https://$candidate" else null
        }

        if (IPV4_REGEX.matches(candidate)) {
            return if (isValidPort(candidate)) "https://$candidate" else null
        }

        if (IPV6_REGEX.matches(candidate)) {
            return if (isValidPort(candidate)) "https://$candidate" else null
        }

        if (DOMAIN_REGEX.matches(candidate)) {
            val tld = extractTld(candidate)
            if (tld !in NON_TLD_EXTENSIONS && isValidPort(candidate)) {
                return "https://$candidate"
            }
            return null
        }

        return null
    }

    private fun extractTld(candidate: String): String {
        val delimiterIndex = candidate.indexOfAny(charArrayOf('/', '?', '#'))
        val hostAndPort = if (delimiterIndex != -1) candidate.substring(0, delimiterIndex) else candidate
        val colonIndex = hostAndPort.lastIndexOf(':')
        val host = if (colonIndex != -1) hostAndPort.substring(0, colonIndex) else hostAndPort
        val lastDot = host.lastIndexOf('.')
        return if (lastDot != -1) host.substring(lastDot + 1).lowercase() else ""
    }

    private fun isValidPort(candidate: String): Boolean {
        val delimiterIndex = candidate.indexOfAny(charArrayOf('/', '?', '#'))
        val hostAndPort = if (delimiterIndex != -1) candidate.substring(0, delimiterIndex) else candidate
        val colonIndex = hostAndPort.lastIndexOf(':')
        if (colonIndex == -1) return true
        val portStr = hostAndPort.substring(colonIndex + 1)
        val port = portStr.toIntOrNull() ?: return false
        return port in 1..65535
    }

    fun stripTrailingPunctuation(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1] in ".,;!") {
            end--
        }
        return text.substring(0, end)
    }
}
