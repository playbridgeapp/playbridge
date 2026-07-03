package com.playbridge.sender.data.nuvio

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NuvioScraperDao {
    @Query("SELECT * FROM nuvio_scrapers WHERE repoUrl = :repoUrl ORDER BY name")
    suspend fun getForRepo(repoUrl: String): List<NuvioScraperEntity>

    @Query("SELECT * FROM nuvio_scrapers WHERE repoUrl = :repoUrl ORDER BY name")
    fun observeForRepo(repoUrl: String): Flow<List<NuvioScraperEntity>>

    @Query("SELECT * FROM nuvio_scrapers ORDER BY name")
    fun observeAll(): Flow<List<NuvioScraperEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scrapers: List<NuvioScraperEntity>)

    @Update
    suspend fun update(scraper: NuvioScraperEntity)

    @Query("DELETE FROM nuvio_scrapers WHERE repoUrl = :repoUrl")
    suspend fun deleteForRepo(repoUrl: String)

    @Query("DELETE FROM nuvio_scrapers WHERE repoUrl = :repoUrl AND scraperId NOT IN (:keepIds)")
    suspend fun deleteStale(repoUrl: String, keepIds: List<String>)
}
