package com.pichs.download.store.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
@Database(entities = [DownloadEntity::class, DownloadChunkEntity::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
internal abstract class DownloadDatabase : RoomDatabase() {
    abstract fun dao(): DownloadDao
    abstract fun chunkDao(): DownloadChunkDao

    companion object {
        @Volatile private var INSTANCE: DownloadDatabase? = null
        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, "downloads.db")
                .fallbackToDestructiveMigration() // 破坏性迁移，直接重建数据库
                .build().also { INSTANCE = it }
        }
    }
}
