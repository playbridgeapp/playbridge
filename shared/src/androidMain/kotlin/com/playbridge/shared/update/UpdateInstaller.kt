package com.playbridge.shared.update

import java.io.File

/**
 * Flavour-specific install path. FOSS builds sideload; Play builds refuse to.
 */
interface UpdateInstaller {
    val selfUpdateSupported: Boolean

    suspend fun download(url: String, onProgress: (Float?) -> Unit): File

    fun launchInstall(apk: File)
}
