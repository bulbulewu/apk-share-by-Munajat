package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus

class TransferConverters {
    @TypeConverter
    fun fromDirection(value: TransferDirection): String = value.name

    @TypeConverter
    fun toDirection(value: String): TransferDirection =
        runCatching { TransferDirection.valueOf(value) }.getOrDefault(TransferDirection.SENT)

    @TypeConverter
    fun fromMethod(value: TransferMethod): String = value.name

    @TypeConverter
    fun toMethod(value: String): TransferMethod =
        runCatching { TransferMethod.valueOf(value) }.getOrDefault(TransferMethod.NEARBY)

    @TypeConverter
    fun fromStatus(value: TransferStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): TransferStatus =
        runCatching { TransferStatus.valueOf(value) }.getOrDefault(TransferStatus.SUCCESS)
}

@Database(entities = [TransferHistoryEntity::class], version = 1, exportSchema = false)
@TypeConverters(TransferConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "apk_share_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
