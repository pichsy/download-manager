package com.pichs.shanhai.base.provider.sql

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.pichs.xbase.utils.UiKit

/**
 * 实体类，表结构
 */
@Entity(
    tableName = "shanhai_info",
    primaryKeys = ["key"]
)
data class ShanhaiInfo(
    var key: String,
    var value: String,
)


/**
 * 数据库操作
 */
@Dao
interface ShanhaiInfoDao {

    @Upsert
    fun insertOrUpdate(info: ShanhaiInfo): Long

    @Query("select * from shanhai_info where `key` = :key")
    fun query(key: String): ShanhaiInfo?

    @Delete
    fun delete(info: ShanhaiInfo): Int

    @Query("delete from shanhai_info where `key` = :key")
    fun delete(key: String): Int

    @Query("select * from shanhai_info")
    fun queryAll(): List<ShanhaiInfo>?

    @Query("delete from shanhai_info")
    fun deleteAll(): Int

}


/**
 * 数据库
 */
@Database(entities = [ShanhaiInfo::class], version = 2)
abstract class ShanhaiDatabase : RoomDatabase() {
    abstract fun shanhaiInfoDao(): ShanhaiInfoDao
}


/**
 * 数据库操作工厂
 */
object ShanhaiInfoSqlManager {

    private val databseName = "shanhai_content_provider.db"

    private val database by lazy {
        Room.databaseBuilder(UiKit.getApplication(), ShanhaiDatabase::class.java, databseName)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()
    }

    private val shanhaiInfoDao: ShanhaiInfoDao by lazy {
        database.shanhaiInfoDao()
    }

    fun insertOrUpdate(info: ShanhaiInfo): Long {
        return shanhaiInfoDao.insertOrUpdate(info)
    }

    fun query(key: String): ShanhaiInfo? {
        return shanhaiInfoDao.query(key)
    }

    fun delete(info: ShanhaiInfo): Int {
        return shanhaiInfoDao.delete(info)
    }

    fun delete(key: String): Int {
        return shanhaiInfoDao.delete(key)
    }

    fun queryAll(): List<ShanhaiInfo>? {
        return shanhaiInfoDao.queryAll()
    }

    fun deleteAll(): Int {
        return shanhaiInfoDao.deleteAll()
    }

}
