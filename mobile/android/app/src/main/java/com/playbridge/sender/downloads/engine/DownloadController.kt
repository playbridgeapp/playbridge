package com.playbridge.sender.downloads.engine

import java.io.File

/**
 * Cross-process pause/cancel signalling via marker files in the download's temp dir.
 *
 * Ported from the super-video-downloader reference (`FileBasedDownloadController`).
 * Why files instead of in-memory flags or IPC: the worker runs under WorkManager's
 * own process management and may be in a different process than whoever requests the
 * pause. A file on disk is the simplest signal both sides can see without coupling.
 *
 * The strategy polls [isInterrupted] in its segment/chunk loop and throws
 * [DownloadPausedException] / CancellationException accordingly.
 */
class DownloadController(private val downloadDir: File) {

    private val pauseFlag = File(downloadDir, PAUSE_FLAG)
    private val cancelFlag = File(downloadDir, CANCEL_FLAG)

    /** Clear stale flags at the start of a (re)run. Keeps already-downloaded segments. */
    fun start() {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        pauseFlag.delete()
        cancelFlag.delete()
    }

    fun requestPause() {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        pauseFlag.createNewFile()
    }

    fun requestCancel() {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        cancelFlag.createNewFile()
    }

    fun isPauseRequested(): Boolean = pauseFlag.exists()
    fun isCancelRequested(): Boolean = cancelFlag.exists()

    /** True if any stop-like action is pending — cheap to call inside tight loops. */
    fun isInterrupted(): Boolean = isPauseRequested() || isCancelRequested()

    companion object {
        const val PAUSE_FLAG = ".pause"
        const val CANCEL_FLAG = ".cancel"
    }
}
