package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {

    @Query("SELECT * FROM transfer_history ORDER BY timestampMillis DESC LIMIT 20")
    fun getRecentTransfers(): Flow<List<TransferHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(entry: TransferHistoryEntity): Long

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()
}
