package com.playbridge.shared.browser

/** Base64 alphabet only, so a text command cannot escape its JSON or script string. Empty is Clear. */
fun focusTextPayload(b64: String): String? {
    if (b64.any { !it.isLetterOrDigit() && it != '+' && it != '/' && it != '=' }) return null
    return b64
}

/** Debug logs accept these codes. A frame result that contains field text becomes "err". */
fun focusResultToken(value: String): String = when (value) {
    "ok", "no_focus" -> value
    else -> "err"
}
