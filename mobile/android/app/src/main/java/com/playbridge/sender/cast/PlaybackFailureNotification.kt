package com.playbridge.sender.cast

/** Only receiver-owned sentences may appear on the lock screen, never arbitrary wire text. */
internal fun playbackFailureNotification(deviceName: String, message: String?): String? {
    if (message == null) return null
    val sentences = setOf(
        "Playback failed on the TV", "The media couldn't be loaded",
        "This video format isn't supported", "The TV couldn't reach the video server",
        "The video stopped loading", "The TV couldn't decode this video",
    )
    val httpSentence = Regex("^(The link was refused|The video wasn't found|The media couldn't be loaded) \\(HTTP [1-5][0-9]{2}\\)$")
    val safeMessage = message.takeIf { it in sentences || httpSentence.matches(it) } ?: "The media couldn't be loaded"
    val safeName = deviceName.trim().take(80).takeIf {
        it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char in " -_'()" }
    } ?: "TV"
    return "Playback failed on $safeName: $safeMessage"
}
