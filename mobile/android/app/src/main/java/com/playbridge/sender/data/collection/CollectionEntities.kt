package com.playbridge.sender.data.collection

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Playback kind for a collection item. Stored as the entity's `kind` string. */
object CollectionItemKind {
    const val WEB = "WEB"     // http(s) stream (optional headers)
    const val LOCAL = "LOCAL" // content:// file URI
}

/** Where an item was curated from (drives the row icon + analytics). */
object CollectionSource {
    const val MANUAL = "manual"
    const val IPTV = "iptv"
    const val PHONE_FILE = "phone_file"
    const val DEBRID = "debrid"
    const val BROWSER = "browser"
    const val HISTORY = "history"
}

/**
 * A user-curated, ordered playlist of concrete playable items ([CollectionItemEntity]).
 * Distinct from the TMDB watchlist (Library) and from IPTV sources — items here store
 * everything needed to play directly. See COLLECTIONS_PLAN.md.
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val itemCount: Int = 0,
)

@Entity(
    tableName = "collection_items",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("collectionId")],
)
data class CollectionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val title: String,
    val url: String,
    val kind: String = CollectionItemKind.WEB,
    val mimeType: String? = null,
    /** JSON map of request headers (Referer / User-Agent) for headered web streams. */
    val headersJson: String? = null,
    val logo: String? = null,
    val sourceTag: String? = null,
    val orderIndex: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)
