package com.playbridge.shared.io

import okio.Path

expect object Paths {
    val cacheDir: Path            // subtitle cache, resume data, etc.
    val documentsDir: Path        // user-persistent state
}
