package com.playbridge.sender.data.collection

import com.playbridge.sender.data.iptv.encodeHeaders

/** Everything needed to add one playable item to a collection (built at each call site). */
data class CollectionItemDraft(
    val title: String,
    val url: String,
    val kind: String = CollectionItemKind.WEB,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val logo: String? = null,
    val sourceTag: String? = CollectionSource.MANUAL,
)

/**
 * Owns collection storage: create/rename/delete collections and add/remove/reorder their items.
 * See COLLECTIONS_PLAN.md §7. Koin singleton.
 */
class CollectionsRepository(
    private val collectionDao: CollectionDao,
    private val itemDao: CollectionItemDao,
) {
    fun observeCollections() = collectionDao.observeAll()
    fun observeItems(collectionId: Long) = itemDao.observeForCollection(collectionId)
    suspend fun getCollection(id: Long) = collectionDao.getById(id)

    suspend fun createCollection(name: String): Long {
        val now = System.currentTimeMillis()
        return collectionDao.insert(CollectionEntity(name = name.trim(), addedAt = now, updatedAt = now))
    }

    suspend fun renameCollection(id: Long, name: String) =
        collectionDao.rename(id, name.trim(), System.currentTimeMillis())

    suspend fun deleteCollection(id: Long) = collectionDao.deleteById(id)

    /**
     * Add [draft] to a collection. A collection is treated as a **set keyed by URL**: if the
     * same URL is already present, nothing is inserted. Returns true if added, false if it was
     * already there.
     */
    suspend fun addItem(collectionId: Long, draft: CollectionItemDraft): Boolean {
        val url = draft.url.trim()
        if (itemDao.findByUrl(collectionId, url) != null) return false
        val nextIndex = itemDao.maxOrderIndex(collectionId) + 1
        itemDao.insert(
            CollectionItemEntity(
                collectionId = collectionId,
                title = draft.title.trim().ifBlank { url },
                url = url,
                kind = draft.kind,
                mimeType = draft.mimeType,
                headersJson = encodeHeaders(draft.headers),
                logo = draft.logo,
                sourceTag = draft.sourceTag,
                orderIndex = nextIndex,
            ),
        )
        touch(collectionId)
        return true
    }

    suspend fun removeItem(item: CollectionItemEntity) {
        itemDao.deleteById(item.id)
        touch(item.collectionId)
    }

    /** Move an item one slot up or down by swapping orderIndex with its neighbour. */
    suspend fun moveItem(collectionId: Long, itemId: Long, up: Boolean) {
        val items = itemDao.getForCollection(collectionId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val swapWith = if (up) index - 1 else index + 1
        if (swapWith !in items.indices) return
        val a = items[index]
        val b = items[swapWith]
        // Swap their order positions.
        itemDao.updateAll(
            listOf(a.copy(orderIndex = b.orderIndex), b.copy(orderIndex = a.orderIndex)),
        )
        collectionDao.markChanged(collectionId, items.size, System.currentTimeMillis())
    }

    private suspend fun touch(collectionId: Long) {
        val count = itemDao.countFor(collectionId)
        collectionDao.markChanged(collectionId, count, System.currentTimeMillis())
    }
}
