package com.playbridge.sender.update

import android.content.Context
import java.io.File

/**
 * Play-flavor stub. Google Play's Device and Network Abuse policy prohibits apps
 * distributed through Play from downloading and installing APKs themselves, so the
 * Play build contains no self-update code at all — [UpdateChecker] sees
 * [selfUpdateSupported] = false and routes users to the Play listing instead.
 */
class ApkInstaller(@Suppress("UNUSED_PARAMETER") appContext: Context) {

    /** Play builds never sideload — UpdateChecker treats every install as Play-sourced. */
    val selfUpdateSupported: Boolean get() = false

    suspend fun download(url: String, onProgress: (Float?) -> Unit): File =
        throw UnsupportedOperationException("Self-update is not available in Play builds")

    fun launchInstall(apk: File) {
        throw UnsupportedOperationException("Self-update is not available in Play builds")
    }

    companion object {
        /** No downloaded APKs can exist in Play builds — nothing to clean. */
        fun cleanupStaleApks(context: Context, maxAgeMillis: Long = 0L) = Unit
    }
}
