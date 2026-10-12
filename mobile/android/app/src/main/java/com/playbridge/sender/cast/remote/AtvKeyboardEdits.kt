package com.playbridge.sender.cast.remote

internal const val ATV_TEXT_DELETE_CAP = 64

/**
 * Android TV `insert_text` inserts. The sheet sends the added suffix, or one delete per removed
 * code point. An empty replace is never sent.
 */
internal fun atvKeyboardEdits(previous: String, next: String): List<RemoteAction> {
    if (previous == next) return emptyList()
    val common = commonPrefixLength(previous, next)
    val removed = previous.substring(common)
    val added = next.substring(common)
    val deletes = removed.codePointCount(0, removed.length).coerceAtMost(ATV_TEXT_DELETE_CAP)
    return buildList {
        repeat(deletes) { add(RemoteAction.DeleteText) }
        if (added.isNotEmpty()) add(RemoteAction.ReplaceText(added))
    }
}

private fun commonPrefixLength(previous: String, next: String): Int {
    val limit = minOf(previous.length, next.length)
    var index = 0
    while (index < limit && previous[index] == next[index]) index++
    if (index > 0 && previous[index - 1].isHighSurrogate()) index--
    return index
}
