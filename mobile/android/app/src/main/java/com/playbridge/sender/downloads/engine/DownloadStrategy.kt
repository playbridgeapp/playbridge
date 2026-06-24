package com.playbridge.sender.downloads.engine

import java.io.File

/**
 * One way of fetching a [DownloadKind] into a finished media file. The worker picks a
 * strategy by [kind] and delegates the whole download to it.
 *
 * Implementations MUST:
 *  - write all temp work under [downloadDir] (so resume can scan it and cancel can wipe it),
 *  - poll [controller] for pause/cancel and bail promptly,
 *  - be idempotent / resumable: a re-run over a partially-populated [downloadDir] should
 *    continue, not restart.
 */
interface DownloadStrategy {

    val kind: DownloadKind

    /**
     * @return the final, complete media file inside [downloadDir]. The worker publishes it
     *         to MediaStore Downloads afterwards.
     * @throws DownloadPausedException when [controller] requests pause.
     * @throws kotlinx.coroutines.CancellationException when [controller] requests cancel.
     */
    suspend fun download(
        request: DownloadRequest,
        downloadDir: File,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ): File
}
