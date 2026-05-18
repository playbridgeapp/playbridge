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

    @Query("DELETE FROM command_history")
    suspend fun clear()
}
