package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus

@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val sizeBytes: Long,
    val timestampMillis: Long,
    val direction: TransferDirection,
    val method: TransferMethod,
    val status: TransferStatus,
    val peerDeviceName: String,
    val isSplitApk: Boolean = false,
    val durationMillis: Long = 0L,
    val errorReason: String? = null,
    val isGenericFile: Boolean = false,
    val mimeType: String? = null,
    val savedFilePath: String? = null
)
