package com.playbridge.sender.data.collection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CollectionEntity): Long

    @Update
    suspend fun update(item: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE collections SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE collections SET itemCount = :count, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markChanged(id: Long, count: Int, updatedAt: Long)
}

@Dao
interface CollectionItemDao {
    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY orderIndex ASC")
    fun observeForCollection(collectionId: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY orderIndex ASC")
    suspend fun getForCollection(collectionId: Long): List<CollectionItemEntity>

    @Query("SELECT COUNT(*) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun countFor(collectionId: Long): Int

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun maxOrderIndex(collectionId: Long): Int

    /** A collection is a set keyed by URL — used to skip duplicates on add. */
    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId AND url = :url LIMIT 1")
    suspend fun findByUrl(collectionId: Long, url: String): CollectionItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CollectionItemEntity): Long

    @Update
    suspend fun update(item: CollectionItemEntity)

    @Update
    suspend fun updateAll(items: List<CollectionItemEntity>)

    @Query("DELETE FROM collection_items WHERE id = :id")
    suspend fun deleteById(id: Long)
}
