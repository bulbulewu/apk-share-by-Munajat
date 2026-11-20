package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferHistoryRepository(private val dao: TransferHistoryDao) {
    val recentTransfers: Flow<List<TransferHistoryEntity>> = dao.getRecentTransfers()

    suspend fun recordTransfer(entry: TransferHistoryEntity) {
        dao.insertTransfer(entry)
    }

    suspend fun deleteTransfer(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }
}
