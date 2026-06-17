package com.playbridge.sender.data.iptv

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Source kind for an IPTV playlist. Stored as the entity's `sourceType` string. */
object IptvSourceType {
    const val URL = "URL"
    const val FILE = "FILE"
}

/** Result of a channel reachability probe. Stored as the entity's `probeStatus` string. */
object IptvProbeStatus {
    const val UNKNOWN = "UNKNOWN"
    const val ACTIVE = "ACTIVE"
    const val DEAD = "DEAD"
}

/**
 * A user-added IPTV (M3U/M3U8) playlist. The actual channels are cached separately in
 * [IptvChannelEntity] so reopening the playlist is instant; "Update" re-parses [source].
 *
 * [source] is either an `http(s)://` URL (when [sourceType] == [IptvSourceType.URL]) or a
 * persisted `content://` document URI (when [sourceType] == [IptvSourceType.FILE]).
 */
@Entity(tableName = "iptv_playlists")
data class IptvPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val sourceType: String,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val channelCount: Int = 0,
)

/**
 * A single cached channel belonging to a playlist. Populated by the M3U parser; the probe
 * columns are filled in lazily by the reachability probe (see IptvRepository.probe).
 */
@Entity(
    tableName = "iptv_channels",
    foreignKeys = [
        ForeignKey(
            entity = IptvPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class IptvChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val url: String,
    val logo: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val orderIndex: Int = 0,
    /** JSON map of request headers (Referer / User-Agent) extracted from the playlist. */
    val headersJson: String? = null,
    val probeStatus: String = IptvProbeStatus.UNKNOWN,
    val probeLatencyMs: Int? = null,
    val probedAt: Long? = null,
)
