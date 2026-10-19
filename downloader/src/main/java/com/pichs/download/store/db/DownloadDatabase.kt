package com.pichs.download.store.db

import android.content.Context
import androidx.annotation.Keep
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Keep
@Database(
    entities = [DownloadEntity::class, DownloadChunkEntity::class, DownloadMetaEntity::class],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
internal abstract class DownloadDatabase : RoomDatabase() {
    abstract fun dao(): DownloadDao
    abstract fun chunkDao(): DownloadChunkDao
    abstract fun metaDao(): DownloadMetaDao

    companion object {
        @Volatile private var INSTANCE: DownloadDatabase? = null

        /** 6 -> 7：新增请求头/远端校验器表 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_meta` (" +
                        "`taskId` TEXT NOT NULL, " +
                        "`headersEncoded` TEXT NOT NULL, " +
                        "`eTag` TEXT, " +
                        "`lastModified` TEXT, " +
                        "`totalSize` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`))"
                )
            }
        }

        /** 7 -> 8：任务级校验配置（算法/期望值/失败策略） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_meta` ADD COLUMN `checksumType` TEXT")
                db.execSQL("ALTER TABLE `download_meta` ADD COLUMN `checksumValue` TEXT")
                db.execSQL("ALTER TABLE `download_meta` ADD COLUMN `checksumOnFail` TEXT")
            }
        }

        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext.createDeviceProtectedStorageContext(), DownloadDatabase::class.java, "app_downloads_manager.db")
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration() // 更早版本的缺失迁移仍直接重建数据库
                .build().also { INSTANCE = it }
        }
    }
}
