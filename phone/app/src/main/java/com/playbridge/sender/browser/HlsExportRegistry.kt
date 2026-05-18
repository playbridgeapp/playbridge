package com.playbridge.sender.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process registry that tracks which HLS URLs have been exported to the
 * public Downloads folder. Written by [MediaDownloadService] on completion,
 * read by [DownloadsScreen] to show the "Open in player" button reactively.
 *
 * State is in-memory only — survives process lifecycle but resets on app restart.
 * That's fine: the exported file persists in Downloads, the button just won't
 * auto-appear after a cold start (the user can still open the file via Files).
 */
object HlsExportRegistry {

    enum class ExportState {
        EXPORTING,
        DONE,
        FAILED
    }

    data class ExportResult(
        val state: ExportState,
        val path: String? = null  // non-null when DONE
    )

    private val _exports = MutableStateFlow<Map<String, ExportResult>>(emptyMap())

    /** Observe this from Compose to reactively update the UI. */
    val exports: StateFlow<Map<String, ExportResult>> = _exports.asStateFlow()

    fun markExporting(url: String) {
        _exports.value = _exports.value + (url to ExportResult(ExportState.EXPORTING))
    }

    fun markDone(url: String, path: String) {
        _exports.value = _exports.value + (url to ExportResult(ExportState.DONE, path))
    }

    fun markFailed(url: String) {
        _exports.value = _exports.value + (url to ExportResult(ExportState.FAILED))
    }

    fun get(url: String): ExportResult? = _exports.value[url]
}
