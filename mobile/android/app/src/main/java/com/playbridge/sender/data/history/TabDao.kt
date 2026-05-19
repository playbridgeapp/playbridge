package com.playbridge.sender.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC, lastAccessTime ASC")
    fun getAll(): List<TabEntity>

    @Insert
    fun insertAll(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs")
    fun deleteAll()

    @Transaction
    fun updateTabs(tabs: List<TabEntity>) {
        deleteAll()
        insertAll(tabs)
    }
}
