package com.playbridge.shared.io

import com.playbridge.shared.SharedContext
import okio.Path
import okio.Path.Companion.toPath

actual object Paths {
    actual val cacheDir: Path
        get() = SharedContext.appContext.cacheDir.absolutePath.toPath()

    actual val documentsDir: Path
        get() = SharedContext.appContext.filesDir.absolutePath.toPath()
}
