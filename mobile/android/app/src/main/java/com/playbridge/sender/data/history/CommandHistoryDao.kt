package com.playbridge.sender.data.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CommandHistoryEntity)

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<CommandHistoryEntity>>

    @Delete
    suspend fun delete(entry: CommandHistoryEntity)

    @Query("SELECT id, timestamp FROM command_history ORDER BY timestamp DESC")
    suspend fun idsAndTimestamps(): List<CommandHistoryStamp>

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM command_history")
    suspend fun clear()
}

/** Just enough of a legacy row to count it against the shared history limit. */
data class CommandHistoryStamp(val id: Long, val timestamp: Long)
