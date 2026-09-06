package com.playbridge.sender.library

/** Keep the first occurrence and its position when providers repeat or overlap pages. */
internal fun <T, K> mergeUniquePage(current: List<T>, incoming: List<T>, key: (T) -> K): List<T> =
    (current + incoming).distinctBy(key)
