package com.pichs.download.store.db

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Keep
@Dao
internal interface DownloadMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadMetaEntity)

    @Query("SELECT * FROM download_meta WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): DownloadMetaEntity?

    @Query("SELECT * FROM download_meta")
    suspend fun getAll(): List<DownloadMetaEntity>

    @Query("DELETE FROM download_meta WHERE taskId = :taskId")
    suspend fun delete(taskId: String)

    @Query("DELETE FROM download_meta")
    suspend fun clear()

    /**
     * 清理没有对应任务的元数据；activeTaskIds 覆盖已创建但尚未落库的任务，避免误删。
     */
    @Query(
        "DELETE FROM download_meta WHERE taskId NOT IN (SELECT id FROM downloads) " +
                "AND taskId NOT IN (:activeTaskIds)"
    )
    suspend fun deleteOrphans(activeTaskIds: List<String>): Int

    @Query("DELETE FROM download_meta WHERE taskId NOT IN (SELECT id FROM downloads)")
    suspend fun deleteAllOrphans(): Int
}
