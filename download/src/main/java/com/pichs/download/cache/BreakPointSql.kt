package com.pichs.download.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.pichs.download.Downloader

@Dao
interface BreakPointDao {

    @Upsert
    fun insert(info: BreakPointData)
}

@Database(entities = [BreakPointData::class], version = 1)
abstract class BreakPointDataBase : RoomDatabase() {
    abstract fun breakPointDao(): BreakPointDao
}

/**
 * 断点缓存
 */
object BreakPointCache {
    private val database by lazy {
        Room.databaseBuilder(
            Downloader.getContext(),
            BreakPointDataBase::class.java, tableName
        ).build()
    }


    fun insert(info: BreakPointData) {
        database.breakPointDao().insert(info)
    }


}