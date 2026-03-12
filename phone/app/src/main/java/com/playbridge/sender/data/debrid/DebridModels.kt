package com.playbridge.sender.data.debrid

import kotlinx.serialization.Serializable

/**
 * Common data models abstracting different Debrid provider APIs.
 */

@Serializable
data class DebridTorrentInfo(
    val id: String,
    val filename: String,
    val hash: String,
    val bytes: Long,
    val progress: Double,
    val status: TorrentStatus,
    val files: List<DebridFile>
)

enum class TorrentStatus {
    WAITING_FILES_SELECTION,
    DOWNLOADING,
    READY,
    ERROR,
    UNKNOWN
}

@Serializable
data class DebridFile(
    val id: String,
    val path: String,
    val bytes: Long,
    val selected: Int,
    val link: String = ""
) {
    val isVideo: Boolean
        get() = path.lowercase().let { 
            it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") || 
            it.endsWith(".wmv") || it.endsWith(".webm") || it.endsWith(".ts") ||
            it.endsWith(".m4v") || it.endsWith(".3gp") || it.endsWith(".flv") || it.endsWith(".mov")
        }
}

@Serializable
data class DebridUnrestrictedLink(
    val id: String,
    val filename: String,
    val mimeType: String,
    val filesize: Long,
    val downloadUrl: String
)
