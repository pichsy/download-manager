package com.pichs.download.breakpoint

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pichs.download.Downloader
import java.security.AccessController.getContext

@Database(entities = [DownloadChunk::class, DownloadBreakPointData::class], version = 1, exportSchema = false)
abstract class DownloadBreakPointDatabase : RoomDatabase() {
    abstract fun chunkDao(): DownloadChunkDao

    abstract fun breakPointDao(): DownloadBreakPointDao

    companion object {
        private const val DB_NAME = "xp_download_chunk_db"
        val database by lazy {
            Room
                .databaseBuilder(
                    Downloader.with().getContext().createDeviceProtectedStorageContext(),
                    DownloadBreakPointDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .allowMainThreadQueries()
                .build()
        }
    }
}
