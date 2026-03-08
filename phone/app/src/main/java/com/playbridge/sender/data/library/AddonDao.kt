package com.playbridge.sender.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AddonDao {

    @Query("SELECT * FROM installed_addons ORDER BY installedAt DESC")
    fun getAll(): Flow<List<InstalledAddonEntity>>

    @Query("SELECT * FROM installed_addons ORDER BY installedAt DESC")
    suspend fun getAllSync(): List<InstalledAddonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(addon: InstalledAddonEntity)

    @Delete
    suspend fun delete(addon: InstalledAddonEntity)

    @Query("DELETE FROM installed_addons WHERE manifestUrl = :manifestUrl")
    suspend fun deleteByUrl(manifestUrl: String)

    @Query("SELECT COUNT(*) FROM installed_addons")
    suspend fun count(): Int
}
