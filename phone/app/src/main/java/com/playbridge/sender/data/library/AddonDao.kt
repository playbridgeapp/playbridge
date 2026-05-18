package com.playbridge.sender.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AddonDao {

    /** Live list ordered by user-defined position, falling back to install time. */
    @Query("SELECT * FROM installed_addons ORDER BY sortOrder ASC, installedAt DESC")
    fun getAll(): Flow<List<InstalledAddonEntity>>

    @Query("SELECT * FROM installed_addons ORDER BY sortOrder ASC, installedAt DESC")
    suspend fun getAllSync(): List<InstalledAddonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(addon: InstalledAddonEntity)

    /** Full replace — used for toggling isEnabled and updating sortOrder. */
    @Update
    suspend fun update(addon: InstalledAddonEntity)

    @Delete
    suspend fun delete(addon: InstalledAddonEntity)

    @Query("DELETE FROM installed_addons WHERE manifestUrl = :manifestUrl")
    suspend fun deleteByUrl(manifestUrl: String)

    @Query("SELECT COUNT(*) FROM installed_addons")
    suspend fun count(): Int
}
